package com.ably.chat

/**
 * A cancellation handle, returned by various functions (mostly subscriptions)
 * where cancellation is required.
 */
fun interface Cancellation {
    /**
     * Handle cancellation (unsubscribe listeners, clean up)
     */
    fun cancel()
}
