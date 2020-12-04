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
