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
package io.v47.taskMaster.events

import io.v47.taskMaster.TaskHandle

sealed class TaskMasterEvent {
    abstract val taskHandle: TaskHandle<*, *>

    data class TaskAdded(override val taskHandle: TaskHandle<*, *>) : TaskMasterEvent()

    data class TaskRunning(override val taskHandle: TaskHandle<*, *>) : TaskMasterEvent()

    data class TaskSuspended(override val taskHandle: TaskHandle<*, *>) : TaskMasterEvent()

    data class TaskCompleted(override val taskHandle: TaskHandle<*, *>) : TaskMasterEvent()

    data class TaskFailed(override val taskHandle: TaskHandle<*, *>) : TaskMasterEvent()
}
