package com.ably.chat

import java.util.concurrent.PriorityBlockingQueue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * AtomicCoroutineScope is a thread safe wrapper to run multiple operations mutually exclusive.
 * All operations are atomic and run with given priority.
 * Accepts scope as a constructor parameter to run operations under the given scope.
 * See [Kotlin Dispatchers](https://kt.academy/article/cc-dispatchers) for more information.
 */
internal class AtomicCoroutineScope(private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {

    private val sequentialScope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    private class Job<T : Any>(
        private val priority: Int,
        val coroutineBlock: suspend CoroutineScope.() -> T,
        val deferredResult: CompletableDeferred<T>,
        val queuedPriority: Int,
    ) : Comparable<Job<*>> {
        override fun compareTo(other: Job<*>) = when {
            this.priority == other.priority -> this.queuedPriority.compareTo(other.queuedPriority)
            else -> this.priority.compareTo(other.priority)
        }
    }

    // Handles jobs of any type
    private val jobs: PriorityBlockingQueue<Job<*>> = PriorityBlockingQueue() // Accessed from both sequentialScope and async method
    private var isRunning = false // Only accessed from sequentialScope
    private var queueCounter = 0 // Only accessed from synchronized method

    val finishedProcessing: Boolean
        get() = jobs.isEmpty() && !isRunning

    val pendingJobCount: Int
        get() = jobs.size

    /**
     * Defines priority for the operation execution and
     * executes given coroutineBlock mutually exclusive under given scope.
     */
    @Synchronized
    fun <T : Any> async(priority: Int = 0, coroutineBlock: suspend CoroutineScope.() -> T): CompletableDeferred<T> {
        val deferredResult = CompletableDeferred<T>()
        jobs.add(Job(priority, coroutineBlock, deferredResult, queueCounter++))
        sequentialScope.launch {
            if (!isRunning) {
                isRunning = true
                while (jobs.isNotEmpty()) {
                    val job = jobs.poll()
                    job?.let {
                        safeExecute(it)
                    }
                }
                isRunning = false
            }
        }
        return deferredResult
    }

    private suspend fun <T : Any> safeExecute(job: Job<T>) {
        try {
            // Appends coroutineContext to cancel current/pending jobs when AtomicCoroutineScope is cancelled
            scope.launch(coroutineContext) {
                try {
                    val result = job.coroutineBlock(this)
                    job.deferredResult.complete(result)
                } catch (t: Throwable) {
                    job.deferredResult.completeExceptionally(t)
                }
            }.join()
        } catch (t: Throwable) {
            job.deferredResult.completeExceptionally(t)
        }
    }

    /**
     * Cancels ongoing and pending operations with given error.
     * See [Coroutine cancellation](https://kt.academy/article/cc-cancellation#cancellation-in-a-coroutine-scope) for more information.
     */
    @Synchronized
    fun cancel(message: String?, cause: Throwable? = null) {
        queueCounter = 0
        sequentialScope.coroutineContext.cancelChildren(CancellationException(message, cause))
    }
}
