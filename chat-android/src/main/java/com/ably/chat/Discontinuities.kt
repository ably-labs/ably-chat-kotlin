package com.ably.chat

import io.ably.lib.types.ErrorInfo
import io.ably.lib.util.EventEmitter
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
 * An interface to be implemented by objects that can emit discontinuities to listeners.
 */
interface EmitsDiscontinuities {
    /**
     * Register a listener to be called when a discontinuity is detected.
     * @param listener The listener to be called when a discontinuity is detected.
     */
    fun onDiscontinuity(listener: Listener): Subscription

    /**
     * An interface for listening when discontinuity happens
     */
    fun interface Listener {
        /**
         * A function that can be called when discontinuity happens.
         * @param reason reason for discontinuity
         */
        fun discontinuityEmitted(reason: ErrorInfo?)
    }
}

internal class DiscontinuityEmitter(logger: Logger) : EventEmitter<String, EmitsDiscontinuities.Listener>() {
    private val logger = logger.withContext("DiscontinuityEmitter")

    override fun apply(listener: EmitsDiscontinuities.Listener?, event: String?, vararg args: Any?) {
        try {
            val reason = args.firstOrNull() as? ErrorInfo?
            listener?.discontinuityEmitted(reason)
        } catch (t: Throwable) {
            logger.error("Unexpected exception calling Discontinuity Listener", t)
        }
    }
}
