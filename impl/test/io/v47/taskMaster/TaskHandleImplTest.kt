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

import io.v47.taskMaster.events.TaskHandleEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import testsupport.mocks.MockTaskInput
import utils.createMockTaskHandle
import org.junit.jupiter.api.*
import testsupport.utils.deferredOnce
import testsupport.utils.record
import org.junit.jupiter.api.Assertions as A

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskHandleImplTest {
    private lateinit var taskHandle: TaskHandleImpl<out Any, out Any>

    @Test
    fun `it runs`() = runBlocking {
        taskHandle = createMockTaskHandle(MockTaskInput())
        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)

        taskHandle.run()

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)

        assertThrows<IllegalStateException> {
            taskHandle.error
        }

        val output = assertDoesNotThrow {
            taskHandle.output
        }

        A.assertEquals(Unit, output)
    }

    @Test
    fun `it suspends and resumes`() = runBlocking {
        taskHandle = createMockTaskHandle(MockTaskInput(suspendable = true))
        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)
        val recordedEvents = taskHandle.record(TaskHandleEvent.StateChanged)

        taskHandle.run()

        delay(50)

        A.assertEquals(Result.success(true), taskHandle.suspend())
        A.assertEquals(TaskState.Suspended, taskHandle.state)

        delay(50)

        A.assertEquals(Result.success(true), taskHandle.resume())
        A.assertEquals(TaskState.Running, taskHandle.state)

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)
        A.assertEquals(
            listOf(
                TaskHandleEvent.StateChanged(TaskState.Running, TaskState.Waiting, taskHandle),
                TaskHandleEvent.StateChanged(TaskState.Suspended, TaskState.Running, taskHandle),
                TaskHandleEvent.StateChanged(TaskState.Running, TaskState.Suspended, taskHandle),
                TaskHandleEvent.StateChanged(TaskState.Complete, TaskState.Running, taskHandle)
            ),
            recordedEvents
        )
    }

    @Test
    fun `it fails to run`() = runBlocking {
        taskHandle = createMockTaskHandle(MockTaskInput(failWhileRunning = true))
        val failedEvent = taskHandle.deferredOnce(TaskHandleEvent.Failed)
        val recordedEvents = taskHandle.record(TaskHandleEvent.StateChanged)

        taskHandle.run()

        failedEvent.await()

        A.assertEquals(TaskState.Failed, taskHandle.state)

        assertThrows<IllegalStateException> {
            taskHandle.output
        }

        val error = assertDoesNotThrow {
            taskHandle.error
        }

        A.assertEquals("This is a random failure", error.message)
        A.assertTrue(error is IllegalArgumentException)

        A.assertEquals(
            listOf(
                TaskHandleEvent.StateChanged(TaskState.Running, TaskState.Waiting, taskHandle),
                TaskHandleEvent.StateChanged(TaskState.Failed, TaskState.Running, taskHandle)
            ),
            recordedEvents
        )
    }

    @Test
    fun `it fails to cleanup after failing`() = runBlocking {
        taskHandle = createMockTaskHandle(MockTaskInput(failWhileRunning = true, failDuringCleanUp = true))
        val failedEvent = taskHandle.deferredOnce(TaskHandleEvent.Failed)

        taskHandle.run()

        failedEvent.await()

        A.assertEquals(TaskState.Failed, taskHandle.state)
    }

    @Test
    fun `it fails to suspend`() = runBlocking {
        taskHandle = createMockTaskHandle(MockTaskInput(suspendable = true, setSuspended = true, failToSuspend = true))
        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)
        val recordedEvents = taskHandle.record(TaskHandleEvent.StateChanged)

        taskHandle.run()

        delay(50)

        A.assertTrue(taskHandle.suspend().isFailure)
        A.assertEquals(TaskState.Running, taskHandle.state)

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)

        A.assertEquals(
            listOf(
                TaskHandleEvent.StateChanged(TaskState.Running, TaskState.Waiting, taskHandle),
                TaskHandleEvent.StateChanged(TaskState.Complete, TaskState.Running, taskHandle)
            ),
            recordedEvents
        )
    }

    @Test
    fun `it fails to resume`() = runBlocking {
        taskHandle = createMockTaskHandle(MockTaskInput(suspendable = true, setSuspended = true, failToResume = true))

        taskHandle.run()

        delay(50)

        A.assertEquals(Result.success(true), taskHandle.suspend())
        A.assertEquals(TaskState.Suspended, taskHandle.state)

        A.assertTrue(taskHandle.resume().isFailure)
        A.assertEquals(TaskState.Suspended, taskHandle.state)

        taskHandle.kill()

        A.assertEquals(TaskState.Killed, taskHandle.state)
    }

    @Test
    fun `it reruns a killed task`() = runBlocking {
        taskHandle = createMockTaskHandle(MockTaskInput())

        taskHandle.run()

        delay(50)

        A.assertEquals(TaskState.Running, taskHandle.state)

        taskHandle.kill()

        A.assertEquals(TaskState.Killed, taskHandle.state)

        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)

        taskHandle.run()
        A.assertEquals(TaskState.Running, taskHandle.state)

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)
    }

    @Test
    fun `it shouldn't be broken by calling run multiple times`() = runBlocking {
        taskHandle = createMockTaskHandle(MockTaskInput())
        val completedEvent = taskHandle.deferredOnce(TaskHandleEvent.Completed)
        val recordedEvents = taskHandle.record(TaskHandleEvent.StateChanged)

        repeat(10) {
            taskHandle.run()

            delay(10)
        }

        A.assertEquals(TaskState.Running, taskHandle.state)

        completedEvent.await()

        A.assertEquals(TaskState.Complete, taskHandle.state)

        A.assertEquals(
            listOf(
                TaskHandleEvent.StateChanged(TaskState.Running, TaskState.Waiting, taskHandle),
                TaskHandleEvent.StateChanged(TaskState.Complete, TaskState.Running, taskHandle)
            ),
            recordedEvents
        )
    }

    @AfterEach
    fun cleanup() {
        taskHandle.clear()
    }
}
