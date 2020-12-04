/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2020, Alex Katlein
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package io.v47.taskMaster

import horus.events.DefaultEventEmitter
import horus.events.EventEmitter
import horus.events.EventKey
import io.v47.taskMaster.TaskState.Running
import io.v47.taskMaster.TaskState.Suspended
import io.v47.taskMaster.events.TaskHandleEvent
import io.v47.taskMaster.events.TaskMasterEvent
import io.v47.taskMaster.exceptions.ResumeFailedException
import io.v47.taskMaster.exceptions.SuspendFailedException
import io.v47.taskMaster.utils.createTaskHandleTreeSet
import io.v47.taskMaster.utils.ifTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

@Suppress("TooManyFunctions")
internal class TaskMasterImpl(
    private val configuration: Configuration,
    override val coroutineContext: CoroutineContext
) : TaskMaster, CoroutineScope, EventEmitter by DefaultEventEmitter() {
    private val log = LoggerFactory.getLogger(javaClass)!!

    override val taskHandles: Set<TaskHandle<*, *>>
        get() = runBlocking {
            taskHandlesMutex.withLock {
                _taskHandles.toSet()
            }
        }

    //region taskHandlesMutex scope
    private val taskHandlesMutex = Mutex()
    private val _taskHandles = createTaskHandleTreeSet()
    private var taskHandlesSequence = 0L

    private val runningTaskHandles = createTaskHandleTreeSet()
    private val suspendedTaskHandles = createTaskHandleTreeSet()

    private val consumedBudget: Double
        get() = runningTaskHandles.sumOf { it.cost }

    private val availableBudget: Double
        get() = configuration.totalBudget - consumedBudget

    private val currentDebt: Double
        get() = suspendedTaskHandles.sumOf { it.cost }
    //endregion

    private val eventChannel: Channel<Pair<EventKey<TaskMasterEvent>, TaskMasterEvent>> =
        Channel(
            Channel.BUFFERED,
            BufferOverflow.DROP_OLDEST
        )

    init {
        launch {
            while (isActive) {
                val (key, event) = eventChannel.receive()
                emit(key, event)
            }
        }
    }

    //region public API
    override suspend fun <I, O> add(
        factory: TaskFactory<I, O>,
        input: I,
        priority: Int,
        runCondition: RunCondition?
    ): TaskHandle<I, O> = taskHandlesMutex.withLock {
        @Suppress("UNCHECKED_CAST")
        val existingTaskHandle = _taskHandles.find { it.matches(factory, input) } as? TaskHandleImpl<I, O>
        if (existingTaskHandle != null) {
            if (configuration.rescheduleOnAdd) {
                existingTaskHandle.priority = priority
                existingTaskHandle.runCondition = runCondition

                if (log.isTraceEnabled)
                    log.trace("Rescheduling existing task {}", existingTaskHandle)

                scheduleTask(existingTaskHandle, force = true)
            }

            existingTaskHandle
        } else {
            val cost = factory.calculateCost(input)
            require(cost > 0.0) { "The calculated cost of a task must be greater than 0" }
            require(cost <= configuration.totalBudget) { "The cost of this task exceeds the total budget" }

            val taskHandle = TaskHandleImpl(
                factory,
                this,
                input,
                priority,
                runCondition,
                cost,
                taskHandlesSequence++
            )

            if (log.isDebugEnabled)
                log.debug("Adding new task {}", taskHandle)

            _taskHandles.add(taskHandle)
            eventChannel.send(TaskMasterEvent.TaskAdded to TaskMasterEvent.TaskAdded(taskHandle))

            addTaskHandleEventListeners(taskHandle)
            scheduleTask(taskHandle, force = false)

            taskHandle
        }
    }

    override suspend fun <I, O> suspend(
        taskHandle: TaskHandle<I, O>,
        force: Boolean,
        consumeFreedBudget: Boolean
    ): Boolean =
        configuration.maximumDebt?.let { maximumDebt ->
            taskHandlesMutex.withLock {
                require(taskHandle is TaskHandleImpl && taskHandle in _taskHandles) { "Unknown task handle" }

                val desiredDebt = maximumDebt - taskHandle.cost

                @Suppress("ComplexCondition")
                if (
                    taskHandle in runningTaskHandles && taskHandle.state == Running &&
                    (force || currentDebt <= desiredDebt)
                ) {
                    if (log.isDebugEnabled)
                        log.debug("Attempting to{} suspend task {}", if (force) " forcefully" else "", taskHandle)

                    if (force)
                        killSuspendedUntil(desiredDebt = desiredDebt)

                    if (currentDebt <= desiredDebt)
                        taskHandle.suspend()
                            .getOrElse { throw SuspendFailedException(taskHandle, it) }
                            .ifTrue {
                                if (log.isTraceEnabled)
                                    log.trace("Suspended task {}", taskHandle)

                                runningTaskHandles.remove(taskHandle)

                                if (consumeFreedBudget)
                                    consumeRemainingBudget()

                                // IMPORTANT: THIS MUST COME AFTER consumeRemainingBudget
                                // Otherwise this suspended task might get resumed right away
                                suspendedTaskHandles.add(taskHandle)
                            }
                    else
                        false
                } else
                    false
            }
        } ?: false

    override suspend fun <I, O> resume(taskHandle: TaskHandle<I, O>, force: Boolean): Boolean =
        taskHandlesMutex.withLock {
            require(taskHandle is TaskHandleImpl && taskHandle in _taskHandles) { "Unknown task handle" }

            @Suppress("ComplexCondition")
            if (
                taskHandle in suspendedTaskHandles && taskHandle.state == Suspended &&
                (force || availableBudget >= taskHandle.cost)
            ) {
                if (log.isDebugEnabled)
                    log.debug("Attempting to{} resume task {}", if (force) " forcefully" else "", taskHandle)

                if (force) {
                    trySuspendingRunningTasksUntil(taskHandle.cost)
                    killRunningTasksUntil(taskHandle.cost)
                }

                if (availableBudget >= taskHandle.cost)
                    taskHandle.resume()
                        .getOrElse { throw ResumeFailedException(taskHandle, it) }
                        .ifTrue {
                            if (log.isTraceEnabled)
                                log.trace("Resumed task {}", taskHandle)

                            suspendedTaskHandles.remove(taskHandle)
                            runningTaskHandles.add(taskHandle)
                        }
                else
                    false
            } else
                false
        }

    override suspend fun <I, O> kill(taskHandle: TaskHandle<I, O>, remove: Boolean, consumeFreedBudget: Boolean) =
        taskHandlesMutex.withLock {
            require(taskHandle is TaskHandleImpl && taskHandle in _taskHandles) { "Unknown task handle" }

            doKillTaskHandle(taskHandle, remove)

            if (consumeFreedBudget) {
                // Need to temporarily remove the task so that it isn't
                // restarted immediately
                val didExist = _taskHandles.remove(taskHandle)

                consumeRemainingBudget()

                // Here add it again after other tasks were started or resumed
                if (didExist)
                    _taskHandles.add(taskHandle)
            }
        }
    //endregion

    //region task handle event listeners
    private suspend fun taskHandleOnStateChanged(e: TaskHandleEvent.StateChanged) {
        eventChannel.send(
            TaskMasterEvent.TaskStateChanged to TaskMasterEvent.TaskStateChanged(
                e.taskHandle,
                e.state,
                e.previousState
            )
        )
    }

    private suspend fun taskHandleOnFailed(e: TaskHandleEvent.Failed) {
        log.warn("Task failed: ${e.taskHandle}", e.error)

        taskHandlesMutex.withLock {
            _taskHandles.remove(e.taskHandle)
            runningTaskHandles.remove(e.taskHandle)
            suspendedTaskHandles.remove(e.taskHandle)

            removeTaskHandleEventListeners(e.taskHandle as TaskHandleImpl<*, *>)

            consumeRemainingBudget()
        }
    }

    private suspend fun taskHandleOnCompleted(e: TaskHandleEvent.Completed) {
        if (log.isDebugEnabled)
            log.debug("Task complete: {}", e.taskHandle)

        taskHandlesMutex.withLock {
            _taskHandles.remove(e.taskHandle)
            runningTaskHandles.remove(e.taskHandle)

            removeTaskHandleEventListeners(e.taskHandle as TaskHandleImpl<*, *>)

            consumeRemainingBudget()
        }
    }
    //endregion

    //region task handle event support functions
    private fun addTaskHandleEventListeners(taskHandle: TaskHandleImpl<*, *>) {
        taskHandle.on(TaskHandleEvent.StateChanged, this::taskHandleOnStateChanged)
        taskHandle.on(TaskHandleEvent.Failed, this::taskHandleOnFailed)
        taskHandle.on(TaskHandleEvent.Completed, this::taskHandleOnCompleted)
    }

    private fun removeTaskHandleEventListeners(taskHandle: TaskHandleImpl<*, *>) {
        taskHandle.remove(this::taskHandleOnStateChanged)
        taskHandle.remove(this::taskHandleOnFailed)
        taskHandle.remove(this::taskHandleOnCompleted)
    }
    //endregion

    //region private support functions
    private suspend fun scheduleTask(taskHandle: TaskHandleImpl<*, *>, force: Boolean): Boolean =
        if (taskHandle.isScheduleable) {
            if (taskHandle.runCondition?.invoke() != false) {
                if (log.isTraceEnabled)
                    log.trace("Trying to run or resume task {}", taskHandle)

                if (availableBudget < taskHandle.cost)
                    trySuspendingRunningTasksUntil(desiredAvailableBudget = taskHandle.cost)

                if (force)
                    killRunningTasksUntil(desiredAvailableBudget = taskHandle.cost)

                val didStart = if (availableBudget >= taskHandle.cost)
                    startOrResumeTask(taskHandle)
                else
                    false

                consumeRemainingBudget()

                didStart
            } else
                false
        } else
            false

    private suspend fun trySuspendingRunningTasksUntil(desiredAvailableBudget: Double) {
        val maximumDebt = configuration.maximumDebt

        if (availableBudget >= desiredAvailableBudget || maximumDebt == null)
            return

        val runningTaskHandlesIter = runningTaskHandles.iterator()
        while (
            availableBudget < desiredAvailableBudget &&
            runningTaskHandlesIter.hasNext()
        ) {
            val runningTaskHandle = runningTaskHandlesIter.next()

            if (!runningTaskHandle.suspendable)
                continue

            if (log.isTraceEnabled)
                log.trace("Trying to suspend task {}", runningTaskHandle)

            val desiredDebt = maximumDebt - runningTaskHandle.cost

            killSuspendedUntil(desiredDebt = desiredDebt)

            if (currentDebt <= desiredDebt) {
                runningTaskHandle.suspend()
                    .getOrElse {
                        if (log.isDebugEnabled)
                            log.debug("Failed to suspend task $runningTaskHandle", it)

                        if (configuration.killIfSuspendFails)
                            doKillTaskHandle(runningTaskHandle, remove = !configuration.rescheduleKilledTasks)

                        false
                    }
                    .ifTrue {
                        if (log.isTraceEnabled)
                            log.trace("Suspended task {}", runningTaskHandle)

                        runningTaskHandlesIter.remove()
                        suspendedTaskHandles.add(runningTaskHandle)
                    }
            }
        }
    }

    private suspend fun killSuspendedUntil(desiredDebt: Double) {
        if (!configuration.killSuspended)
            return

        val suspendedTaskHandlesIter = suspendedTaskHandles.iterator()

        while (
            currentDebt > desiredDebt &&
            suspendedTaskHandlesIter.hasNext()
        ) {
            val suspendedTaskHandle = suspendedTaskHandlesIter.next()
            suspendedTaskHandlesIter.remove()

            doKillTaskHandle(suspendedTaskHandle, remove = !configuration.rescheduleKilledTasks)
        }
    }

    private suspend fun killRunningTasksUntil(desiredAvailableBudget: Double) {
        if (!configuration.killRunningTasks || availableBudget >= desiredAvailableBudget)
            return

        val runningTaskHandlesIter = runningTaskHandles.iterator()
        while (
            availableBudget < desiredAvailableBudget &&
            runningTaskHandlesIter.hasNext()
        ) {
            val runningTaskHandle = runningTaskHandlesIter.next()
            runningTaskHandlesIter.remove()

            doKillTaskHandle(runningTaskHandle, remove = !configuration.rescheduleKilledTasks)
        }
    }

    private suspend fun consumeRemainingBudget() {
        if (log.isTraceEnabled)
            log.trace("Consuming remaining budget")

        val suspendedTaskHandlesIter = suspendedTaskHandles.descendingIterator()
        while (availableBudget > 0.0 && suspendedTaskHandlesIter.hasNext()) {
            val suspendedTaskHandle = suspendedTaskHandlesIter.next()

            if (availableBudget >= suspendedTaskHandle.cost)
                startOrResumeTask(suspendedTaskHandle)
        }

        val taskHandlesIter = _taskHandles.descendingIterator()
        while (availableBudget > 0.0 && taskHandlesIter.hasNext()) {
            val taskHandle = taskHandlesIter.next()

            if (
                taskHandle.isScheduleable && availableBudget >= taskHandle.cost &&
                taskHandle.runCondition?.invoke() != false
            )
                startOrResumeTask(taskHandle)
        }
    }

    private suspend fun startOrResumeTask(taskHandle: TaskHandleImpl<*, *>): Boolean =
        when {
            taskHandle.isRunnable -> {
                val didStart = taskHandle.run()
                if (didStart) {
                    if (log.isTraceEnabled)
                        log.trace("Started task {}", taskHandle)

                    runningTaskHandles.add(taskHandle)
                }

                didStart
            }
            taskHandle.isResumeable -> {
                taskHandle.resume()
                    .getOrElse {
                        if (log.isDebugEnabled)
                            log.debug("Failed to resume task $taskHandle", it)

                        if (configuration.killIfResumeFails)
                            doKillTaskHandle(taskHandle, remove = !configuration.rescheduleKilledTasks)

                        false
                    }.ifTrue {
                        if (log.isTraceEnabled)
                            log.trace("Resumed task {}", taskHandle)

                        suspendedTaskHandles.remove(taskHandle)
                        runningTaskHandles.add(taskHandle)
                    }
            }
            else -> throw IllegalStateException(
                "Tried to call startOrResumeTask on a task handle " +
                        "that's not runnable or resumeable"
            )
        }

    private suspend fun doKillTaskHandle(taskHandle: TaskHandleImpl<*, *>, remove: Boolean = false) {
        if (log.isTraceEnabled)
            log.trace("Killing task {}", taskHandle)

        runningTaskHandles.remove(taskHandle)
        suspendedTaskHandles.remove(taskHandle)

        taskHandle.kill()

        if (remove || !configuration.rescheduleKilledTasks) {
            if (log.isTraceEnabled)
                log.trace("Removing killed task {}", taskHandle)

            removeTaskHandleEventListeners(taskHandle)
            _taskHandles.remove(taskHandle)
        }
    }
    //endregion
}
