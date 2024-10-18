package com.ably.chat

import java.util.PriorityQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private class Task(
    private val priority: Int,
    val coroutineBlock: suspend CoroutineScope.() -> Any,
    val result: TaskResult<Any>)
    : Comparable<Task> {
        override fun compareTo(other: Task): Int {
            return this.priority.compareTo(other.priority)
        }

        suspend fun setResult(res: Result<Any>) {
            result.channel.send(res)
        }
}

class TaskResult<T> {
    // Size of channel is set to 1. This is to avoid sender getting blocked
    // because receiver doesn't call receive on the channel
    internal val channel = Channel<Result<T>>(1)

    suspend fun await(): Result<T> {
        val result = channel.receive()
        channel.close()
        return result
    }
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
    suspend fun <T : Any>schedule(priority:Int = 0, coroutineBlock: suspend CoroutineScope.() -> T) : TaskResult<T> {
        val taskResult = TaskResult<Any>()
        scope.launch {
            tasks.add(Task(priority, coroutineBlock, taskResult))
            if (!isRunning) {
                isRunning = true
                while (tasks.isNotEmpty()) {
                    val task = tasks.poll()
                    task?.let {
                        val result = kotlin.runCatching { scope.async(block = it.coroutineBlock).await() }
                        it.setResult(result)
                    }
                }
                isRunning = false
            }
        }

        @Suppress("UNCHECKED_CAST")
        return taskResult as TaskResult<T>
    }
}
