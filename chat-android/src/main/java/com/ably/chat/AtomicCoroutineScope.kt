package com.ably.chat

import io.ably.annotation.Experimental
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * AtomicCoroutineScope makes sure all operations are atomic and run with given priority.
 * Accepts scope as an optional parameter to run atomic operations under the given scope.
 * See [Kotlin Dispatchers](https://kt.academy/article/cc-dispatchers) for more information.
 */
class AtomicCoroutineScope(private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {

    private val sequentialScope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    private class Job private constructor(
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

        companion object {
            var counter: AtomicInteger = AtomicInteger()
            fun create(priority: Int, coroutineBlock: suspend CoroutineScope.() -> Any, deferredResult: CompletableDeferred<Any>): Job {
                return Job(priority, coroutineBlock, deferredResult, counter.getAndIncrement())
            }
        }
    }

    private val jobs: PriorityBlockingQueue<Job> = PriorityBlockingQueue()
    private var isRunning = false

    /**
     * @param priority Defines priority for the operation execution.
     * @param coroutineBlock Suspended function that needs to be executed mutually exclusive under given scope.
     */
    fun <T : Any>async(priority: Int = 0, coroutineBlock: suspend CoroutineScope.() -> T): CompletableDeferred<T> {
        val deferredResult = CompletableDeferred<Any>()
        jobs.add(Job.create(priority, coroutineBlock, deferredResult))
        sequentialScope.launch {
            if (!isRunning) {
                isRunning = true
                while (jobs.isNotEmpty()) {
                    val job = jobs.poll()
                    safeExecute(job)
                }
                isRunning = false
            }
        }

        @Suppress("UNCHECKED_CAST")
        return deferredResult as CompletableDeferred<T>
    }

    private suspend fun safeExecute(job: Job?) {
        job?.let {
            runCatching {
                scope.launch {
                    try {
                        val result = it.coroutineBlock(this)
                        it.deferredResult.complete(result)
                    } catch (t: Throwable) {
                        it.deferredResult.completeExceptionally(t)
                    }
                }.join()
            }
        }
    }

    @Experimental
    val finishedProcessing: Boolean
        get() = jobs.isEmpty() && !isRunning

    /**
     * Clears queuedJobs
     */
    fun cancel(message: String? = "Atomic coroutine scope cancelled", cause: Throwable? = null) {
        jobs.clear()
        Job.counter.set(0)
        // Once sequentialScope.cancel called, AtomicCoroutineScope can't be reused
        // So, once all jobs are executed, it should be garbage collected.
    }
}
