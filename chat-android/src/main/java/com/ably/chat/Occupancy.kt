package com.ably.chat

import io.ably.lib.realtime.Channel

/**
 * This interface is used to interact with occupancy in a chat room: subscribing to occupancy updates and
 * fetching the current room occupancy metrics.
 *
 * Get an instance via {@link Room.occupancy}.
 */
interface Occupancy : EmitsDiscontinuities {
    /**
     * Get underlying Ably channel for occupancy events.
     *
     * @returns The underlying Ably channel for occupancy events.
     */
    val channel: Channel

    /**
     * Subscribe a given listener to occupancy updates of the chat room.
     *
     * @param listener A listener to be called when the occupancy of the room changes.
     * @returns A promise resolves to the channel attachment state change event from the implicit channel attach operation.
     */
    fun subscribe(listener: Listener)

    /**
     * Unsubscribe a given listener to occupancy updates of the chat room.
     *
     * @param listener A listener to be unsubscribed.
     */
    fun unsubscribe(listener: Listener)

    /**
     * Get the current occupancy of the chat room.
     *
     * @returns A promise that resolves to the current occupancy of the chat room.
     */
    suspend fun get(): OccupancyEvent

    /**
     * An interface for listening to new occupancy event
     */
    fun interface Listener {
        /**
         * A function that can be called when the new occupancy event happens.
         * @param event The event that happened.
         */
        fun onEvent(event: OccupancyEvent)
    }
}

/**
 * Represents the occupancy of a chat room.
 */
data class OccupancyEvent(
    /**
     * The number of connections to the chat room.
     */
    val connections: Int,

    /**
     * The number of presence members in the chat room - members who have entered presence.
     */
    val presenceMembers: Int,
)
