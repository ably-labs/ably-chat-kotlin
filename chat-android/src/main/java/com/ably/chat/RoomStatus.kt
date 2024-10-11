package com.ably.chat

import io.ably.lib.types.ErrorInfo

/**
 * Represents the status of a Room.
 */
interface RoomStatus {
    /**
     * (CHA-RS2a)
     * The current status of the room.
     */
    val current: RoomLifecycle

    /**
     * (CHA-RS2b)
     * The current error, if any, that caused the room to enter the current status.
     */
    val error: ErrorInfo?

    /**
     * Registers a listener that will be called whenever the room status changes.
     * @param listener The function to call when the status changes.
     * @returns An object that can be used to unregister the listener.
     */
    fun on(listener: Listener): Subscription

    /**
     * An interface for listening to changes for the room status
     */
    fun interface Listener {
        /**
         * A function that can be called when the room status changes.
         * @param change The change in status.
         */
        fun roomStatusChanged(change: RoomStatusChange)
    }
}

/**
 * (CHA-RS1)
 * The different states that a room can be in throughout its lifecycle.
 */
enum class RoomLifecycle(val stateName: String) {
    /**
     * (CHA-RS1a)
     * A temporary state for when the library is first initialized.
     */
    Initialized("initialized"),

    /**
     * (CHA-RS1b)
     * The library is currently attempting to attach the room.
     */
    Attaching("attaching"),

    /**
     * (CHA-RS1c)
     * The room is currently attached and receiving events.
     */
    Attached("attached"),

    /**
     * (CHA-RS1d)
     * The room is currently detaching and will not receive events.
     */
    Detaching("detaching"),

    /**
     * (CHA-RS1e)
     * The room is currently detached and will not receive events.
     */
    Detached("detached"),

    /**
     * (CHA-RS1f)
     * The room is in an extended state of detachment, but will attempt to re-attach when able.
     */
    Suspended("suspended"),

    /**
     * (CHA-RS1g)
     * The room is currently detached and will not attempt to re-attach. User intervention is required.
     */
    Failed("failed"),

    /**
     * (CHA-RS1h)
     * The room is in the process of releasing. Attempting to use a room in this state may result in undefined behavior.
     */
    Releasing("releasing"),

    /**
     * (CHA-RS1i)
     * The room has been released and is no longer usable.
     */
    Released("released"),
}

/**
 * Represents a change in the status of the room.
 * (CHA-RS4)
 */
data class RoomStatusChange(
    /**
     * The new status of the room.
     */
    val current: RoomLifecycle,

    /**
     * The previous status of the room.
     */
    val previous: RoomLifecycle,

    /**
     * An error that provides a reason why the room has
     * entered the new status, if applicable.
     */
    val error: ErrorInfo? = null,
)
