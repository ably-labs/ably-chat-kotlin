package com.ably.chat

import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.hamcrest.CoreMatchers.containsString
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
        Assert.assertFalse(atomicCoroutineScope.finishedProcessing)
        val result = deferredResult.await()
        assertWaiter { atomicCoroutineScope.finishedProcessing }
        Assert.assertEquals("Operation Success!", result)
    }

    @Test
    fun `should capture failure of the given operation and continue performing other operation`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val deferredResult1 = atomicCoroutineScope.async {
            delay(2000)
            throw AblyException.fromErrorInfo(ErrorInfo("Error performing operation", 400))
        }
        val deferredResult2 = atomicCoroutineScope.async {
            delay(2000)
            return@async "Operation Success!"
        }
        Assert.assertFalse(atomicCoroutineScope.finishedProcessing)

        val ex = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                deferredResult1.await()
            }
        }
        Assert.assertEquals("Error performing operation", ex.errorInfo.message)

        val result2 = deferredResult2.await()
        assertWaiter { atomicCoroutineScope.finishedProcessing }
        Assert.assertEquals("Operation Success!", result2)
    }

    @Test
    fun `should perform mutually exclusive operations`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val deferredResults = mutableListOf<Deferred<Int>>()
        var operationInProgress = false
        var counter = 0

        repeat(10) {
            val result = atomicCoroutineScope.async {
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
        Assert.assertFalse(atomicCoroutineScope.finishedProcessing)

        val results = deferredResults.awaitAll()
        assertWaiter { atomicCoroutineScope.finishedProcessing }

        repeat(10) {
            Assert.assertEquals(it, results[it])
        }
    }

    @Test
    fun `should perform mutually exclusive operations with custom scope`() = runTest {
        val sequentialScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
        val atomicCoroutineScope = AtomicCoroutineScope(sequentialScope)
        val deferredResults = mutableListOf<Deferred<Int>>()
        val contexts = mutableListOf<String>()
        var operationInProgress = false
        var counter = 0

        repeat(10) {
            val result = atomicCoroutineScope.async {
                contexts.add(this.coroutineContext.toString())
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
        Assert.assertFalse(atomicCoroutineScope.finishedProcessing)

        val results = deferredResults.awaitAll()
        repeat(10) {
            Assert.assertEquals(it, results[it])
            Assert.assertThat(contexts[it], containsString("Dispatchers.Default.limitedParallelism(1)"))
        }
        Assert.assertTrue(atomicCoroutineScope.finishedProcessing)
    }

    @Test
    fun `should perform mutually exclusive operations with given priority`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val deferredResults = mutableListOf<Deferred<Int>>()
        var operationInProgress = false
        var counter = 0
        val contexts = mutableListOf<String>()

        // This will start internal operation
        deferredResults.add(
            atomicCoroutineScope.async {
                delay(1000)
                return@async 99
            },
        )
        delay(100)

        // Add more jobs, will be processed based on priority
        repeat(10) {
            val result = atomicCoroutineScope.async(10 - it) {
                contexts.add(this.coroutineContext.toString())
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
        val expectedResults = listOf(99, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
        repeat(10) {
            Assert.assertEquals(expectedResults[it], results[it])
            Assert.assertThat(contexts[it], containsString("Dispatchers.Default"))
        }
        Assert.assertTrue(atomicCoroutineScope.finishedProcessing)
    }

    @Test
    fun `reuse AtomicCoroutineScope once cancelled`() = runTest {
    }
}
