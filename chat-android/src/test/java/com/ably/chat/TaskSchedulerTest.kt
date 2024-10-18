package com.ably.chat

import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import java.util.concurrent.Executors
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
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
        Assert.assertTrue(result.isSuccess)
        Assert.assertFalse(result.isFailure)
        Assert.assertEquals("Operation Success!", result.getOrNull())
    }

    @Test
    fun `should capture failure of the given operation`() = runTest {
        val singleThreadedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val taskScheduler = TaskScheduler(CoroutineScope(singleThreadedDispatcher))
        val taskResult = taskScheduler.schedule {
            delay(2000)
            throw AblyException.fromErrorInfo(ErrorInfo("Error performing operation", 400))
        }
        val result = taskResult.await()
        Assert.assertFalse(result.isSuccess)
        Assert.assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as AblyException
        Assert.assertEquals("Error performing operation", exception.errorInfo.message)
    }

    @Test
    fun `should perform mutually exclusive operations with given priority`() = runTest {
        val singleThreadedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val taskScheduler = TaskScheduler(CoroutineScope(singleThreadedDispatcher))
        val taskResults = mutableListOf<TaskResult<Int>>()
        var operationInProgress = false
        var counter = 0
        val threadIds = mutableSetOf<Long>()

        repeat(20) {
            val result = taskScheduler.schedule(it) {
                threadIds.add(Thread.currentThread().id)
                if (operationInProgress) {
                    throw IllegalStateException("Can't perform operation when other operation is going on")
                }
                operationInProgress = true
                delay((200..800).random().toDuration(DurationUnit.MILLISECONDS))
                operationInProgress = false
                return@schedule counter++
            }
            taskResults.add(result)
        }

        val results = taskResults.map { it.await() }
        repeat(20) {
            Assert.assertTrue(results[it].isSuccess)
            Assert.assertEquals(it, results[it].getOrNull())
        }

        // Scheduler should run all async operations under single thread
        Assert.assertEquals(1, threadIds.size);
    }
}
