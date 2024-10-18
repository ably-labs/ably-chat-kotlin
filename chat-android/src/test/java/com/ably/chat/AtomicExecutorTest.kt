package com.ably.chat

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AtomicExecutorTest {

    @Test
    fun `should perform given operation`() = runTest {
        val singleThreadedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val atomicExecutor = AtomicExecutor(CoroutineScope(singleThreadedDispatcher))
        val deferred = atomicExecutor.execute {
            delay(5000)
            return@execute "Hello"
        }
        val result = deferred.receive()
        assert(result.isSuccess)
        assertEquals("Hello", result.getOrNull())
    }
}
