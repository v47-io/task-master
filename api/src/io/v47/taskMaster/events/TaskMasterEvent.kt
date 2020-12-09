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

import io.v47.events.EventKey
import io.v47.taskMaster.TaskHandle
import io.v47.taskMaster.TaskState

/**
 * Provides events that are emitted by [io.v47.taskMaster.TaskMaster] itself.
 */
sealed class TaskMasterEvent {
    abstract val taskHandle: TaskHandle<*, *>

    data class TaskAdded(override val taskHandle: TaskHandle<*, *>) : TaskMasterEvent() {
        companion object : EventKey<TaskAdded>
    }

    data class TaskStateChanged(
        override val taskHandle: TaskHandle<*, *>,
        val state: TaskState,
        val previousState: TaskState
    ) : TaskMasterEvent() {
        companion object : EventKey<TaskStateChanged>
    }

    /**
     * This event signals that there are currently no tasks to be scheduled
     * by the task master.
     */
    object NoMoreTasks : TaskMasterEvent(), EventKey<NoMoreTasks> {
        /**
         * Not available for this event.
         *
         * @throws IllegalStateException if used
         */
        override val taskHandle: TaskHandle<*, *>
            get() = throw IllegalStateException("Cannot retrieve TaskHandle")
    }
}
