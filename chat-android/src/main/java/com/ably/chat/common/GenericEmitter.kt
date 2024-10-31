package com.ably.chat.common

import com.ably.chat.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * An interface for listening to events and process them in a blocking mode
 * Main use-case is for listening to events from java code.
 */
fun interface BlockingListener<V> {
    fun onChange(value: V)
}

/**
 * Kotlin/Java Emitter interface for supplied value
 * Ideally, class implementation should work for both kotlin and java
 */
interface IGenericEmitter<V> : Emitter<V> {
    fun register(listener: BlockingListener<V>): Subscription
}

open class GenericEmitter<V> (
    private val subscriberScope: CoroutineScope = CoroutineScope(
        Dispatchers.Default,
    ),
) : AsyncEmitter<V>(subscriberScope), IGenericEmitter<V> {

    @Synchronized
    override fun register(listener: BlockingListener<V>): Subscription {
        val subscriber = Subscriber(sequentialScope, subscriberScope, SubscriberBlock(block = listener))
        return register(subscriber)
    }
}

interface IGenericEventEmitter<E, V> : EventEmitter<E, V>, IGenericEmitter<V> {
    fun register(event: E, listener: BlockingListener<V>): Subscription
}

open class GenericEventEmitter<E, V> (
    private val subscriberScope: CoroutineScope = CoroutineScope(
        Dispatchers.Default,
    ),
) : AsyncEventEmitter<E, V>(subscriberScope), IGenericEventEmitter<E, V> {

    @Synchronized
    override fun register(listener: BlockingListener<V>): Subscription {
        val subscriber = Subscriber(sequentialScope, subscriberScope, SubscriberBlock(block = listener))
        return register(subscriber)
    }

    @Synchronized
    override fun register(event: E, listener: BlockingListener<V>): Subscription {
        val subscriber = Subscriber(sequentialScope, subscriberScope, SubscriberBlock(block = listener))
        return register(event, subscriber)
    }
}
