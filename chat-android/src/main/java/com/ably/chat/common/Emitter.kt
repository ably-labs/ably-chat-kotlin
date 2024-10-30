package com.ably.chat.common

import com.ably.chat.Subscription
import io.ably.annotation.Experimental
import java.util.TreeSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Kotlin Emitter interface for supplied value
 */

interface Emitter<V> {
    fun emit(value: V)
    fun on(block: suspend CoroutineScope.(V) -> Unit, once: Boolean = false): Subscription
    fun off(subscriber: Subscriber<V>)
    fun offAll()
}

/**
 * AsyncEmitter is thread safe, async emitter implementation for kotlin.
 * use-case is mainly for handling internal events.
 */
open class AsyncEmitter<V> (private val subscriberScope: CoroutineScope = CoroutineScope(Dispatchers.Default)) : Emitter<V> {

    // Sorted list of unique subscribers based on supplied block
    protected val subscribers = TreeSet<Subscriber<V>>()

    // Emitter scope to make sure all subscribers receive events in same order.
    // Will be automatically garbage collected once all jobs are performed.
    protected val sequentialScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    @Synchronized
    override fun emit(value: V) {
        for (subscriber in subscribers.toList()) {
            subscriber.inform(value)
            if (subscriber.once) {
                off(subscriber)
            }
        }
    }

    @Synchronized
    override fun on(block: suspend CoroutineScope.(V) -> Unit, once: Boolean): Subscription {
        val subscriber = Subscriber(sequentialScope, subscriberScope, SubscriberBlock(block), once)
        subscribers.add(subscriber)
        return Subscription {
            off(subscriber)
        }
    }

    @Synchronized
    override fun off(subscriber: Subscriber<V>) {
        subscribers.remove(subscriber)
    }

    @Synchronized
    override fun offAll() {
        subscribers.clear()
    }

    @Experimental
    open val finishedProcessing: Boolean
        get() = subscribers.all { it.values.isEmpty() && !it.isSubscriberRunning }

    @get:Synchronized
    open val subscribersCount: Int
        get() = subscribers.size
}

interface EventEmitter<E, V> {
    fun emit(event: E, value: V)
    fun on(event: E, block: suspend CoroutineScope.(V) -> Unit, once: Boolean = false): Subscription
    fun off(event: E, subscriber: Subscriber<V>)
    fun offAll()
}

/**
 * AsyncEventEmitter is a thread safe, async emitter implementation for kotlin.
 * use-case is limited to handle internal events.
 */
open class AsyncEventEmitter<E, V> (
    private val subscriberScope: CoroutineScope = CoroutineScope(
        Dispatchers.Default,
    ),
) : AsyncEmitter<V>(subscriberScope), EventEmitter<E, V> {

    internal val eventToSubscribersMap = mutableMapOf<E, TreeSet<Subscriber<V>>>()

    @Synchronized
    override fun emit(event: E, value: V) {
        super.emit(value)
        val eventSubscribers = eventToSubscribersMap[event]
        eventSubscribers?.run {
            for (subscriber in this.toList()) {
                subscriber.inform(value)
                if (subscriber.once) {
                    off(event, subscriber)
                }
            }
        }
    }

    @Synchronized
    override fun on(event: E, block: suspend CoroutineScope.(V) -> Unit, once: Boolean): Subscription {
        val subscriber = Subscriber(sequentialScope, subscriberScope, SubscriberBlock(block), once)
        if (eventToSubscribersMap.contains(event)) {
            val subscribers = eventToSubscribersMap[event]
            subscribers?.add(subscriber)
        } else {
            eventToSubscribersMap[event] = TreeSet<Subscriber<V>>().apply {
                add(subscriber)
            }
        }
        return Subscription {
            off(event, subscriber)
        }
    }

    @Synchronized
    override fun off(event: E, subscriber: Subscriber<V>) {
        eventToSubscribersMap[event]?.remove(subscriber)
    }

    @Synchronized
    override fun offAll() {
        super.offAll()
        eventToSubscribersMap.clear()
    }

    @Experimental
    override val finishedProcessing: Boolean
        get() = super.finishedProcessing &&
            eventToSubscribersMap.values.flatten().all { it.values.isEmpty() && !it.isSubscriberRunning }

    @get:Synchronized
    override val subscribersCount: Int
        get() = super.subscribersCount + eventToSubscribersMap.values.flatten().size
}
