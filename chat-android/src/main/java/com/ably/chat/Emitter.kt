package com.ably.chat

import io.ably.lib.util.Log.ERROR
import io.ably.lib.util.Log.LogHandler
import java.util.TreeSet
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Emitter interface for supplied value
 * Ideally, class implementation should work for both kotlin and java
 */
interface Emitter<V> {
    fun emit(value: V)
    fun on(block: suspend CoroutineScope.(V) -> Unit): Subscription
    fun offAll()
}

/**
 * AsyncEmitter is thread safe, async emitter implementation for kotlin.
 * Currently, use-case is limited to handle internal events.
 * This can be modified in the future to handle external listeners, events etc
 */
class AsyncEmitter<V> (private val collectorScope: CoroutineScope = CoroutineScope(Dispatchers.Default)) : Emitter<V> {

    private val subscribers = TreeSet<AsyncSubscriber<V>>()

    @Synchronized
    override fun emit(value: V) {
        for (subscriber in subscribers) {
            subscriber.notify(value)
        }
    }

    @Synchronized
    override fun on(block: suspend CoroutineScope.(V) -> Unit): Subscription {
        val subscriber = AsyncSubscriber(collectorScope, block)
        subscribers.add(subscriber)
        return Subscription {
            synchronized(this) {
                subscribers.remove(subscriber)
            }
        }
    }

    @Synchronized
    override fun offAll() {
        subscribers.clear()
    }
}

private class AsyncSubscriber<V>(
    private val scope: CoroutineScope,
    private val subscriberBlock: (suspend CoroutineScope.(V) -> Unit),
    private val logger: LogHandler? = null,
) : Comparable<V> {
    private val values = LinkedBlockingQueue<V>()
    private var isSubscriberRunning = false

    fun notify(value: V) {
        values.add(value)
        sequentialScope.launch {
            if (!isSubscriberRunning) {
                isSubscriberRunning = true
                while (values.isNotEmpty()) {
                    val valueTobeEmitted = values.poll()
                    try {
                        // Should process values sequentially, similar to blocking eventEmitter
                        scope.launch { subscriberBlock(valueTobeEmitted as V) }.join()
                    } catch (t: Throwable) {
                        // TODO - replace with more verbose logging
                        logger?.println(ERROR, "AsyncSubscriber", "Error processing value $valueTobeEmitted", t)
                    }
                }
                isSubscriberRunning = false
            }
        }
    }

    companion object {
        val sequentialScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
    }

    override fun compareTo(other: V): Int {
        // Avoid registering duplicate anonymous subscriber block with same instance id
        // Common scenario when Android activity is refreshed or some app components refresh
        if (other is AsyncSubscriber<*>) {
            return this.subscriberBlock.hashCode().compareTo(other.subscriberBlock.hashCode())
        }
        return this.hashCode().compareTo(other.hashCode())
    }
}
