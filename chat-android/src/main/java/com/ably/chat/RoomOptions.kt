package com.ably.chat

import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo

/**
 * Represents the options for a given chat room.
 */
data class RoomOptions(
    /**
     * The presence options for the room. To enable presence in the room, set this property. You may
     * use {@link RoomOptionsDefaults.presence} to enable presence with default options.
     * @defaultValue undefined
     */
    val presence: PresenceOptions? = null,

    /**
     * The typing options for the room. To enable typing in the room, set this property. You may use
     * {@link RoomOptionsDefaults.typing} to enable typing with default options.
     */
    val typing: TypingOptions? = null,

    /**
     * The reactions options for the room. To enable reactions in the room, set this property. You may use
     * {@link RoomOptionsDefaults.reactions} to enable reactions with default options.
     */
    val reactions: RoomReactionsOptions? = null,

    /**
     * The occupancy options for the room. To enable occupancy in the room, set this property. You may use
     * {@link RoomOptionsDefaults.occupancy} to enable occupancy with default options.
     */
    val occupancy: OccupancyOptions? = null,
)

/**
 * Represents the presence options for a chat room.
 */
data class PresenceOptions(
    /**
     * Whether the underlying Realtime channel should use the presence enter mode, allowing entry into presence.
     * This property does not affect the presence lifecycle, and users must still call {@link Presence.enter}
     * in order to enter presence.
     * @defaultValue true
     */
    val enter: Boolean = true,

    /**
     * Whether the underlying Realtime channel should use the presence subscribe mode, allowing subscription to presence.
     * This property does not affect the presence lifecycle, and users must still call {@link Presence.subscribe}
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
 */
fun RoomOptions.validateRoomOptions() {
    if (typing != null && typing.timeoutMs <= 0) {
        throw AblyException.fromErrorInfo(
            ErrorInfo(
                "Typing timeout must be greater than 0",
                ErrorCodes.InvalidRequestBody.errorCode,
                HttpStatusCodes.BadRequest,
            ),
        )
    }
}
