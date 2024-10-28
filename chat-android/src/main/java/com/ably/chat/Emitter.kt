package com.ably.chat

import io.ably.annotation.Experimental
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
class AsyncEmitter<V> (private val subscriberScope: CoroutineScope = CoroutineScope(Dispatchers.Default)) : Emitter<V> {

    // Sorted list of unique subscribers based on supplied block
    private val subscribers = TreeSet<AsyncSubscriber<V>>()

    // Emitter scope to make sure all subscribers receive events in same order.
    // Will be automatically garbage collected once all jobs are performed.
    private val sequentialScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    @Synchronized
    override fun emit(value: V) {
        for (subscriber in subscribers) {
            subscriber.inform(value)
        }
    }

    @Synchronized
    override fun on(block: suspend CoroutineScope.(V) -> Unit): Subscription {
        val subscriber = AsyncSubscriber(sequentialScope, subscriberScope, block)
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

    @Experimental
    val finishedProcessing: Boolean
        get() = subscribers.all { it.values.isEmpty() && !it.isSubscriberRunning }

    @get:Synchronized
    val subscribersCount: Int
        get() = subscribers.size

    private class AsyncSubscriber<V>(
        private val emitterSequentialScope: CoroutineScope,
        private val subscriberScope: CoroutineScope,
        private val subscriberBlock: (suspend CoroutineScope.(V) -> Unit),
        private val logger: LogHandler? = null,
    ) : Comparable<V> {
        val values = LinkedBlockingQueue<V>()
        var isSubscriberRunning = false

        fun inform(value: V) {
            values.add(value)
            emitterSequentialScope.launch {
                if (!isSubscriberRunning) {
                    isSubscriberRunning = true
                    while (values.isNotEmpty()) {
                        val valueTobeEmitted = values.poll()
                        runCatching {
                            // Should process values sequentially, similar to blocking eventEmitter
                            subscriberScope.launch {
                                try {
                                    subscriberBlock(valueTobeEmitted as V)
                                } catch (t: Throwable) {
                                    // Catching exception to avoid error propagation to parent
                                    // TODO - replace with more verbose logging
                                    logger?.println(ERROR, "AsyncSubscriber", "Error processing value $valueTobeEmitted", t)
                                }
                            }.join()
                        }
                    }
                    isSubscriberRunning = false
                }
            }
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
}
