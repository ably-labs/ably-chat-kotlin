package com.ably.chat.common

import io.ably.lib.util.Log.ERROR
import io.ably.lib.util.Log.LogHandler
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface ISubscriber<V> {
    fun inform(value: V)
}

class SubscriberBlock<V>(
    private val coroutineFn: (suspend CoroutineScope.(V) -> Unit)? = null,
    private val blockingFn: BlockingListener<V>? = null,
) {

    suspend operator fun invoke(scope: CoroutineScope, value: V) {
        coroutineFn?.invoke(scope, value)
        blockingFn?.onChange(value)
    }

    override fun hashCode(): Int {
        coroutineFn?.let {
            return coroutineFn.hashCode()
        }
        blockingFn?.let {
            return blockingFn.hashCode()
        }
        return super.hashCode()
    }
}

class Subscriber<V>(
    private val emitterSequentialScope: CoroutineScope,
    private val subscriberScope: CoroutineScope,
    private val subscriberBlock: SubscriberBlock<V>,
    internal val once: Boolean = false,
    private val logger: LogHandler? = null,
) : Comparable<V>, ISubscriber<V> {
    val values = LinkedBlockingQueue<V>() // Accessed by both Emitter#emit and emitterSequentialScope
    var isSubscriberRunning = false // Only accessed as a part of emitterSequentialScope

    override fun inform(value: V) {
        values.add(value)
        emitterSequentialScope.launch {
            if (!isSubscriberRunning) {
                isSubscriberRunning = true
                while (values.isNotEmpty()) {
                    val valueTobeEmitted = values.poll()
                    safelyPublish(valueTobeEmitted as V) // Process sequentially, similar to core ably eventEmitter
                }
                isSubscriberRunning = false
            }
        }
    }

    private suspend fun safelyPublish(value: V) {
        runCatching {
            subscriberScope.launch {
                try {
                    subscriberBlock.invoke(this, value)
                } catch (t: Throwable) {
                    // Catching exception to avoid error propagation to parent
                    // TODO - replace with more verbose logging
                    logger?.println(ERROR, "AsyncSubscriber", "Error processing value $value", t)
                }
            }.join()
        }
    }

    override fun compareTo(other: V): Int {
        // Avoid registering duplicate anonymous subscriber block with same instance id
        // Common scenario when Android activity is refreshed or some app components refresh
        if (other is Subscriber<*>) {
            return this.subscriberBlock.hashCode().compareTo(other.subscriberBlock.hashCode())
        }
        return this.hashCode().compareTo(other.hashCode())
    }
}
