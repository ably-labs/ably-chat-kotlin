package com.ably.chat

import io.ably.lib.types.ChannelMode
import io.ably.lib.types.ChannelOptions

/**
 * Represents the options for a given chat room.
 */
data class RoomOptions(
    /**
     * The presence options for the room. To enable presence in the room, set this property. You may
     * use [RoomOptionsDefaults.presence] to enable presence with default options.
     * @defaultValue undefined
     */
    val presence: PresenceOptions? = null,

    /**
     * The typing options for the room. To enable typing in the room, set this property. You may use
     * [RoomOptionsDefaults.typing] to enable typing with default options.
     */
    val typing: TypingOptions? = null,

    /**
     * The reactions options for the room. To enable reactions in the room, set this property. You may use
     * [RoomOptionsDefaults.reactions] to enable reactions with default options.
     */
    val reactions: RoomReactionsOptions? = null,

    /**
     * The occupancy options for the room. To enable occupancy in the room, set this property. You may use
     * [RoomOptionsDefaults.occupancy] to enable occupancy with default options.
     */
    val occupancy: OccupancyOptions? = null,
) {
    companion object {
        /**
         * Supports all room options with default values
         */
        val default = RoomOptions(
            typing = TypingOptions(),
            presence = PresenceOptions(),
            reactions = RoomReactionsOptions,
            occupancy = OccupancyOptions,
        )
    }
}

/**
 * Represents the presence options for a chat room.
 */
data class PresenceOptions(
    /**
     * Whether the underlying Realtime channel should use the presence enter mode, allowing entry into presence.
     * This property does not affect the presence lifecycle, and users must still call [Presence.enter]
     * in order to enter presence.
     * @defaultValue true
     */
    val enter: Boolean = true,

    /**
     * Whether the underlying Realtime channel should use the presence subscribe mode, allowing subscription to presence.
     * This property does not affect the presence lifecycle, and users must still call [Presence.subscribe]
     * in order to subscribe to presence.
     * @defaultValue true
     */
    val subscribe: Boolean = true,
)

/**
 * Represents the typing options for a chat room.
 */
data class TypingOptions(
    /**
     * The timeout for typing events in milliseconds. If typing.start() is not called for this amount of time, a stop
     * typing event will be fired, resulting in the user being removed from the currently typing set.
     * @defaultValue 5000
     */
    val timeoutMs: Long = 5000,
)

/**
 * Represents the reactions options for a chat room.
 */
object RoomReactionsOptions

/**
 * Represents the occupancy options for a chat room.
 */
object OccupancyOptions

/**
 * Throws AblyException for invalid room configuration.
 * Spec: CHA-RC2a
 */
internal fun RoomOptions.validateRoomOptions() {
    typing?.let {
        if (typing.timeoutMs <= 0) {
            throw ablyException("Typing timeout must be greater than 0", ErrorCode.InvalidRequestBody)
        }
    }
}

/**
 * Merges channel options/modes from presence and occupancy to be used for shared channel.
 * This channel is shared by Room messages, presence and occupancy feature.
 * @return channelOptions for shared channel with options/modes from presence and occupancy.
 * Spec: CHA-RC3
 */
internal fun RoomOptions.messagesChannelOptions(): ChannelOptions {
    return ChatChannelOptions {
        presence?.let {
            val presenceModes = mutableListOf<ChannelMode>()
            if (presence.enter) {
                presenceModes.add(ChannelMode.presence)
            }
            if (presence.subscribe) {
                presenceModes.add(ChannelMode.presence_subscribe)
            }
            modes = presenceModes.toTypedArray()
        }
        occupancy?.let {
            params = mapOf(
                "occupancy" to "metrics",
            )
        }
    }
}
