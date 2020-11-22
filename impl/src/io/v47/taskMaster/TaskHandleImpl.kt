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
import io.v47.taskMaster.events.TaskHandleEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.atomic.AtomicReference

@Suppress("LongParameterList")
internal class TaskHandleImpl<I, O>(
    private val factory: TaskFactory<I, O>,
    private val coroutineScope: CoroutineScope,
    override val type: Class<out Task<I, O>>,
    override val input: I,
    override val priority: Int,
    override val runCondition: RunCondition?,
    val cost: Double
) : TaskHandle<I, O>, EventEmitter by DefaultEventEmitter() {
    override val id = UUID.randomUUID().toString()

    override val suspendable by lazy {
        SuspendableTask::class.java.isAssignableFrom(type)
    }

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

    val isRunnable: Boolean
        get() {
            var result = false
            _state.updateAndGet { state ->
                result = state == TaskState.Waiting || state == TaskState.Killed
                state
            }

            return result
        }

    private val taskLogger = LoggerFactory.getLogger(type)!!

    private val currentTaskMutex = Mutex()
    private var currentTask: Task<I, O>? = null
    private var currentTaskJob: Job? = null

    suspend fun run() =
        currentTaskMutex.withLock {
            if (isRunnable && runCondition?.invoke() != false) {
                val task = factory.create(input, this)

                taskLogger.trace("Created with input {}", input)

                currentTask = task
                setStateAndEmit(TaskState.Running)

                currentTaskJob = coroutineScope.launch {
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        taskLogger.trace("Running...")

                        val result = supervisorScope {
                            task.run()
                        }

                        taskLogger.trace("Finished!")

                        currentTaskMutex.withLock {
                            currentTask = null
                            currentTaskJob = null
                        }

                        _output.set(result)

                        setStateAndEmit(TaskState.Complete)
                        emit(TaskHandleEvent.Completed, TaskHandleEvent.Completed(result as Any))
                    } catch (x: Throwable) {
                        if (x !is CancellationException) {
                            taskLogger.warn("Execution failed with exception", x)

                            performCleanup(task)

                            _error.set(x)

                            setStateAndEmit(TaskState.Failed)
                            emit(TaskHandleEvent.Failed, TaskHandleEvent.Failed(x))
                        }

                        if (x is CancellationException)
                            throw x
                    }
                }

                true
            } else
                false
        }

    suspend fun suspend(): SuspendResult =
        currentTaskMutex.withLock {
            if (suspendable && state == TaskState.Running) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    val result = (currentTask as SuspendableTask<I, O>).suspend()
                    if (result)
                        setStateAndEmit(TaskState.Suspended)

                    if (result)
                        SuspendResult.Suspended
                    else
                        SuspendResult.NotSuspended
                } catch (x: Throwable) {
                    taskLogger.warn("Failed to suspend with error", x)
                    SuspendResult.Failed
                }
            } else
                SuspendResult.NotSuspended
        }

    suspend fun resume(): ResumeResult =
        currentTaskMutex.withLock {
            if (suspendable && state == TaskState.Suspended) {
                @Suppress("TooGenericExceptionCaught")
                try {
                    val result = (currentTask as SuspendableTask<I, O>).resume()
                    if (result)
                        setStateAndEmit(TaskState.Running)

                    if (result)
                        ResumeResult.Resumed
                    else
                        ResumeResult.NotResumed
                } catch (x: Throwable) {
                    taskLogger.warn("Failed to resume task with error", x)
                    ResumeResult.Failed
                }
            } else
                ResumeResult.NotResumed
        }

    suspend fun kill() {
        currentTaskMutex.withLock {
            if (state == TaskState.Suspended) {
                currentTaskJob?.let {
                    it.cancel("job killed")
                    it.join()
                }

                currentTaskJob = null
            }

            currentTask?.let {
                performCleanup(it)
            }

            _output.set(null)
            _error.set(null)
            setStateAndEmit(TaskState.Killed)

            clear()

            taskLogger.debug("Task killed")
        }
    }

    private suspend fun performCleanup(task: Task<I, O>) {
        runCatching {
            task.clean()
        }.onFailure { cleanX ->
            LoggerFactory.getLogger(type).debug("Clean-up failed with exception", cleanX)
        }

        currentTask = null
        currentTaskJob = null
    }

    private suspend fun setStateAndEmit(newState: TaskState) {
        var changed = false
        _state.updateAndGet { oldState ->
            if (newState != oldState) {
                changed = true
                newState
            } else
                oldState
        }

        if (changed)
            emit(TaskHandleEvent.StateChanged, TaskHandleEvent.StateChanged(newState))
    }

    override fun toString(): String {
        return "TaskHandle(" +
                "id='$id', " +
                "type=${type.typeName}, " +
                "input=$input, " +
                "priority=$priority, " +
                "runCondition=${runCondition?.let { "<set>" } ?: "<unset>"}, " +
                "output=${_output.get() ?: "<unavailable>"}, " +
                "error=${_error.get() ?: "<unavailable>"}, " +
                "state=$state, " +
                "suspendable=$suspendable" +
                ")"
    }

    enum class SuspendResult {
        Suspended,
        NotSuspended,
        Failed
    }

    enum class ResumeResult {
        Resumed,
        NotResumed,
        Failed
    }
}
