/**
 * Copyright 2020 The library authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.v47.taskMaster

import horus.events.EventEmitter

interface Task<I, O> {
    suspend fun run(input: I): O
}

interface SuspendableTask<I, O> : Task<I, O> {
    suspend fun suspend(): Boolean
    suspend fun resume(): Boolean
}

interface TaskFactory<T : Task<I, O>, I, O> {
    fun create(): T
}

interface TaskHandle<I, O> : EventEmitter {
    val id: String
    val type: Class<Task<I, O>>

    val input: I
    val output: O
    val error: Throwable

    val state: TaskState
    val suspendable: Boolean
}

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
    Failed
}
