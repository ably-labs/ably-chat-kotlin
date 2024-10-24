package com.ably.chat

import java.util.PriorityQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AtomicCoroutineScope makes sure all operations are atomic and run with given priority.
 * Each ChatRoomLifecycleManager is supposed to have it's own AtomicCoroutineScope.
 * Uses limitedParallelism set to 1 to make sure coroutines under given scope do not run in parallel.
 * See [Kotlin Dispatchers](https://kt.academy/article/cc-dispatchers) for more information.
 */
class AtomicCoroutineScope {

    internal val sequentialScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

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

    private var isRunning = false
    private var queueCounter = 0
    private val jobs: PriorityQueue<Job> = PriorityQueue()

    /**
     * @param priority Defines priority for the operation execution.
     * @param coroutineBlock Suspended function that needs to be executed mutually exclusive under given scope.
     */
    suspend fun <T : Any>async(priority: Int = 0, coroutineBlock: suspend CoroutineScope.() -> T): CompletableDeferred<T> {
        val deferredResult = CompletableDeferred<Any>()
        sequentialScope.launch {
            jobs.add(Job(priority, coroutineBlock, deferredResult, queueCounter++))
            if (!isRunning) {
                isRunning = true
                while (jobs.isNotEmpty()) {
                    val job = jobs.poll()
                    job?.let {
                        try {
                            val result = sequentialScope.async(block = it.coroutineBlock).await()
                            it.deferredResult.complete(result)
                        } catch (t: Throwable) {
                            it.deferredResult.completeExceptionally(t)
                        }
                    }
                }
                isRunning = false
            }
        }

        @Suppress("UNCHECKED_CAST")
        return deferredResult as CompletableDeferred<T>
    }

    /**
     * Cancels all jobs along with it's children.
     * This includes cancelling queued jobs and current retry timers.
     */
    fun cancel(message: String, cause: Throwable? = null) {
        jobs.clear()
        sequentialScope.cancel(message, cause)
    }
}
