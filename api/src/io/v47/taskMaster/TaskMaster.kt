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

        operator fun invoke(
            configuration: Configuration,
            coroutineContext: CoroutineContext = Dispatchers.Default
        ): TaskMaster {
            val (totalBudget, reservedBudget, maximumDebt, _) = configuration

            require(totalBudget > 0) { "No budget set. No tasks will run" }
            require(reservedBudget == null || (reservedBudget <= totalBudget && reservedBudget > -1)) {
                "Reserved budget must be in the range 0..totalBudget"
            }
            require(maximumDebt == null || maximumDebt >= 0) { "Maximum debt must be null or not negative" }

            val provider = providers.findFirst().orElse(null)
                ?: throw ServiceConfigurationError("No TaskMasterProvider implementation found on classpath!")

            val log: Logger = LoggerFactory.getLogger(provider.javaClass)

            log.info("Creating TaskMaster using provider '{}'", provider::class.qualifiedName)

            @Suppress("MagicNumber")
            if ((reservedBudget ?: 0.0) / totalBudget > 0.667)
                log.warn(
                    "Reserved budget is more than two thirds of defined total budget. " +
                            "This may impact task execution performance."
                )

            if (maximumDebt == 0.0)
                log.warn("Maximum debt is 0. Tasks will always be killed instead of being suspended if possible")

            if (maximumDebt == null)
                log.warn("No maximum debt configured. Too many suspended tasks could lead to resource exhaustion")

            return provider.create(
                configuration,
                coroutineContext
            )
        }

        operator fun invoke(configurationBlock: Configuration.Builder.() -> Unit) =
            ConfigurationBuilderImpl().let { builder ->
                builder.configurationBlock()
                TaskMaster(builder.build(), builder.coroutineContext)
            }
    }

    val taskHandles: Set<TaskHandle<*, *>>

    suspend fun <T : Task<I, O>, I, O> add(
        factory: TaskFactory<T, I, O>,
        input: I,
        priority: Int = TaskPriority.NORMAL,
        runCondition: RunCondition? = null
    ): TaskHandle<I, O>

    suspend fun <I, O> suspend(taskHandle: TaskHandle<I, O>): Boolean

    suspend fun <I, O> resume(taskHandle: TaskHandle<I, O>): Boolean

    suspend fun <I, O> kill(taskHandle: TaskHandle<I, O>)

    suspend fun stop(killRunning: Boolean = false)
}

data class Configuration(
    val totalBudget: Double,
    val reservedBudget: Double? = null,
    val maximumDebt: Double? = null,
    val restartKilledTasks: Boolean = false,
) {
    interface Builder {
        var totalBudget: Double
        var reservedBudget: Double?
        var maximumDebt: Double?
        var restartKilledTasks: Boolean
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

    override var reservedBudget: Double? = null
    override var maximumDebt: Double? = null
    override var restartKilledTasks: Boolean = false
    override var coroutineContext: CoroutineContext = Dispatchers.Default

    fun build() =
        Configuration(
            totalBudget,
            reservedBudget,
            maximumDebt,
            restartKilledTasks
        )
}
