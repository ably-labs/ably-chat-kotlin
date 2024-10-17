package com.ably.chat

import java.util.PriorityQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private class Task(
    private val priority: Int,
    val coroutineBlock: suspend CoroutineScope.() -> Any,
    val resultChannel: Channel<Result<Any>>)
    : Comparable<Task> {
        override fun compareTo(other: Task): Int {
            return other.priority - this.priority
        }
}

/**
 * Aim of AtomicExecutor is to execute given coroutine operation mutually exclusive.
 * @property scope Uses single threaded dispatcher to avoid thread synchronization issues.
 */
class AtomicExecutor(private val scope: CoroutineScope) {
    private var isRunning = false
    private val priorityQueue: PriorityQueue<Task> = PriorityQueue()

    suspend fun <T : Any>execute(priority:Int, coroutineBlock: suspend CoroutineScope.() -> T) : Channel<Result<T>> {
        // Size of resultChannel is set to 1 to keep while loop running.
        // i.e. If caller doesn't explicitly receive on the channel, loop will be blocked.
        val resultChannel = Channel<Result<Any>>(1)
        priorityQueue.add(Task(priority, coroutineBlock, resultChannel))

        scope.launch {
            if (!isRunning) {
                isRunning = true
                while (priorityQueue.size > 0) {
                    val task = priorityQueue.remove()
                    val result = kotlin.runCatching { scope.async(block = task.coroutineBlock).await() }
                    task.resultChannel.send(result)
                }
                isRunning = false
            }
        }

        @Suppress("UNCHECKED_CAST")
        return resultChannel as Channel<Result<T>>
    }
}
