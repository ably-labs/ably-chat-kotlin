package com.ably.chat

import io.ably.lib.types.AblyException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.MatcherAssert.assertThat
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
        assertWaiter { !atomicCoroutineScope.finishedProcessing }
        val result = deferredResult.await()
        assertWaiter { atomicCoroutineScope.finishedProcessing }
        Assert.assertEquals("Operation Success!", result)
    }

    @Test
    fun `should capture failure of the given operation and continue performing other operation`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val deferredResult1 = atomicCoroutineScope.async {
            delay(2000)
            throw clientError("Error performing operation")
        }
        val deferredResult2 = atomicCoroutineScope.async {
            delay(2000)
            return@async "Operation Success!"
        }
        assertWaiter { !atomicCoroutineScope.finishedProcessing }

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
                val returnValue = counter++
                operationInProgress = false
                return@async returnValue
            }
            deferredResults.add(result)
        }
        assertWaiter { !atomicCoroutineScope.finishedProcessing }

        val results = deferredResults.awaitAll()
        assertWaiter { atomicCoroutineScope.finishedProcessing }

        repeat(10) {
            Assert.assertEquals(it, results[it])
        }
    }

    @Test
    fun `Concurrently perform mutually exclusive operations`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val deferredResults = LinkedBlockingQueue<CompletableDeferred<Unit>>()

        var operationInProgress = false
        var counter = 0
        val countedValues = mutableListOf<Int>()

        // Concurrently schedule 100000 jobs from multiple threads
        withContext(Dispatchers.IO) {
            repeat(1_00_000) {
                launch {
                    val result = atomicCoroutineScope.async {
                        if (operationInProgress) {
                            error("Can't perform operation when other operation is going on")
                        }
                        operationInProgress = true
                        countedValues.add(counter++)
                        operationInProgress = false
                    }
                    deferredResults.add(result)
                }
            }
        }

        assertWaiter { deferredResults.size == 1_00_000 }

        deferredResults.awaitAll()
        assertWaiter { atomicCoroutineScope.finishedProcessing }
        Assert.assertEquals((0..99_999).toList(), countedValues)
    }

    @Test
    fun `should perform mutually exclusive operations with custom room scope`() = runTest {
        val roomScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + CoroutineName("roomId"))
        val atomicCoroutineScope = AtomicCoroutineScope(roomScope)
        val deferredResults = mutableListOf<Deferred<Int>>()

        val contexts = mutableListOf<String>()
        val contextNames = mutableListOf<String>()

        var operationInProgress = false
        var counter = 0

        repeat(10) {
            val result = atomicCoroutineScope.async {
                if (operationInProgress) {
                    error("Can't perform operation when other operation is going on")
                }
                operationInProgress = true
                contexts.add(coroutineContext.toString())
                contextNames.add(coroutineContext[CoroutineName]!!.name)

                delay((200..800).random().toDuration(DurationUnit.MILLISECONDS))
                val returnValue = counter++
                operationInProgress = false
                return@async returnValue
            }
            deferredResults.add(result)
        }
        assertWaiter { !atomicCoroutineScope.finishedProcessing }

        val results = deferredResults.awaitAll()
        repeat(10) {
            Assert.assertEquals(it, results[it])
            Assert.assertEquals("roomId", contextNames[it])
            assertThat(contexts[it], containsString("Dispatchers.Default.limitedParallelism(1)"))
        }
        assertWaiter { atomicCoroutineScope.finishedProcessing }
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
                if (operationInProgress) {
                    error("Can't perform operation when other operation is going on")
                }
                operationInProgress = true
                contexts.add(this.coroutineContext.toString())
                delay((200..800).random().toDuration(DurationUnit.MILLISECONDS))
                val returnValue = counter++
                operationInProgress = false
                return@async returnValue
            }
            deferredResults.add(result)
        }

        val results = deferredResults.awaitAll()
        val expectedResults = listOf(99, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
        repeat(10) {
            Assert.assertEquals(expectedResults[it], results[it])
            assertThat(contexts[it], containsString("Dispatchers.Default"))
        }
        assertWaiter { atomicCoroutineScope.finishedProcessing }
    }

    @Test
    fun `Concurrently execute mutually exclusive operations with given priority`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val deferredResults = LinkedBlockingQueue<Deferred<Unit>>()

        var operationInProgress = false
        val processedValues = mutableListOf<Int>()

//         This will start first internal operation
        deferredResults.add(
            atomicCoroutineScope.async {
                delay(1000)
                processedValues.add(1000)
                return@async
            },
        )

        // Add more jobs, will be processed based on priority
        // Concurrently schedule 1000 jobs with incremental priority from multiple threads
        withContext(Dispatchers.IO) {
            repeat(1000) {
                launch {
                    val result = atomicCoroutineScope.async(1000 - it) {
                        if (operationInProgress) {
                            error("Can't perform operation when other operation is going on")
                        }
                        operationInProgress = true
                        processedValues.add(it)
                        operationInProgress = false
                    }
                    deferredResults.add(result)
                }
            }
        }

        deferredResults.awaitAll()
        val expectedResults = (1000 downTo 0).toList()
        repeat(1001) {
            Assert.assertEquals(expectedResults[it], processedValues[it])
        }
        assertWaiter { atomicCoroutineScope.finishedProcessing }
    }

    @Test
    fun `should cancel current+pending operations once scope is cancelled and continue performing new operations`() = runTest {
        val atomicCoroutineScope = AtomicCoroutineScope()
        val results = mutableListOf<CompletableDeferred<Unit>>()
        repeat(10) {
            results.add(
                atomicCoroutineScope.async {
                    delay(10_000)
                },
            )
        }
        assertWaiter { !atomicCoroutineScope.finishedProcessing }
        Assert.assertEquals(9, atomicCoroutineScope.pendingJobCount)

        // Cancelling scope should cancel current job and other queued jobs
        atomicCoroutineScope.cancel("scope cancelled externally")
        assertWaiter { atomicCoroutineScope.finishedProcessing }
        Assert.assertEquals(0, atomicCoroutineScope.pendingJobCount)

        for (result in results) {
            val result1 = kotlin.runCatching { result.await() }
            Assert.assertTrue(result1.isFailure)
            Assert.assertEquals("scope cancelled externally", result1.exceptionOrNull()!!.message)
        }

        // Should process new job
        val deferredResult3 = atomicCoroutineScope.async {
            delay(200)
            return@async "Operation Success!"
        }
        assertWaiter { !atomicCoroutineScope.finishedProcessing }

        val result3 = deferredResult3.await()
        assertWaiter { atomicCoroutineScope.finishedProcessing }
        Assert.assertEquals("Operation Success!", result3)
    }
}
