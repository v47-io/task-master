package io.v47.taskMaster.events

import io.v47.taskMaster.Task
import kotlinx.coroutines.runBlocking
import mocks.MockTask
import mocks.MockTaskEvent
import mocks.MockTaskInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import utils.deferredOnce
import utils.record

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskHandleEventEmitterTest {
    @Test
    fun `it should emit events`() {
        val mockEmitter = MockTaskHandleEventEmitter(createMockTask())
        val events = mockEmitter.record(MockTaskEvent)

        val firstEventOnce = mockEmitter.deferredOnce(MockTaskEvent)

        runBlocking {
            mockEmitter.task?.run()
        }

        assertEquals(
            listOf(
                MockTaskEvent("first event"),
                MockTaskEvent("second event")
            ),
            events
        )

        assertEquals(
            MockTaskEvent("first event"),
            runBlocking { firstEventOnce.await() }
        )
    }

    @Test
    fun `it correctly adds the event listeners`() {
        val mockEmitter = MockTaskHandleEventEmitter()
        val events = mockEmitter.record(MockTaskEvent)
        val firstEventOnce = mockEmitter.deferredOnce(MockTaskEvent)

        mockEmitter.task = createMockTask()

        runBlocking {
            mockEmitter.task?.run()
        }

        assertEquals(
            listOf(
                MockTaskEvent("first event"),
                MockTaskEvent("second event")
            ),
            events
        )

        assertEquals(
            MockTaskEvent("first event"),
            runBlocking { firstEventOnce.await() }
        )
    }

    @Test
    fun `it correctly handles changing task instances`() {
        val mockEmitter = MockTaskHandleEventEmitter(createMockTask())
        val events = mockEmitter.record(MockTaskEvent)

        runBlocking {
            mockEmitter.task?.run()
        }

        val firstEventOnce = mockEmitter.deferredOnce(MockTaskEvent)

        mockEmitter.task = createMockTask()

        runBlocking {
            mockEmitter.task?.run()
        }

        assertEquals(
            listOf(
                MockTaskEvent("first event"),
                MockTaskEvent("second event"),
                MockTaskEvent("first event"),
                MockTaskEvent("second event")
            ),
            events
        )

        assertEquals(
            MockTaskEvent("first event"),
            runBlocking { firstEventOnce.await() }
        )
    }

    @Test
    fun `it clears the event listeners`() {
        val mockEmitter = MockTaskHandleEventEmitter(createMockTask())
        val events = mockEmitter.record(MockTaskEvent)

        runBlocking {
            mockEmitter.task?.run()
        }

        assertEquals(
            listOf(
                MockTaskEvent("first event"),
                MockTaskEvent("second event")
            ),
            events
        )

        mockEmitter.clear(MockTaskEvent)

        runBlocking {
            mockEmitter.task?.run()
        }

        assertEquals(
            listOf(
                MockTaskEvent("first event"),
                MockTaskEvent("second event")
            ),
            events
        )
    }

    @Test
    fun `it removes event listeners`() {
        val mockEmitter = MockTaskHandleEventEmitter(createMockTask())

        var handlerInvokeCount = 0
        val handler: suspend (MockTaskEvent) -> Unit = {
            println("I'm a giraffe!")
            handlerInvokeCount++
        }

        mockEmitter.on(MockTaskEvent, handler)
        mockEmitter.once(MockTaskEvent, handler)

        val events = mockEmitter.record(MockTaskEvent)

        mockEmitter.remove(handler)

        runBlocking {
            mockEmitter.task?.run()
        }

        assertEquals(
            listOf(
                MockTaskEvent("first event"),
                MockTaskEvent("second event")
            ),
            events
        )

        assertEquals(0, handlerInvokeCount)
    }

    private fun createMockTask() =
        MockTask(MockTaskInput())
}

private class MockTaskHandleEventEmitter(
    task: Task<MockTaskInput, Unit>? = null
) : TaskHandleEventEmitter<MockTaskInput, Unit>() {
    var task: Task<MockTaskInput, Unit>?
        set(value) {
            currentTask = value
        }
        get() = currentTask

    init {
        this.task = task
    }
}
