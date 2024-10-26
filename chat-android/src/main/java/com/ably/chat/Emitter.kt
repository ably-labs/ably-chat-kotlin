package com.ably.chat

import io.ably.lib.util.Log.ERROR
import io.ably.lib.util.Log.LogHandler
import java.util.LinkedList
import java.util.concurrent.CopyOnWriteArrayList
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
 * AsyncEmitter is thread safe, highly performant async emitter implementation for kotlin.
 * Currently, use-case is limited to handle internal events.
 * This can be modified in the future to handle external listeners, events etc
 */
class AsyncEmitter<V> (private val collectorScope: CoroutineScope = CoroutineScope(Dispatchers.Default)) : Emitter<V> {

    // Read more on https://www.codejava.net/java-core/concurrency/java-concurrent-collection-copyonwritearraylist-examples
    private val subscribers = CopyOnWriteArrayList<AsyncSubscriber<V>>()

    override fun emit(value: V) {
        for (subscriber in subscribers) {
            subscriber.notifyAsync(value)
        }
    }

    override fun on(block: suspend CoroutineScope.(V) -> Unit): Subscription {
        val subscriber = AsyncSubscriber(collectorScope, block)
        subscribers.addIfAbsent(subscriber)
        return Subscription {
            subscribers.remove(subscriber)
        }
    }

    override fun offAll() {
        subscribers.clear()
    }
}

private class AsyncSubscriber<V>(
    private val scope: CoroutineScope,
    private val subscriberBlock: (suspend CoroutineScope.(V) -> Unit),
    private val logger: LogHandler? = null,
) {
    private var isSubscriberRunning = false
    private val values = LinkedList<V>()

    fun notifyAsync(value: V) {
        sequentialScope.launch {
            values.add(value)
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

    override fun equals(other: Any?): Boolean {
        if (other is AsyncSubscriber<*>) {
            // Avoid registering duplicate anonymous subscriber block with same instance id
            // Common scenario when Android activity is refreshed or some app components refresh
            return this.subscriberBlock.hashCode() == other.subscriberBlock.hashCode()
        }
        return super.equals(other)
    }

    companion object {
        val sequentialScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))
    }
}
