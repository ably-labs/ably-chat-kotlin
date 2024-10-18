package com.ably.chat

import java.util.PriorityQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Each ChatRoomLifecycleManager is supposed to have it's own AtomicCoroutineScope.
 * AtomicCoroutineScope makes sure all operations are atomic and run with given priority.
 * Uses single threaded dispatcher to avoid thread synchronization issues.
 */
class AtomicCoroutineScope private constructor(private val scope: CoroutineScope) {

    private class Job(
        private val priority: Int,
        val coroutineBlock: suspend CoroutineScope.() -> Any,
        val deferredResult: CompletableDeferred<Any>,
    ) :
        Comparable<Job> {
        override fun compareTo(other: Job): Int = this.priority.compareTo(other.priority)
    }

    private var isRunning = false
    private val jobs: PriorityQueue<Job> = PriorityQueue()

    /**
     * @param priority Defines priority for the operation execution.
     * @param coroutineBlock Suspended function that needs to be executed mutually exclusive under given scope.
     */
    suspend fun <T : Any>async(priority: Int = 0, coroutineBlock: suspend CoroutineScope.() -> T): CompletableDeferred<T> {
        val deferredResult = CompletableDeferred<Any>()
        scope.launch {
            jobs.add(Job(priority, coroutineBlock, deferredResult))
            if (!isRunning) {
                isRunning = true
                while (jobs.isNotEmpty()) {
                    val job = jobs.poll()
                    job?.let {
                        try {
                            it.deferredResult.complete(scope.async(block = it.coroutineBlock).await())
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
     * Cancels all jobs along along with it's children.
     * This includes cancelling queued jobs and current retry timers.
     */
    fun cancel(message: String, cause: Throwable? = null) {
        jobs.clear()
        scope.cancel(message, cause)
    }

    companion object {
        private var _singleThreadedDispatcher : ExecutorCoroutineDispatcher? = null

        fun create(): AtomicCoroutineScope {
            if (_singleThreadedDispatcher == null) {
                _singleThreadedDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher();
            }
            return AtomicCoroutineScope(CoroutineScope(singleThreadedDispatcher))
        }

        val singleThreadedDispatcher: ExecutorCoroutineDispatcher
            get() {
                return _singleThreadedDispatcher?: error("Call SingleThreadedExecutor.create() method to initialize SingleThreadedDispatcher")
            }
    }
}
