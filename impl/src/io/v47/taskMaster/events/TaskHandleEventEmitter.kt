package io.v47.taskMaster.events

import horus.events.DefaultEventEmitter
import horus.events.EventEmitter
import horus.events.EventKey
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
        super.clear(key)

        currentTask?.let {
            if (it is EventEmitter)
                it.clear(key)
        }

        if (key != null) {
            nonTaskHandleListeners.remove(key)
            nonTaskHandleListenersOnce.remove(key)
        } else {
            nonTaskHandleListeners.clear()
            nonTaskHandleListenersOnce.clear()
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
