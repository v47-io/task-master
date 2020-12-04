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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
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
    fun `it correctly runs multiple tasks`()  = withCoroutineContext {
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
                TaskHandleEvent.StateChanged(TaskState.Suspended, TaskState.Running, firstTaskHandle),
                TaskHandleEvent.StateChanged(TaskState.Running, TaskState.Suspended, firstTaskHandle),
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
}
