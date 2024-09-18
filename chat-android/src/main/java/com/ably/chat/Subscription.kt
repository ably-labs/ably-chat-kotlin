package com.ably.chat

/**
 * An unsubscription handle, returned by various functions (mostly subscriptions)
 * where unsubscription is required.
 */
fun interface Subscription {
    /**
     * Handle unsubscription (unsubscribe listeners, clean up)
     */
    fun unsubscribe()
}
