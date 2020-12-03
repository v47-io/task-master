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
package testsupport.mocks

import io.v47.taskMaster.SuspendableTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicBoolean

class MockSuspendableTask(private val input: MockTaskInput) : SuspendableTask<MockTaskInput, Unit> {
    private var suspended = AtomicBoolean(false)

    override suspend fun run() {
        var msRun = 0L
        while (true) {
            if (suspended.get())
                yield()
            else if (msRun < input.duration / 10) {
                if (input.failWhileRunning)
                    throw IllegalArgumentException("This is a random failure")

                delay(1)
                msRun++
            } else
                break
        }
    }

    override suspend fun suspend() =
        if (input.setSuspended) {
            if (input.failToSuspend)
                throw IllegalArgumentException("Failed to suspend")

            suspended.set(true)
            true
        } else
            false

    override suspend fun resume() =
        if (input.setSuspended) {
            if (input.failToResume)
                throw IllegalArgumentException("Failed to resume")

            suspended.set(false)
            true
        } else
            false

    override suspend fun clean() {
        if (input.failDuringCleanUp)
            throw IllegalArgumentException("Failed to clean")
    }
}
