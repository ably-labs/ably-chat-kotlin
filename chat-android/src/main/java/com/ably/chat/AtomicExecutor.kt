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
    private val tasks: PriorityQueue<Task> = PriorityQueue()

    /**
     * @param priority Defines priority for the operation execution.
     *                 Default=0, means operation will be performed immediately after ongoing operation.
     *                 This can also be set to negative number if operation needs higher priority than existing ones.
     * @param coroutineBlock Suspended function that needs to be executed mutually exclusive under given scope.
     */
    suspend fun <T : Any>execute(priority:Int = 0, coroutineBlock: suspend CoroutineScope.() -> T) : Channel<Result<T>> {
        // Size of resultChannel is set to 1 to keep while loop running.
        // i.e. If caller doesn't explicitly receive on the channel, loop will be blocked.
        val resultChannel = Channel<Result<Any>>(1)
        tasks.add(Task(priority, coroutineBlock, resultChannel))
        scope.launch {
            if (!isRunning) {
                isRunning = true
                while (tasks.isNotEmpty()) {
                    val task = tasks.poll()
                    task?.let {
                        val result = kotlin.runCatching { scope.async(block = it.coroutineBlock).await() }
                        it.resultChannel.send(result)
                    }
                }
                isRunning = false
            }
        }

        @Suppress("UNCHECKED_CAST")
        return resultChannel as Channel<Result<T>>
    }
}