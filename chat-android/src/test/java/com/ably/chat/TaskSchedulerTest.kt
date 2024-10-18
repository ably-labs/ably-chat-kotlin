package com.ably.chat

import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import java.util.concurrent.Executors
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

class TaskSchedulerTest {

    @Test
    fun `should perform given operation`() = runTest {
        val singleThreadedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val taskScheduler = TaskScheduler(CoroutineScope(singleThreadedDispatcher))
        val taskResult = taskScheduler.schedule {
            delay(3000)
            return@schedule "Operation Success!"
        }
        val result = taskResult.await()
        Assert.assertEquals("Operation Success!", result)
    }

    @Test
    fun `should capture failure of the given operation`() = runTest {
        val singleThreadedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val taskScheduler = TaskScheduler(CoroutineScope(singleThreadedDispatcher))
        val taskResult = taskScheduler.schedule {
            delay(2000)
            throw AblyException.fromErrorInfo(ErrorInfo("Error performing operation", 400))
        }
        Assert.assertThrows("Error performing operation", AblyException::class.java) {
            runBlocking {
                taskResult.await()
            }
        }
    }

    @Test
    fun `should perform mutually exclusive operations with given priority`() = runTest {
        val singleThreadedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val taskScheduler = TaskScheduler(CoroutineScope(singleThreadedDispatcher))
        val taskResults = mutableListOf<Deferred<Int>>()
        var operationInProgress = false
        var counter = 0
        val threadIds = mutableSetOf<Long>()

        repeat(20) {
            val result = taskScheduler.schedule(it) {
                threadIds.add(Thread.currentThread().id)
                if (operationInProgress) {
                    error("Can't perform operation when other operation is going on")
                }
                operationInProgress = true
                delay((200..800).random().toDuration(DurationUnit.MILLISECONDS))
                operationInProgress = false
                val returnValue = counter++
                return@schedule returnValue
            }
            taskResults.add(result)
        }

        val results = taskResults.awaitAll()
        repeat(20) {
            Assert.assertEquals(it, results[it])
        }
        // Scheduler should run all async operations under single thread
        Assert.assertEquals(1, threadIds.size)
    }
}
