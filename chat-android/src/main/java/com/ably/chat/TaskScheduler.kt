package com.ably.chat

import java.util.PriorityQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private class Task(
    private val priority: Int,
    val coroutineBlock: suspend CoroutineScope.() -> Any,
    val deferredResult: CompletableDeferred<Any>,
) :
    Comparable<Task> {
    override fun compareTo(other: Task): Int = this.priority.compareTo(other.priority)
}

/**
 * TaskScheduler schedules given coroutine operation mutually exclusive.
 * @property scope Uses single threaded dispatcher to avoid thread synchronization issues.
 */
class TaskScheduler(private val scope: CoroutineScope) {
    private var isRunning = false
    private val tasks: PriorityQueue<Task> = PriorityQueue()

    /**
     * @param priority Defines priority for the operation execution.
     *                 Default=0, means operation will be performed immediately after ongoing operation.
     *                 This can also be set to negative number if operation needs higher priority than existing ones.
     * @param coroutineBlock Suspended function that needs to be executed mutually exclusive under given scope.
     */
    suspend fun <T : Any>schedule(priority: Int = 0, coroutineBlock: suspend CoroutineScope.() -> T): CompletableDeferred<T> {
        val deferredResult = CompletableDeferred<Any>()
        scope.launch {
            tasks.add(Task(priority, coroutineBlock, deferredResult))
            if (!isRunning) {
                isRunning = true
                while (tasks.isNotEmpty()) {
                    val task = tasks.poll()
                    task?.let {
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
}
