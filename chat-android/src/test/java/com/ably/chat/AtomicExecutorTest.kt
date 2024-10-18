package com.ably.chat

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

class AtomicExecutorTest {

    @Test
    fun `should perform given operation`() = runTest {
        val singleThreadedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val atomicExecutor = AtomicExecutor(CoroutineScope(singleThreadedDispatcher))
        val taskResult = atomicExecutor.execute {
            delay(3000)
            return@execute "Operation Success!"
        }
        val result = taskResult.await()
        Assert.assertTrue(result.isSuccess)
        Assert.assertFalse(result.isFailure)
        Assert.assertEquals("Operation Success!", result.getOrNull())
    }
}
