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

import io.v47.events.DefaultEventEmitter
import io.v47.events.EventEmitter
import io.v47.events.EventKey
import io.v47.taskMaster.Task
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

private val taskHandleEventKeys: List<EventKey<*>> = listOf(
    TaskHandleEvent.StateChanged,
    TaskHandleEvent.Completed,
    TaskHandleEvent.Failed,
    TaskHandleEvent.Killed
)

internal open class TaskHandleEventEmitter<I, O> : DefaultEventEmitter() {
    protected var currentTask: Task<I, O>? = null
        set(value) {
            if (field != null)
                removeTaskListeners()

            field = value

            if (field != null)
                addTaskListeners()
        }

    private val nonTaskHandleListeners =
        ConcurrentHashMap<EventKey<*>, ConcurrentLinkedQueue<suspend (Any) -> Unit>>()

    private val nonTaskHandleListenersOnce =
        ConcurrentHashMap<EventKey<*>, ConcurrentLinkedQueue<suspend (Any) -> Unit>>()

    private val nonTaskHandleListenerProxiesOnce =
        ConcurrentHashMap<suspend (Any) -> Unit, suspend (Any) -> Unit>()

    @Suppress("NestedBlockDepth")
    private fun addTaskListeners() {
        currentTask?.let { task ->
            if (task is EventEmitter) {
                nonTaskHandleListeners.forEach { (key, listeners) ->
                    listeners.forEach { task.on(key, it) }
                }

                nonTaskHandleListenersOnce.forEach { (key, listeners) ->
                    listeners.forEach { task.once(key, it) }
                }
            }
        }
    }

    private fun removeTaskListeners() {
        currentTask?.let { task ->
            if (task is EventEmitter) {
                nonTaskHandleListeners.flatMap { it.value }.forEach { task.remove(it) }
                nonTaskHandleListenersOnce.flatMap { it.value }.forEach { task.remove(it) }
            }
        }
    }

    override fun <T : Any> on(key: EventKey<T>, block: suspend (T) -> Unit) {
        if (key in taskHandleEventKeys)
            super.on(key, block)
        else {
            currentTask?.let {
                if (it is EventEmitter)
                    it.on(key, block)
            }

            @Suppress("UNCHECKED_CAST")
            nonTaskHandleListeners
                .computeIfAbsent(key) { ConcurrentLinkedQueue() }
                .add(block as (suspend (Any) -> Unit))
        }
    }

    override fun <T : Any> once(key: EventKey<T>, block: suspend (T) -> Unit) {
        if (key in taskHandleEventKeys)
            super.once(key, block)
        else {
            val proxyBlock: suspend (T) -> Unit = { event ->
                nonTaskHandleListenersOnce[key]?.remove(nonTaskHandleListenerProxiesOnce.remove(block))
                block(event)
            }

            @Suppress("UNCHECKED_CAST")
            nonTaskHandleListenerProxiesOnce[block as suspend (Any) -> Unit] = proxyBlock as suspend (Any) -> Unit

            currentTask?.let {
                if (it is EventEmitter)
                    it.once(key, proxyBlock)
            }

            @Suppress("UNCHECKED_CAST")
            nonTaskHandleListenersOnce
                .computeIfAbsent(key) { ConcurrentLinkedQueue() }
                .add(proxyBlock)
        }
    }

    override fun clear(key: EventKey<*>?) {
        // Don't call super.clear(key) because that would remove
        // the listeners added by TaskMasterImpl and effectively
        // detach the handle, creating a memory leak if it's not
        // removed on completion or failure of the task. This
        // would also remove the ability to trigger the execution
        // of more tasks afterwards so it's generally a bad idea
        // to clear all events here.
        //
        // TaskHandleImpl takes care to remove its own listeners
        // when they are no longer needed.

        currentTask?.let {
            if (it is EventEmitter)
                it.clear(key)
        }

        if (key != null) {
            nonTaskHandleListeners.remove(key)
            nonTaskHandleListenersOnce.remove(key)?.let {
                nonTaskHandleListenerProxiesOnce.entries.removeIf { (_, value) -> value in it }
            }
        } else {
            nonTaskHandleListeners.clear()
            nonTaskHandleListenersOnce.clear()
            nonTaskHandleListenerProxiesOnce.clear()
        }
    }

    override fun <T : Any> remove(listener: suspend (T) -> Unit) {
        super.remove(listener)

        val proxyHandler = nonTaskHandleListenerProxiesOnce.remove(listener)

        currentTask?.let {
            if (it is EventEmitter) {
                it.remove(listener)

                if (proxyHandler != null)
                    it.remove(proxyHandler)
            }
        }

        nonTaskHandleListeners.entries.removeIf { (_, listeners) ->
            listeners.remove(listener)
            listeners.isEmpty()
        }

        if (proxyHandler != null) {
            nonTaskHandleListenersOnce.entries.removeIf { (_, listeners) ->
                listeners.remove(proxyHandler)
                listeners.isEmpty()
            }
        }
    }
}
