package com.ably.chat

import com.ably.chat.common.BlockingListener
import com.ably.chat.common.GenericEmitter
import io.ably.lib.types.ErrorInfo
import kotlinx.coroutines.CoroutineScope
import io.ably.lib.realtime.ChannelBase as AblyRealtimeChannel

/**
 * Represents an object that has a channel and therefore may care about discontinuities.
 */
interface HandlesDiscontinuity {
    /**
     * A promise of the channel that this object is associated with. The promise
     * is resolved when the feature has finished initializing.
     */
    val channel: AblyRealtimeChannel

    /**
     * Called when a discontinuity is detected on the channel.
     * @param reason The error that caused the discontinuity.
     */
    fun discontinuityDetected(reason: ErrorInfo?)
}

/**
 * A listener that can be registered for discontinuity events.
 * @param reason The error that caused the discontinuity.
 */
typealias DiscontinuityListenerAsync = suspend CoroutineScope.(reason: ErrorInfo?) -> Unit

/**
 * An interface to be implemented by objects that can emit discontinuities to listeners.
 */
interface EmitsDiscontinuities {
    /**
     * Register a listener to be called when a discontinuity is detected.
     * @param listener The listener to be called when a discontinuity is detected.
     */
    fun onDiscontinuity(listener: Listener): Subscription

    /**
     * Register a async suspended listener to be called when a discontinuity is detected.
     * JvmSynthetic makes method unavailable for java code ( since java can't handle it ).
     * @param listener The listener to be called when a discontinuity is detected.
     */
    @JvmSynthetic
    fun onDiscontinuity(listener: DiscontinuityListenerAsync): Subscription

    /**
     * An interface for listening when discontinuity happens
     */
    fun interface Listener : BlockingListener<ErrorInfo?> {

        override fun onChange(value: ErrorInfo?) {
            discontinuityEmitted(value)
        }

        /**
         * A function that can be called when discontinuity happens.
         * @param reason reason for discontinuity
         */
        fun discontinuityEmitted(reason: ErrorInfo?)
    }
}

open class DiscontinuityEmitter : GenericEmitter<ErrorInfo?>()
