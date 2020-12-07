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

import io.v47.events.EventEmitter
import io.v47.taskMaster.exceptions.ResumeFailedException
import io.v47.taskMaster.exceptions.SuspendFailedException
import io.v47.taskMaster.spi.TaskMasterProvider
import kotlinx.coroutines.Dispatchers
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.coroutines.CoroutineContext

/**
 * `TaskMaster` is an unfair, concurrent work scheduler. It prioritizes newer and
 * high-priority tasks over older, low-priority tasks.
 *
 * Unfair means that no matter which task you kill or suspend, task master will
 * always start the highest-priority and most recent tasks first, regardless how
 * long other tasks have been waiting to be executed. For more information about
 * unfairness see the [add] function.
 *
 * The most important concepts to understand are the budget and task cost. Every
 * task has a cost that is calculated before it is executed. This cost is then
 * compared to the available budget to decide if the task can be run.
 *
 * You define a [Configuration.totalBudget] which is the base budget for all task
 * execution in `TaskMaster`. Optionally the task master can temporarily suspend
 * running tasks to make budget available for other tasks. This is enabled if you
 * configure a [Configuration.maximumDebt] which indicates the maximum sum of cost
 * that suspended tasks can accumulate before being killed.
 *
 * Being killed is not the end of a task though. If so configured to do so the task
 * master will pick up killed tasks and execute them again once more budget becomes
 * available. This is configured with [Configuration.rescheduleKilledTasks].
 *
 * The task master offers a few other [Configuration] properties which allow you to
 * customize its behavior to your needs.
 *
 * In addition to those properties you also configure a [CoroutineContext] which is
 * used to concurrently execute the tasks. You should make at least two threads
 * available to the task master, otherwise it might not be able to function because
 * some management tasks might be done concurrently.
 */
interface TaskMaster : EventEmitter {
    companion object {
        private val providers = ServiceLoader.load(TaskMasterProvider::class.java)

        /**
         * Creates a new `TaskMaster` instance using the provided implementation.
         *
         * @param[configuration] The configuration for the task master
         * @param[coroutineContext] The coroutine context to use when running tasks
         *                          (default: [Dispatchers.Default])
         *
         * @see Configuration.Builder.coroutineContext
         */
        operator fun invoke(
            configuration: Configuration,
            coroutineContext: CoroutineContext = Dispatchers.Default
        ): TaskMaster {
            val (totalBudget, maximumDebt, _, _) = configuration

            require(totalBudget > 0) { "No budget set. No tasks will run" }
            require(maximumDebt == null || maximumDebt > 0) { "Maximum debt must be null or greater than 0" }

            val provider = providers.findFirst().orElse(null)
                ?: throw ServiceConfigurationError("No TaskMasterProvider implementation found on classpath!")

            val log: Logger = LoggerFactory.getLogger(TaskMaster::class.java)

            log.info("Creating TaskMaster using provider '{}'", provider::class.qualifiedName)

            if (maximumDebt == null)
                log.warn("No maximum debt configured. Tasks will be killed instead of being suspended.")

            return provider.create(
                configuration,
                coroutineContext
            )
        }

        /**
         * Creates a new `TaskMaster` instance using the provided implementation.
         *
         * This function configures the task master using the DSL provided by
         * [Configuration.Builder]
         *
         * @param[configurationBlock] The code that configures the task master instance
         */
        operator fun invoke(configurationBlock: Configuration.Builder.() -> Unit) =
            ConfigurationBuilderImpl().let { builder ->
                builder.configurationBlock()
                TaskMaster(builder.build(), builder.coroutineContext)
            }
    }

    /**
     * All currently known task handles for waiting, running, or killed tasks.
     */
    val taskHandles: Set<TaskHandle<*, *>>

    /**
     * Adds a new task to be scheduled if none with the specified input already exists.
     *
     * If there is a task with that input it returns the existing task handle instead.
     *
     * If this function is called repeatedly with the same input, the task master may
     * try to schedule the existing task for immediate execution. Other tasks may be
     * suspended or killed as a consequence.
     *
     * However, task master will always try to respect the task priorities. This means
     * that even with repeated calls to this function the task might not immediately
     * run if there are higher-priority tasks running.
     *
     * If a task absolutely, positively has to run _NOW_, call this function again with
     * the priority set to [Int.MAX_VALUE]. This is however extremely discouraged
     * behavior as it defeats the purpose of the task master.
     *
     * Note that the task master will always respect the [runCondition] if one was specified,
     * no matter how many times this function is called or what the priority is. However,
     * the `runCondition` only affects a start from the beginning, it's not used when
     * the task is resumed from a suspended state.
     *
     * @param[factory] The task factory that will create the actual task that will be executed
     * @param[input] The input for the task
     * @param[priority] The priority of the task.
     *                  This may be reset on repeated calls to this function
     *                  (default: [TaskPriority.NORMAL])
     * @param[runCondition] The condition that decides whether the task will run.
     *                      This may be reset on repeated calls to this function
     *                      (default: `null`)
     *
     * @return A task handle for the new task or an existing one
     */
    suspend fun <I, O> add(
        factory: TaskFactory<I, O>,
        input: I,
        priority: Int = TaskPriority.NORMAL,
        runCondition: RunCondition? = null
    ): TaskHandle<I, O>

    /**
     * Tries to suspend the task identified by the specified handle if resources
     * are available. If not, other suspended tasks may be killed to reduce debt
     * to a point where this task can be suspended.
     *
     * However, task master will always try to respect the task priorities. This
     * means that the task may not be suspended if there is too much debt and no
     * lower-priority suspended tasks could be killed.
     *
     * This may not suspend a task if task suspension is not configured (no
     * `maximumDebt` configured).
     *
     * By default the task master will try to reclaim the freed budget by running
     * or resuming other tasks, starting with the newest tasks at the highest
     * priority.
     *
     * @param[taskHandle] The task handle that identifies the task to suspend
     * @param[force] Indicates whether to try forcing to suspend by killing other
     *               suspended tasks (default: `false`)
     * @param[consumeFreedBudget] Indicates whether the task master should try to
     *                            use the freed budget to run or resume other tasks
     *                            (default: `true`)
     *
     * @return A boolean that indicates whether the task was suspended
     * @throws SuspendFailedException if the suspend operation of the task failed
     *                                with an exception
     */
    suspend fun <I, O> suspend(
        taskHandle: TaskHandle<I, O>,
        force: Boolean = false,
        consumeFreedBudget: Boolean = true
    ): Boolean

    /**
     * Tries to resume the task identified by the specified handle if resources are
     * available. If not, other running tasks may be suspended to reduce the consumed
     * budget to a point where this task can be resumed.
     *
     * However, task master will always try to respect the task priorities. This means
     * that the task may not be resumed if there is not enough budget and not enough
     * running tasks could be suspended or killed.
     *
     * This may not suspend a task if task suspension is not configured (no `maximumDebt`
     * configured) and may kill running tasks to free the required resources.
     *
     * @param[taskHandle] The task handle that identifies the task to resume
     * @param[force] Indicates whether to try forcing to resume by suspending or killing
     *               other running tasks (default: `force`)
     *
     * @return A boolean that indicates whether the task was resumed
     * @throws ResumeFailedException if the resume operation of the task failed with
     *                               an exception
     */
    suspend fun <I, O> resume(taskHandle: TaskHandle<I, O>, force: Boolean = false): Boolean

    /**
     * Kills the task identified by the specified task handle.
     *
     * The task may remain known to the task master and may be rescheduled to run at
     * a later time. If this is the only task known to the task master and it's not
     * removed, it may remain unscheduled until a new task finishes or it's restarted
     * using [add].
     *
     * @param[taskHandle] The task handle that identifies the task to kill
     * @param[remove] Indicates whether to remove the task entirely. This will prevent it
     *                from being re-run at a later time (default: `false`)
     * @param[consumeFreedBudget] Indicates whether the task master should try to use the
     *                            freed budget to run or resume other tasks (default: `true`)
     */
    suspend fun <I, O> kill(
        taskHandle: TaskHandle<I, O>,
        remove: Boolean = false,
        consumeFreedBudget: Boolean = true
    )
}

/**
 * Contains all configuration properties for `TaskMaster` instances.
 *
 * Except for `totalBudget` all properties are optional and are set to
 * sensible default values.
 *
 * @see totalBudget
 * @see maximumDebt
 * @see rescheduleOnAdd
 * @see killRunningTasks
 * @see killSuspended
 * @see killIfSuspendFails
 * @see killIfResumeFails
 * @see rescheduleKilledTasks
 */
data class Configuration(
    /**
     * The maximum cost of tasks that can be executed concurrently.
     *
     * This is the only required configuration property
     */
    val totalBudget: Double,
    /**
     * The maximum cost of tasks that can be suspended at the
     * same time.
     *
     * Tasks will not be suspended if this is `null`.
     *
     * Default: `null`
     */
    val maximumDebt: Double? = null,
    /**
     * Indicates whether tasks should be rescheduled for immediate
     * execution if added repeatedly.
     *
     * Default: `true`
     */
    val rescheduleOnAdd: Boolean = true,
    /**
     * Indicates whether running tasks should be killed if budget
     * is required to schedule new tasks.
     *
     * Default: `true`
     */
    val killRunningTasks: Boolean = true,
    /**
     * Indicates whether suspended tasks should be killed to decrease
     * the current debt when trying to suspend other tasks.
     *
     * This has no effect if no [maximumDebt] is configured.
     *
     * Default: `true`
     */
    val killSuspended: Boolean = true,
    /**
     * Indicates whether to kill a task if its suspension failed.
     *
     * This has no effect if no [maximumDebt] is configured.
     *
     * Default: `false`
     */
    val killIfSuspendFails: Boolean = false,
    /**
     * Indicates whether to kill a task if its resumption failed.
     *
     * This has no effect if no [maximumDebt] is configured.
     *
     * Default: `true`
     */
    val killIfResumeFails: Boolean = true,
    /**
     * Indicates whether to reschedule previously killed tasks when
     * budget becomes available.
     *
     * Tasks are immediately discarded if this is `false`.
     *
     * Default: `false`
     */
    val rescheduleKilledTasks: Boolean = false,
) {
    /**
     * The interface that provides to configuration properties when
     * creating a task master using the DSL style
     */
    interface Builder {
        /**
         * The maximum cost of tasks that can be executed concurrently.
         *
         * This is the only required configuration property
         */
        var totalBudget: Double

        /**
         * The maximum cost of tasks that can be suspended at the
         * same time.
         *
         * Tasks will not be suspended if this is `null`.
         *
         * Default: `null`
         */
        var maximumDebt: Double?

        /**
         * Indicates whether tasks should be rescheduled for immediate
         * execution if added repeatedly.
         *
         * Default: `true`
         */
        var rescheduleOnAdd: Boolean

        /**
         * Indicates whether running tasks should be killed if budget
         * is required to schedule new tasks.
         *
         * Default: `true`
         */
        var killRunningTasks: Boolean

        /**
         * Indicates whether suspended tasks should be killed to decrease
         * the current debt when trying to suspend other tasks.
         *
         * This has no effect if no [maximumDebt] is configured.
         *
         * Default: `true`
         */
        var killSuspended: Boolean

        /**
         * Indicates whether to kill a task if its suspension failed.
         *
         * This has no effect if no [maximumDebt] is configured.
         *
         * Default: `false`
         */
        var killIfSuspendFails: Boolean

        /**
         * Indicates whether to kill a task if its resumption failed.
         *
         * This has no effect if no [maximumDebt] is configured.
         *
         * Default: `true`
         */
        var killIfResumeFails: Boolean

        /**
         * Indicates whether to reschedule previously killed tasks when
         * budget becomes available.
         *
         * Tasks are immediately discarded when killed if this is `false`.
         *
         * Default: `false`
         */
        var rescheduleKilledTasks: Boolean

        /**
         * The coroutine context to use when running tasks.
         *
         * If the context doesn't contain a [kotlinx.coroutines.SupervisorJob] a
         * new context based on this one may be created.
         *
         * By default the [Dispatchers.Default] context is used, but it's
         * recommended that a separate context is specified
         */
        var coroutineContext: CoroutineContext
    }
}

private class ConfigurationBuilderImpl : Configuration.Builder {
    private var _totalBudget: Double? = null
    override var totalBudget: Double
        get() = _totalBudget
            ?: throw IllegalArgumentException("The total budget must be configured")
        set(value) {
            _totalBudget = value
        }

    override var maximumDebt: Double? = null
    override var rescheduleOnAdd: Boolean = true
    override var killRunningTasks: Boolean = true
    override var killSuspended: Boolean = true
    override var killIfSuspendFails: Boolean = false
    override var killIfResumeFails: Boolean = true
    override var rescheduleKilledTasks: Boolean = false
    override var coroutineContext: CoroutineContext = Dispatchers.Default

    fun build() =
        Configuration(
            totalBudget,
            maximumDebt,
            rescheduleOnAdd,
            killRunningTasks,
            killSuspended,
            killIfSuspendFails,
            killIfResumeFails,
            rescheduleKilledTasks
        )
}
