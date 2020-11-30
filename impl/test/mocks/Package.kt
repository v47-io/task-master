package mocks

import io.v47.taskMaster.TaskHandleImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

val mockFactory = MockTaskFactory()
val mockCoroutineScope = object : CoroutineScope {
    override val coroutineContext = Executors.newFixedThreadPool(3).asCoroutineDispatcher() + SupervisorJob()
}

internal fun createMockTaskHandle(input: MockTaskInput): TaskHandleImpl<MockTaskInput, Unit> {
    val handle = TaskHandleImpl(
        mockFactory,
        mockCoroutineScope,
        input,
        0,
        input.runCondition,
        input.cost
    )

    println("Testing with handle $handle")

    return handle
}
