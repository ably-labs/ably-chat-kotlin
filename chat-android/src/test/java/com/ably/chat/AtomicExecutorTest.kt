package com.ably.chat

import java.util.concurrent.Executors
import kotlin.time.DurationUnit
import kotlin.time.toDuration
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

    @Test
    fun `should perform mutually exclusive operations with given priority`() = runTest {
        val singleThreadedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val atomicExecutor = AtomicExecutor(CoroutineScope(singleThreadedDispatcher))
        val taskResults = mutableListOf<TaskResult<Int>>()
        var operationInProgress = false
        var counter = 0

        repeat(20) {
            val result = atomicExecutor.execute(it) {
                if (operationInProgress) {
                    throw IllegalStateException("Can't perform operation when other operation is going on")
                }
                operationInProgress = true
                delay((200..600).random().toDuration(DurationUnit.MILLISECONDS))
                operationInProgress = false
                return@execute counter++
            }
            taskResults.add(result)
        }

        val results = taskResults.map { it.await() }

        repeat(20) {
            Assert.assertTrue(results[it].isSuccess)
            Assert.assertEquals(it, results[it].getOrNull())
        }
    }
}
