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

import io.v47.events.EventKey
import io.v47.taskMaster.events.TaskHandleEvent
import io.v47.taskMaster.events.TaskHandleEventEmitter
import io.v47.taskMaster.utils.identityToString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.receiveOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.atomic.AtomicReference

private val taskRunnableStates = setOf(TaskState.Waiting, TaskState.Killed)
private val taskScheduleableStates = setOf(TaskState.Waiting, TaskState.Suspended, TaskState.Killed)
private val taskResumeableStates = setOf(TaskState.Suspended)

internal val TaskHandleImpl<*, *>.isRunnable: Boolean
    get() = state in taskRunnableStates

internal val TaskHandleImpl<*, *>.isScheduleable: Boolean
    get() = state in taskScheduleableStates

internal val TaskHandleImpl<*, *>.isResumeable: Boolean
    get() = state in taskResumeableStates

@Suppress("LongParameterList")
internal class TaskHandleImpl<I, O>(
    private val factory: TaskFactory<I, O>,
    private val coroutineScope: CoroutineScope,
    override val input: I,
    override var priority: Int,
    override var runCondition: RunCondition?,
    val cost: Double,
    val sequenceNumber: Long
) : TaskHandle<I, O>, TaskHandleEventEmitter<I, O>() {
    override val id = UUID.randomUUID().toString()

    override var suspendable = false

    private val _output = AtomicReference<O>(null)

    override val output: O
        get() = if (state == TaskState.Complete)
            _output.get() ?: throw IllegalStateException("state is 'Complete' but no output value set")
        else
            throw IllegalStateException("Task state is not 'Complete'. Cannot retrieve output value")

    private val _error = AtomicReference<Throwable>(null)

    override val error: Throwable
        get() = if (state == TaskState.Failed)
            _error.get() ?: throw IllegalStateException("state is 'Failed' but no error set")
        else
            throw IllegalStateException("Task state is not 'Failed'. Cannot retrieve error")

    private val _state = AtomicReference(TaskState.Waiting)

    override val state: TaskState
        get() = _state.get()

    private lateinit var taskLogger: Logger

    private val currentTaskMutex = Mutex()
    private var currentTaskJob: Job? = null

    private var eventChannel: Channel<Pair<EventKey<TaskHandleEvent>, TaskHandleEvent>>? = null
    private var currentEventChannelDone: Deferred<Unit>? = null

    suspend fun run() =
        currentTaskMutex.withLock {
            if (isRunnable) {
                val ec = Channel<Pair<EventKey<TaskHandleEvent>, TaskHandleEvent>>(Channel.BUFFERED)
                eventChannel = ec

                val ecDone = CompletableDeferred<Unit>()
                currentEventChannelDone = ecDone

                coroutineScope.launch {
                    while (isActive) {
                        @Suppress("EXPERIMENTAL_API_USAGE")
                        val event = ec.receiveOrNull() ?: break

                        emit(event.first, event.second)
                    }

                    ecDone.complete(Unit)
                }

                val task = factory.create(input, this)
                suspendable = task is SuspendableTask
                taskLogger = LoggerFactory.getLogger(task::class.java)!!

                if (taskLogger.isTraceEnabled)
                    taskLogger.trace("Created with input {}", input)

                currentTask = task
                setStateAndEmit(TaskState.Running)

                val self = this

                currentTaskJob = coroutineScope.launch {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        if (taskLogger.isTraceEnabled)
                            taskLogger.trace("Running...")

                        val result = task.run()

                        if (taskLogger.isTraceEnabled)
                            taskLogger.trace("Finished!")

                        _output.set(result)

                        setStateAndEmit(TaskState.Complete)
                        eventChannel?.send(TaskHandleEvent.Completed to TaskHandleEvent.Completed(result as Any, self))

                        currentTaskMutex.withLock {
                            performCleanup(null)
                        }
                    } catch (x: Throwable) {
                        if (x !is CancellationException) {
                            taskLogger.warn("Execution failed with exception. Handle: $self", x)

                            _error.set(x)

                            setStateAndEmit(TaskState.Failed)
                            eventChannel?.send(TaskHandleEvent.Failed to TaskHandleEvent.Failed(x, self))

                            currentTaskMutex.withLock {
                                performCleanup(task)
                            }
                        }

                        if (x is CancellationException)
                            throw x
                    }
                }

                true
            } else
                false
        }

    suspend fun suspend(): Result<Boolean> =
        currentTaskMutex.withLock {
            if (suspendable && state == TaskState.Running) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    val result = (currentTask as SuspendableTask<I, O>).suspend()
                    if (result)
                        setStateAndEmit(TaskState.Suspended)

                    Result.success(result)
                } catch (x: Throwable) {
                    taskLogger.warn("Failed to suspend with error. Handle: $this", x)
                    Result.failure(x)
                }
            } else
                Result.success(false)
        }

    suspend fun resume(): Result<Boolean> =
        currentTaskMutex.withLock {
            if (suspendable && state == TaskState.Suspended) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    val result = (currentTask as SuspendableTask<I, O>).resume()
                    if (result)
                        setStateAndEmit(TaskState.Running)

                    Result.success(result)
                } catch (x: Throwable) {
                    taskLogger.warn("Failed to resume task with error. Handle: $this", x)
                    Result.failure(x)
                }
            } else
                Result.success(false)
        }

    suspend fun kill() {
        currentTaskMutex.withLock {
            if (state == TaskState.Killed)
                return

            setStateAndEmit(TaskState.Killed)
            eventChannel!!.send(TaskHandleEvent.Killed to TaskHandleEvent.Killed(this))

            currentTaskJob?.let {
                it.cancel("job killed")
                it.join()
            }

            currentTaskJob = null

            _output.set(null)
            _error.set(null)

            currentTask?.let {
                performCleanup(it)
            }

            if (taskLogger.isTraceEnabled)
                taskLogger.trace("Task killed. Handle: {}", this)
        }
    }

    private suspend fun performCleanup(task: Task<I, O>?) {
        runCatching {
            task?.clean()
        }.onFailure { cleanX ->
            if (taskLogger.isDebugEnabled)
                taskLogger.debug("Clean-up failed with exception. Handle: $this", cleanX)
        }

        currentTask = null
        currentTaskJob = null

        eventChannel!!.close()
        eventChannel = null

        currentEventChannelDone!!.await()
        currentEventChannelDone = null
    }

    private suspend fun setStateAndEmit(newState: TaskState) {
        val previousState = _state.getAndUpdate { oldState ->
            if (newState != oldState)
                newState
            else
                oldState
        }

        if (newState != previousState)
            eventChannel?.send(
                TaskHandleEvent.StateChanged to TaskHandleEvent.StateChanged(
                    newState,
                    previousState,
                    this
                )
            )
    }

    override fun toString(): String {
        return "TaskHandle(" +
                "id='$id', " +
                "state=$state, " +
                "factory=${factory.identityToString()}, " +
                "input=$input, " +
                "priority=$priority, " +
                "runCondition=${runCondition?.identityToString() ?: "null"}, " +
                "output=${_output.get() ?: "<unavailable>"}, " +
                "error=${_error.get() ?: "<unavailable>"}, " +
                "suspendable=$suspendable" +
                ")"
    }

    fun <I> matches(factory: TaskFactory<*, *>, input: I) =
        this.factory.javaClass == factory::class.java && this.input == input
}
