package com.ably.chat.common

import com.ably.chat.Subscription
import java.util.TreeSet
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
    override fun register(listener: BlockingListener<V>): Subscription {
        val subscriber = Subscriber(sequentialScope, subscriberScope, SubscriberBlock(block = listener))
        subscribers.add(subscriber)
        return Subscription {
            synchronized(this) {
                subscribers.remove(subscriber)
            }
        }
    }
}

interface IGenericEventEmitter<E, V> : EventEmitter<E, V> {
    fun register(event: E, listener: BlockingListener<V>): Subscription
}

open class GeneticEventEmitter<E, V> (
    private val subscriberScope: CoroutineScope = CoroutineScope(
        Dispatchers.Default,
    ),
) : AsyncEventEmitter<E, V>(subscriberScope), IGenericEventEmitter<E, V> {

    override fun register(event: E, listener: BlockingListener<V>): Subscription {
        val subscriber = Subscriber(sequentialScope, subscriberScope, SubscriberBlock(block = listener))
        if (eventToSubscribersMap.contains(event)) {
            val subscribers = eventToSubscribersMap[event]
            subscribers?.add(subscriber)
        } else {
            eventToSubscribersMap[event] = TreeSet<Subscriber<V>>().apply {
                add(subscriber)
            }
        }
        return Subscription {
            synchronized(this) {
                eventToSubscribersMap[event]?.remove(subscriber)
            }
        }
    }
}
