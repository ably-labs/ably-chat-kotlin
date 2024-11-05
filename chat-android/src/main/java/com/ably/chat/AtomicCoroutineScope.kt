package com.ably.chat

import java.util.concurrent.PriorityBlockingQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AtomicCoroutineScope is a thread safe wrapper to run multiple operations mutually exclusive.
 * All operations are atomic and run with given priority.
 * Accepts scope as a constructor parameter to run operations under the given scope.
 * See [Kotlin Dispatchers](https://kt.academy/article/cc-dispatchers) for more information.
 */
class AtomicCoroutineScope(private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {

    private val sequentialScope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    private class Job(
        private val priority: Int,
        val coroutineBlock: suspend CoroutineScope.() -> Any,
        val deferredResult: CompletableDeferred<Any>,
        val queuedPriority: Int,
    ) :
        Comparable<Job> {
        override fun compareTo(other: Job): Int {
            if (this.priority == other.priority) {
                return this.queuedPriority.compareTo(other.queuedPriority)
            }
            return this.priority.compareTo(other.priority)
        }
    }

    private val jobs: PriorityBlockingQueue<Job> = PriorityBlockingQueue() // Accessed from both sequentialScope and async method
    private var isRunning = false // Only accessed from sequentialScope
    private var queueCounter = 0 // Only accessed from synchronized async method

    /**
     * @param priority Defines priority for the operation execution.
     * @param coroutineBlock Suspended function that needs to be executed mutually exclusive under given scope.
     */
    @Synchronized
    fun <T : Any>async(priority: Int = 0, coroutineBlock: suspend CoroutineScope.() -> T): CompletableDeferred<T> {
        val deferredResult = CompletableDeferred<Any>()
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

        @Suppress("UNCHECKED_CAST")
        return deferredResult as CompletableDeferred<T>
    }

    private suspend fun safeExecute(job: Job) {
        runCatching {
            scope.launch {
                try {
                    val result = job.coroutineBlock(this)
                    job.deferredResult.complete(result)
                } catch (t: Throwable) {
                    job.deferredResult.completeExceptionally(t)
                }
            }.join()
        }.onFailure {
            job.deferredResult.completeExceptionally(it)
        }
    }

    val finishedProcessing: Boolean
        get() = jobs.isEmpty() && !isRunning

    val queuedJobs: Int
        get() = jobs.count()
}
