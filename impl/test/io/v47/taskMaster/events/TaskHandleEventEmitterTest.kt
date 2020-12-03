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
