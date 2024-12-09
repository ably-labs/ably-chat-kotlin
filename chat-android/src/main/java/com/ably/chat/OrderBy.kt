package com.ably.chat

/**
 * Represents direction to query messages in.
 */
enum class OrderBy {
    /**
     * The response will include messages from the start of the time window to the end.
     */
    NewestFirst,

    /**
     * the response will include messages from the end of the time window to the start.
     */
    OldestFirst,
}
