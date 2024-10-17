package com.ably.chat

import java.util.PriorityQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private class OrderedCoroutine(
    private val priority: Int,
    val suspendedFn: suspend CoroutineScope.() -> Any,
    val resultChannel: Channel<Result<Any>>)
    : Comparable<OrderedCoroutine> {
        override fun compareTo(other: OrderedCoroutine): Int {
            return other.priority - this.priority
        }
}

/**
 * Aim of PriorityExecutor is to execute given coroutine operation mutually exclusive.
 * @property scope Uses single threaded dispatcher to avoid thread synchronization issues.
 */
class PriorityExecutor(private val scope: CoroutineScope) {
    private var isRunning = false
    private val priorityQueue: PriorityQueue<OrderedCoroutine> = PriorityQueue()

    suspend fun <T : Any>execute(priority:Int, suspendedFun: suspend CoroutineScope.() -> T) : Channel<Result<T>> {
        // Size of resultChannel is set to 1 to keep while loop running.
        // i.e. If other end doesn't call receive on the channel, loop will be blocked.
        val resultChannel = Channel<Result<Any>>(1)
        priorityQueue.add(OrderedCoroutine(priority, suspendedFun, resultChannel))

        scope.launch {
            if (!isRunning) {
                isRunning = true
                while (priorityQueue.size > 0) {
                    val runningCoroutine = priorityQueue.remove()
                    val result = kotlin.runCatching { scope.async(block = runningCoroutine.suspendedFn).await() }
                    runningCoroutine.resultChannel.send(result)
                }
                isRunning = false
            }
        }

        @Suppress("UNCHECKED_CAST")
        return resultChannel as Channel<Result<T>>
    }
}
