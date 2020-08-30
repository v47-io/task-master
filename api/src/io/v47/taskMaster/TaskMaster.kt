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
import io.v47.taskMaster.spi.TaskMasterProvider
import kotlinx.coroutines.Dispatchers
import java.util.*
import kotlin.coroutines.CoroutineContext

interface TaskMaster : EventEmitter {
    companion object {
        private val providers = ServiceLoader.load(TaskMasterProvider::class.java)

        init {
            providers.reload()
        }

        operator fun invoke(
            maxConcurrentTasks: Int = 3,
            maxSuspendedTasks: Int = 10,
            coroutineContext: CoroutineContext = Dispatchers.Default
        ): TaskMaster {
            val provider = providers.findFirst().orElse(null)
                ?: throw ServiceConfigurationError("No TaskMasterProvider implementation found on classpath!")

            return provider.create(
                maxConcurrentTasks,
                maxSuspendedTasks,
                coroutineContext
            )
        }
    }

    suspend fun <T : Task<I, O>, I, O> add(
        factory: TaskFactory<T, I, O>,
        input: I,
        priority: Int = TaskPriority.NORMAL,
        runCondition: (suspend () -> Boolean)? = null
    ): TaskHandle<I, O>

    suspend fun <I, O> resume(taskHandle: TaskHandle<I, O>)

    suspend fun <I, O> kill(taskHandle: TaskHandle<I, O>)
}
