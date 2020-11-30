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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext

internal class TaskMasterImpl(
    private val configuration: Configuration,
    override val coroutineContext: CoroutineContext
) : TaskMaster, CoroutineScope, EventEmitter by DefaultEventEmitter() {
    private var running = true

    private val taskHandlesMutex = Mutex()
    override val taskHandles = mutableSetOf<TaskHandleImpl<*, *>>()

    override suspend fun <I, O> add(
        factory: TaskFactory<I, O>,
        input: I,
        priority: Int,
        runCondition: RunCondition?
    ): TaskHandle<I, O> = taskHandlesMutex.withLock {
        require(running) { "This task master was stopped. Cannot add new tasks" }

        @Suppress("UNCHECKED_CAST")
        val existingTaskHandle = taskHandles.find { it.matches(factory, input) } as? TaskHandleImpl<I, O>
        if (existingTaskHandle != null) {
            if (configuration.rescheduleOnAdd) {
                existingTaskHandle.priority = priority
                existingTaskHandle.runCondition = runCondition

                scheduleTask(existingTaskHandle, forceRun = true)
            }

            existingTaskHandle
        } else {
            val cost = factory.calculateCost(input)
            require(cost > 0.0) { "The calculated cost of a task must be greater than 0" }

            val taskHandle = TaskHandleImpl(
                factory,
                this,
                input,
                priority,
                runCondition,
                cost
            )

            taskHandles.add(taskHandle)

            scheduleTask(taskHandle, forceRun = false)

            taskHandle
        }
    }

    override suspend fun <I, O> suspend(taskHandle: TaskHandle<I, O>): Boolean = taskHandlesMutex.withLock {
        require(taskHandle is TaskHandleImpl && taskHandle in taskHandles) { "Unknown task handle" }

        val suspendResult = taskHandle.suspend()
        handleSuspendResult(taskHandle, suspendResult, rescheduleTasks = true)

        suspendResult == TaskHandleImpl.SuspendResult.Suspended
    }

    override suspend fun <I, O> resume(taskHandle: TaskHandle<I, O>): Boolean = taskHandlesMutex.withLock {
        require(taskHandle is TaskHandleImpl && taskHandle in taskHandles) { "Unknown task handle" }

        val resumeResult = taskHandle.resume()
        handleResumeResult(taskHandle, resumeResult)

        resumeResult == TaskHandleImpl.ResumeResult.Resumed
    }

    override suspend fun <I, O> kill(taskHandle: TaskHandle<I, O>, remove: Boolean) = taskHandlesMutex.withLock {
        require(taskHandle is TaskHandleImpl && taskHandle in taskHandles) { "Unknown task handle" }

        taskHandle.kill()

        if (remove)
            taskHandles.remove(taskHandle)
    }

    override suspend fun stop(killRunning: Boolean) = taskHandlesMutex.withLock {
        running = false

        if (killRunning)
            taskHandles.forEach {
                if (!it.isDone)
                    it.kill()
            }

        taskHandles.clear()
    }

    private suspend fun scheduleTask(taskHandle: TaskHandleImpl<*, *>, forceRun: Boolean) {
        TODO("Not yet implemented")
    }

    private suspend fun handleSuspendResult(
        taskHandle: TaskHandleImpl<*, *>,
        suspendResult: TaskHandleImpl.SuspendResult,
        rescheduleTasks: Boolean
    ) {
        TODO("Not yet implemented")
    }

    private suspend fun handleResumeResult(
        taskHandle: TaskHandleImpl<*, *>,
        resumeResult: TaskHandleImpl.ResumeResult
    ) {
        TODO("Not yet implemented")
    }
}
