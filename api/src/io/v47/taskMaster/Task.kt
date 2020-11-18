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

import horus.events.EventEmitter

/**
 * A `Task` represents a unit of work that is done using information that
 * is provided and produces a result.
 *
 * The entire process is controlled by the `TaskMaster`. The task should
 * not launch coroutines that run longer than itself.
 */
interface Task<I, O> {
    /**
     * This is where the actual work of the task happens.
     */
    suspend fun run(input: I): O

    /**
     * This is called before the task is killed externally to
     * release any external resources and perform clean-up.
     *
     * This function should never fail or throw any exceptions.
     */
    suspend fun kill()
}

/**
 * This represents a task that can be suspended while running to
 * temporarily free up resources without interfering with its
 * execution.
 */
interface SuspendableTask<I, O> : Task<I, O> {
    /**
     * Suspends the task. The result indicates whether the task suspended
     * its operations. This does not indicate whether suspension failed.
     */
    suspend fun suspend(): Boolean

    /**
     * Resumes the task. The result indicates whether the task resumed
     * its operations. This does not indicate whether resumption failed.
     */
    suspend fun resume(): Boolean
}

/**
 * This creates the actual `Task` instances that are run by the task master.
 */
interface TaskFactory<T : Task<I, O>, I, O> {
    /**
     * Calculates the cost of the task operation based on the input.
     *
     * This is used to determine whether the task will be run right now
     * or queued for future execution.
     *
     * The result must be greater than 0.
     */
    fun calculateCost(input: I): Double

    /**
     * Creates a new instance of the `Task`
     */
    fun create(): T
}

/**
 * An alias over a suspending function that returns a boolean.
 *
 * This decides whether a task is run.
 */
typealias RunCondition = suspend () -> Boolean

/**
 * This interface provides a complete overview of the task,
 * its input, and eventually its result or error it ran into.
 *
 * To influence a tasks execution refer to the functions of
 * `TaskMaster` that take a task handle as an argument.
 */
interface TaskHandle<I, O> : EventEmitter {
    /**
     * The id that uniquely identifies the task in `the system`
     */
    val id: String

    /**
     * The class of the task instance
     */
    val type: Class<Task<I, O>>

    /**
     * The input that was provided to the task
     */
    val input: I

    /**
     * The priority with which the task was started
     */
    val priority: Int

    /**
     * THe condition that determines whether the task can run
     */
    val runCondition: RunCondition?

    /**
     * The output of the task. This is only accessible if the task state is `Complete`.
     *
     * @throws IllegalStateException if the task is not complete
     */
    val output: O

    /**
     * The error that occurred during the execution of the task. This is only accessible
     * if the task state is `Failed`
     *
     * @throws IllegalStateException if the task isn't failed
     */
    val error: Throwable

    /**
     * The current state of the task
     */
    val state: TaskState

    /**
     * Indicates whether the task execution can be temporarily suspended
     */
    val suspendable: Boolean
}

/**
 * Contains pre-defined priority values for task queueing and execution.
 *
 * Tasks with a priority below `HIGH` can only use part of the budget that
 * isn't reserved, i.e. tasks will not be executed even if there is still
 * reserved budget available when the remaining budget is depleted.
 */
object TaskPriority {
    const val LOW = 10
    const val NORMAL = 100
    const val HIGH = 1000
}

enum class TaskState {
    Waiting,
    Running,
    Suspended,
    Complete,
    Failed,
    Killed
}
