package com.ably.chat

import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

class AtomicCoroutineScopeTest {

    @Test
    fun `should perform given operation`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val deferredResult = atomicCoroutineScope.async {
            delay(3000)
            return@async "Operation Success!"
        }
        val result = deferredResult.await()
        Assert.assertEquals("Operation Success!", result)
    }

    @Test
    fun `should capture failure of the given operation`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val deferredResult = atomicCoroutineScope.async {
            delay(2000)
            throw AblyException.fromErrorInfo(ErrorInfo("Error performing operation", 400))
        }
        val ex = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                deferredResult.await()
            }
        }
        Assert.assertEquals("Error performing operation", ex.errorInfo.message)
    }

    @Test
    fun `should perform mutually exclusive operations`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val deferredResults = mutableListOf<Deferred<Int>>()
        var operationInProgress = false
        var counter = 0
        val threadIds = mutableSetOf<Long>()

        repeat(10) {
            val result = atomicCoroutineScope.async {
                threadIds.add(Thread.currentThread().id)
                if (operationInProgress) {
                    error("Can't perform operation when other operation is going on")
                }
                operationInProgress = true
                delay((200..800).random().toDuration(DurationUnit.MILLISECONDS))
                operationInProgress = false
                val returnValue = counter++
                return@async returnValue
            }
            deferredResults.add(result)
        }

        val results = deferredResults.awaitAll()
        repeat(10) {
            Assert.assertEquals(it, results[it])
        }
        // Scheduler should run all async operations under single thread
        Assert.assertEquals(1, threadIds.size)
    }

    @Test
    fun `should perform mutually exclusive operations with given priority`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val deferredResults = mutableListOf<Deferred<Int>>()
        var operationInProgress = false
        var counter = 0
        val threadIds = mutableSetOf<Long>()

        repeat(10) {
            val result = atomicCoroutineScope.async(10 - it) {
                threadIds.add(Thread.currentThread().id)
                if (operationInProgress) {
                    error("Can't perform operation when other operation is going on")
                }
                operationInProgress = true
                delay((200..800).random().toDuration(DurationUnit.MILLISECONDS))
                operationInProgress = false
                val returnValue = counter++
                return@async returnValue
            }
            deferredResults.add(result)
        }

        val results = deferredResults.awaitAll()
        val expectedResults = listOf(0, 9, 8, 7, 6, 5, 4, 3, 2, 1)
        repeat(10) {
            Assert.assertEquals(expectedResults[it], results[it])
        }
        // Scheduler should run all async operations under single thread
        Assert.assertEquals(1, threadIds.size)
    }
}
