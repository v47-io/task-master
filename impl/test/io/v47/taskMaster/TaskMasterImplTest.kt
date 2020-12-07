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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testsupport.mocks.MockTaskInput
import testsupport.utils.deferredOnce
import testsupport.utils.record
import utils.assertDoesntRunLongerThan
import utils.mockFactory
import utils.withCoroutineContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskMasterImplTest {
    @Test
    fun `it creates a valid TaskMaster instance`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 10.0
            coroutineContext = it
        }

        assertEquals(TaskMasterImpl::class.java, taskMaster.javaClass)
    }

    @Test
    fun `it correctly runs one task`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 10.0
            coroutineContext = it
        }

        val taskHandle = taskMaster.add(
            mockFactory,
            MockTaskInput()
        )

        taskHandle.deferredOnce(TaskHandleEvent.Completed).await()

        assertEquals(TaskState.Complete, taskHandle.state)
        assertEquals(Unit, taskHandle.output)
    }

    @Test
    fun `it correctly runs multiple tasks`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 10.0
            coroutineContext = it
        }

        assertDoesntRunLongerThan(2500) {
            val firstTaskHandle = taskMaster.add(
                mockFactory,
                MockTaskInput(duration = 2000)
            )

            val secondTaskHandle = taskMaster.add(
                mockFactory,
                MockTaskInput()
            )

            secondTaskHandle.deferredOnce(TaskHandleEvent.Completed).await()

            assertEquals(TaskState.Running, firstTaskHandle.state)
            assertEquals(TaskState.Complete, secondTaskHandle.state)

            firstTaskHandle.deferredOnce(TaskHandleEvent.Completed).await()

            assertEquals(TaskState.Complete, firstTaskHandle.state)
        }
    }

    @Test
    fun `it suspends the first task after adding the second`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 10.0
            maximumDebt = 10.0
            coroutineContext = it
        }

        val firstTaskHandle = taskMaster.add(
            mockFactory,
            MockTaskInput(
                cost = 7.0,
                duration = 2000,
                suspendable = true,
                setSuspended = true
            )
        )

        delay(50)

        val firstTaskHandleEvents = firstTaskHandle.record(TaskHandleEvent.StateChanged)

        assertEquals(TaskState.Running, firstTaskHandle.state)

        val secondTaskHandle = taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 5.0)
        )

        assertEquals(TaskState.Running, secondTaskHandle.state)
        assertEquals(TaskState.Suspended, firstTaskHandle.state)

        secondTaskHandle.deferredOnce(TaskHandleEvent.Completed).await()

        assertEquals(TaskState.Complete, secondTaskHandle.state)

        firstTaskHandle.deferredOnce(TaskHandleEvent.Completed).await()

        assertEquals(TaskState.Complete, firstTaskHandle.state)

        assertEquals(
            listOf(
                TaskHandleEvent.StateChanged(
                    TaskState.Suspended,
                    TaskState.Running,
                    firstTaskHandle
                ),
                TaskHandleEvent.StateChanged(
                    TaskState.Running,
                    TaskState.Suspended,
                    firstTaskHandle
                ),
                TaskHandleEvent.StateChanged(TaskState.Complete, TaskState.Running, firstTaskHandle)
            ),
            firstTaskHandleEvents
        )
    }

    @Test
    fun `it kills the first tasks after repeatedly adding the second`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 10.0
            coroutineContext = it
        }

        val firstTaskHandle = taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 7.0)
        )

        delay(50)

        assertEquals(TaskState.Running, firstTaskHandle.state)

        val secondTaskHandle = taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 5.0)
        )

        assertEquals(TaskState.Running, firstTaskHandle.state)
        assertEquals(TaskState.Waiting, secondTaskHandle.state)

        val secondTaskHandleAgain = taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 5.0)
        )

        assertSame(secondTaskHandle, secondTaskHandleAgain)

        assertEquals(TaskState.Killed, firstTaskHandle.state)
        assertEquals(TaskState.Running, secondTaskHandle.state)

        secondTaskHandle.deferredOnce(TaskHandleEvent.Completed).await()

        assertEquals(TaskState.Complete, secondTaskHandle.state)

        delay(50)

        assertEquals(TaskState.Killed, firstTaskHandle.state)
    }

    @Test
    fun `it kills a suspended task to reduce debt`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 7.0
            maximumDebt = 10.0
            rescheduleKilledTasks = true
            coroutineContext = it
        }

        val taskToBeSuspendedThenKilled = taskMaster.add(
            mockFactory,
            MockTaskInput(
                cost = 5.0,
                suspendable = true,
                setSuspended = true
            ),
            TaskPriority.LOW
        )

        delay(50)

        assertEquals(TaskState.Running, taskToBeSuspendedThenKilled.state)

        val taskToBeSuspended = taskMaster.add(
            mockFactory,
            MockTaskInput(
                cost = 7.0,
                suspendable = true,
                setSuspended = true
            ),
            TaskPriority.NORMAL
        )

        delay(50)

        assertEquals(TaskState.Running, taskToBeSuspended.state)
        assertEquals(TaskState.Suspended, taskToBeSuspendedThenKilled.state)

        val finalTask = taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 5.0, duration = 200),
            TaskPriority.HIGH
        )

        assertEquals(TaskState.Running, finalTask.state)
        assertEquals(TaskState.Suspended, taskToBeSuspended.state)
        assertEquals(TaskState.Killed, taskToBeSuspendedThenKilled.state)

        finalTask.deferredOnce(TaskHandleEvent.Completed).await()

        assertEquals(TaskState.Complete, finalTask.state)
        assertEquals(TaskState.Running, taskToBeSuspended.state)
        assertEquals(TaskState.Killed, taskToBeSuspendedThenKilled.state)

        taskToBeSuspended.deferredOnce(TaskHandleEvent.Completed).await()

        assertEquals(TaskState.Complete, finalTask.state)
        assertEquals(TaskState.Complete, taskToBeSuspended.state)
        assertEquals(TaskState.Running, taskToBeSuspendedThenKilled.state)

        taskToBeSuspendedThenKilled.deferredOnce(TaskHandleEvent.Completed).await()

        assertEquals(TaskState.Complete, finalTask.state)
        assertEquals(TaskState.Complete, taskToBeSuspended.state)
        assertEquals(TaskState.Complete, taskToBeSuspendedThenKilled.state)
    }

    @Test
    fun `it kills a task that failed to suspend`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 7.0
            maximumDebt = 5.0
            killIfSuspendFails = true
            coroutineContext = it
        }

        val taskToBeKilled = taskMaster.add(
            mockFactory,
            MockTaskInput(
                cost = 5.0,
                suspendable = true,
                setSuspended = true,
                failToSuspend = true
            )
        )

        delay(50)

        assertEquals(TaskState.Running, taskToBeKilled.state)

        val secondTask = taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 5.0),
            TaskPriority.HIGH
        )

        delay(50)

        assertEquals(TaskState.Killed, taskToBeKilled.state)
        assertEquals(TaskState.Running, secondTask.state)

        secondTask.deferredOnce(TaskHandleEvent.Completed).await()

        delay(50)

        assertEquals(TaskState.Killed, taskToBeKilled.state)
        assertEquals(TaskState.Complete, secondTask.state)
    }

    @Test
    fun `it kills a task that failed to resume`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 7.0
            maximumDebt = 5.0
            killIfResumeFails = true
            coroutineContext = it
        }

        val taskThatSuspends = taskMaster.add(
            mockFactory,
            MockTaskInput(
                cost = 5.0,
                suspendable = true,
                setSuspended = true,
                failToResume = true
            )
        )

        delay(50)

        assertEquals(TaskState.Running, taskThatSuspends.state)

        val secondTask = taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 5.0),
            TaskPriority.HIGH
        )

        delay(50)

        assertEquals(TaskState.Suspended, taskThatSuspends.state)
        assertEquals(TaskState.Running, secondTask.state)

        secondTask.deferredOnce(TaskHandleEvent.Completed).await()

        delay(50)

        assertEquals(TaskState.Killed, taskThatSuspends.state)
        assertEquals(TaskState.Complete, secondTask.state)
    }

    @Test
    fun `it handles the failure of a task`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 10.0
            coroutineContext = it
        }

        val failingTask = taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 7.0, failWhileRunning = true)
        )

        failingTask.deferredOnce(TaskHandleEvent.Failed).await()

        assertEquals(TaskState.Failed, failingTask.state)

        assertEquals(
            emptySet<TaskHandle<*, *>>(),
            taskMaster.taskHandles
        )
    }

    @Test
    fun `it resumes a task forcefully`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 7.0
            maximumDebt = 5.0
            coroutineContext = it
        }

        val taskToResume = taskMaster.add(
            mockFactory,
            MockTaskInput(
                cost = 5.0,
                suspendable = true,
                setSuspended = true
            )
        )

        taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 5.0)
        )

        val taskToKill = taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 5.0)
        )

        assertEquals(TaskState.Suspended, taskToResume.state)
        assertEquals(TaskState.Running, taskToKill.state)

        delay(50)

        assertFalse(taskMaster.resume(taskToResume))

        assertTrue(taskMaster.resume(taskToResume, force = true))

        delay(50)

        assertEquals(TaskState.Running, taskToResume.state)
        assertEquals(TaskState.Killed, taskToKill.state)

        taskToResume.deferredOnce(TaskHandleEvent.Completed).await()

        assertEquals(TaskState.Complete, taskToResume.state)
        assertEquals(TaskState.Killed, taskToKill.state)
    }

    @Test
    fun `it suspends a task`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 7.0
            maximumDebt = 5.0
            coroutineContext = it
        }

        val task = taskMaster.add(
            mockFactory,
            MockTaskInput(
                cost = 5.0,
                suspendable = true,
                setSuspended = true
            )
        )

        assertEquals(TaskState.Running, task.state)

        delay(50)

        assertTrue(taskMaster.suspend(task))

        assertEquals(TaskState.Suspended, task.state)

        assertTrue(taskMaster.resume(task))

        assertEquals(TaskState.Running, task.state)

        task.deferredOnce(TaskHandleEvent.Completed).await()

        assertEquals(TaskState.Complete, task.state)
    }

    @Test
    fun `it kills a task and doesn't restart it immediately`() = withCoroutineContext {
        val taskMaster = TaskMaster {
            totalBudget = 7.0
            rescheduleKilledTasks = true
            coroutineContext = it
        }

        val task = taskMaster.add(
            mockFactory,
            MockTaskInput(cost = 5.0)
        )

        delay(50)

        taskMaster.kill(task)

        delay(50)

        assertEquals(TaskState.Killed, task.state)
        assertEquals(
            setOf(task),
            taskMaster.taskHandles
        )
    }
}
