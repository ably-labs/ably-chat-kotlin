package com.ably.chat

import io.ably.lib.types.ErrorInfo
import io.ably.lib.util.EventEmitter
import io.ably.lib.util.Log
import io.ably.lib.util.Log.LogHandler

/**
 * (CHA-RS1)
 * The different states that a room can be in throughout its lifecycle.
 */
enum class RoomStatus(val stateName: String) {
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
    val current: RoomStatus,

    /**
     * The previous status of the room.
     */
    val previous: RoomStatus,

    /**
     * An error that provides a reason why the room has
     * entered the new status, if applicable.
     */
    val error: ErrorInfo? = null,
)

/**
 * Represents the status of a Room.
 */
interface RoomLifecycle {
    /**
     * (CHA-RS2a)
     * The current status of the room.
     */
    val status: RoomStatus

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
    fun onChange(listener: Listener): Subscription

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

    /**
     * Removes all listeners that were added by the `onChange` method.
     */
    fun offAll()
}

/**
 * A new room status that can be set.
 */
interface NewRoomStatus {
    /**
     * The new status of the room.
     */
    val status: RoomStatus

    /**
     * An error that provides a reason why the room has
     * entered the new status, if applicable.
     */
    val error: ErrorInfo?
}

/**
 * An internal interface for the status of a room, which can be used to separate critical
 * internal functionality from user listeners.
 * @internal
 */
interface InternalRoomLifecycle : RoomLifecycle {
    /**
     * Sets the status of the room.
     *
     * @param params The new status of the room.
     */
    fun setStatus(params: NewRoomStatus)
}

class RoomStatusEventEmitter : EventEmitter<RoomStatus, RoomLifecycle.Listener>() {

    override fun apply(listener: RoomLifecycle.Listener?, event: RoomStatus?, vararg args: Any?) {
        try {
            listener?.roomStatusChanged(args[0] as RoomStatusChange)
        } catch (t: Throwable) {
            Log.e("RoomEventEmitter", "Unexpected exception calling Room Status Listener", t)
        }
    }
}

class DefaultRoomLifecycle(private val logger: LogHandler? = null) : InternalRoomLifecycle {

    private val _logger = logger

    private var _status = RoomStatus.Initialized // CHA-RS3
    override val status: RoomStatus
        get() = _status

    private var _error: ErrorInfo? = null
    override val error: ErrorInfo?
        get() = _error

    private val externalEmitter = RoomStatusEventEmitter()
    private val internalEmitter = RoomStatusEventEmitter()

    override fun onChange(listener: RoomLifecycle.Listener): Subscription {
        externalEmitter.on(listener)
        return Subscription {
            externalEmitter.off(listener)
        }
    }

    override fun offAll() {
        externalEmitter.off()
    }

    override fun setStatus(params: NewRoomStatus) {
        setStatus(params.status, params.error)
    }

    internal fun setStatus(status: RoomStatus, error: ErrorInfo? = null) {
        val change = RoomStatusChange(status, _status, error)
        _status = change.current
        _error = change.error
        internalEmitter.emit(change.current, change)
        externalEmitter.emit(change.current, change)
    }
}
