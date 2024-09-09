package com.ably.chat

import io.ably.lib.types.ErrorInfo

/**
 * An interface to be implemented by objects that can emit discontinuities to listeners.
 */
interface EmitsDiscontinuities {
    /**
     * Register a listener to be called when a discontinuity is detected.
     * @param listener The listener to be called when a discontinuity is detected.
     */
    fun onDiscontinuity(listener: Listener): Cancellation

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
