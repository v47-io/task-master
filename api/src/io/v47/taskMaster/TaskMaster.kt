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
import io.v47.taskMaster.spi.TaskMasterProvider
import kotlinx.coroutines.Dispatchers
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.coroutines.CoroutineContext

interface TaskMaster : EventEmitter {
    companion object {
        private val providers = ServiceLoader.load(TaskMasterProvider::class.java)

        /**
         * Creates a new `TaskMaster` instance using the provided implementation
         *
         * @param[configuration] The configuration for the task master
         * @param[coroutineContext] The coroutine context to use when running tasks
         *
         * @see Configuration.Builder.coroutineContext
         */
        operator fun invoke(
            configuration: Configuration,
            coroutineContext: CoroutineContext = Dispatchers.Default
        ): TaskMaster {
            val (totalBudget, maximumDebt, _, _) = configuration

            require(totalBudget > 0) { "No budget set. No tasks will run" }
            require(maximumDebt == null || maximumDebt >= 0) { "Maximum debt must be null or not negative" }

            val provider = providers.findFirst().orElse(null)
                ?: throw ServiceConfigurationError("No TaskMasterProvider implementation found on classpath!")

            val log: Logger = LoggerFactory.getLogger(TaskMaster::class.java)

            log.info("Creating TaskMaster using provider '{}'", provider::class.qualifiedName)

            if (maximumDebt == 0.0)
                log.warn("Maximum debt is 0. Tasks will always be killed instead of being suspended if possible")

            if (maximumDebt == null)
                log.warn("No maximum debt configured. Too many suspended tasks could lead to resource exhaustion")

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
     * @param[factory] The task factory that will create the actual task that will be executed
     * @param[input] The input for the task
     * @param[priority] The priority of the task.
     *                  This is reset on repeated calls to this function
     * @param[runCondition] The condition that decides whether the task will run.
     *                      This is reset on repeated calls to this function
     *
     * @return A task handle for the new task or an existing one
     */
    suspend fun <I, O> add(
        factory: TaskFactory<I, O>,
        input: I,
        priority: Int = TaskPriority.NORMAL,
        runCondition: RunCondition? = null
    ): TaskHandle<I, O>

    suspend fun <I, O> suspend(
        taskHandle: TaskHandle<I, O>,
        force: Boolean = false,
        consumeFreedBudget: Boolean = true
    ): Boolean

    suspend fun <I, O> resume(taskHandle: TaskHandle<I, O>, force: Boolean = false): Boolean

    suspend fun <I, O> kill(taskHandle: TaskHandle<I, O>, remove: Boolean = false)
}

data class Configuration(
    /**
     * The maximum cost of tasks that can be executed concurrently
     */
    val totalBudget: Double,
    /**
     * The maximum cost of tasks that can be suspended at the
     * same time.
     *
     * Tasks will not be suspended if this is `null`
     */
    val maximumDebt: Double? = null,
    /**
     * Indicates whether tasks should be rescheduled for immediate
     * execution if added repeatedly
     */
    val rescheduleOnAdd: Boolean = true,
    /**
     * Indicates whether running tasks should be killed if budget
     * is required to schedule new tasks
     */
    val killRunningTasks: Boolean = true,
    /**
     * Indicates whether suspended tasks should be killed to decrease
     * the current debt when trying to suspend other tasks.
     *
     * This has no effect if no [maximumDebt] is configured
     */
    val killSuspended: Boolean = true,
    /**
     * Indicates whether to kill a task if its suspension failed.
     *
     * This has no effect if no [maximumDebt] is configured
     */
    val killIfSuspendFails: Boolean = false,
    /**
     * Indicates whether to kill a task if its resumption failed.
     *
     * This has no effect if no [maximumDebt] is configured
     */
    val killIfResumeFails: Boolean = true,
    /**
     * Indicates whether to reschedule previously killed tasks when
     * budget becomes available.
     *
     * Tasks are immediately discarded if this is `false`
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
         * Tasks will not be suspended if this is `null`
         */
        var maximumDebt: Double?

        /**
         * Indicates whether tasks should be rescheduled for immediate
         * execution if added repeatedly
         */
        var rescheduleOnAdd: Boolean

        /**
         * Indicates whether running tasks should be killed if budget
         * is required to schedule new tasks
         */
        var killRunningTasks: Boolean

        /**
         * Indicates whether suspended tasks should be killed to decrease
         * the current debt when trying to suspend other tasks.
         *
         * This has no effect if no [maximumDebt] is configured
         */
        var killSuspended: Boolean

        /**
         * Indicates whether to kill a task if its suspension failed.
         *
         * This has no effect if no [maximumDebt] is configured
         */
        var killIfSuspendFails: Boolean

        /**
         * Indicates whether to kill a task if its resumption failed.
         *
         * This has no effect if no [maximumDebt] is configured
         */
        var killIfResumeFails: Boolean

        /**
         * Indicates whether to reschedule previously killed tasks when
         * budget becomes available.
         *
         * Tasks are immediately discarded when killed if this is `false`
         */
        var rescheduleKilledTasks: Boolean

        /**
         * The coroutine context to use when running tasks.
         *
         * If the context doesn't contain a [kotlinx.coroutines.SupervisorJob] a
         * new context based on this one may be created
         */
        var coroutineContext: CoroutineContext
    }
}

private class ConfigurationBuilderImpl : Configuration.Builder {
    private var _totalBudget: Double? = null
    override var totalBudget: Double
        get() = _totalBudget ?: throw IllegalArgumentException("The total budget must be configured")
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
            rescheduleKilledTasks
        )
}
