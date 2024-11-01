package com.ably.chat

import com.ably.chat.common.AsyncEventEmitter
import com.ably.chat.common.BlockingListener
import com.ably.chat.common.GenericEventEmitter
import io.ably.lib.types.ErrorInfo
import io.ably.lib.util.Log.LogHandler
import kotlinx.coroutines.CoroutineScope

/**
 * A listener that can be registered for RoomStatusChange events.
 * @param roomStatusChange The change in status.
 */
typealias RoomStatusChangeListenerAsync = suspend CoroutineScope.(roomStatusChange: RoomStatusChange) -> Unit

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
    fun onChange(listener: Listener): Subscription

    /**
     * Register a async suspended listener to be called when a RoomStatusChange is detected.
     * JvmSynthetic makes method unavailable for java code ( since java can't handle it ).
     * @param listener The listener to be called when a RoomStatusChange is detected.
     */
    @JvmSynthetic
    fun onChange(listener: RoomStatusChangeListenerAsync): Subscription

    /**
     * An interface for listening to changes for the room status
     */
    fun interface Listener : BlockingListener<RoomStatusChange> {

        override fun onChange(value: RoomStatusChange) {
            roomStatusChanged(value)
        }

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
    val status: RoomLifecycle

    /**
     * An error that provides a reason why the room has
     * entered the new status, if applicable.
     */
    val error: ErrorInfo?
}

interface InternalRoomStatus : RoomStatus {
    /**
     * Registers a listener that will be called once when the room status changes.
     * @param listener The function to call when the status changes.
     */
    fun onChangeOnce(block: RoomStatusChangeListenerAsync)

    /**
     * Sets the status of the room.
     *
     * @param params The new status of the room.
     */
    fun setStatus(params: NewRoomStatus)
}

/**
 * (CHA-RS1)
 * The different states that a room can be in throughout its lifecycle.
 */
enum class RoomLifecycle(val stateName: String) {
    /**
     * The library is currently initializing the room.
     */
    Initializing("initializing"),

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

open class RoomStatusEventEmitter : GenericEventEmitter<RoomLifecycle, RoomStatusChange>()

open class RoomStatusInternalAsyncEventEmitter(roomScope: CoroutineScope) : AsyncEventEmitter<RoomLifecycle, RoomStatusChange>(roomScope)

class DefaultStatus(private val roomScope: CoroutineScope, private val logger: LogHandler? = null) : InternalRoomStatus, RoomStatusEventEmitter() {

    private val _logger = logger

    private var _state = RoomLifecycle.Initializing
    override val current: RoomLifecycle
        get() = _state

    private var _error: ErrorInfo? = null
    override val error: ErrorInfo?
        get() = _error

    private val internalEmitter = RoomStatusInternalAsyncEventEmitter(roomScope)

    override fun onChange(listener: RoomStatus.Listener): Subscription {
        // TODO - Add warning message when registering blocking subscriber
        return this.subscribe(listener)
    }

    @JvmSynthetic
    override fun onChange(listener: RoomStatusChangeListenerAsync): Subscription {
        return this.on(listener)
    }

    override fun onChangeOnce(block: RoomStatusChangeListenerAsync) {
        internalEmitter.once(block)
    }

    override fun offAll() {
        super.offAll()
    }

    override fun setStatus(params: NewRoomStatus) {
        val change = RoomStatusChange(params.status, current, params.error)
        synchronized(this) {
            this._state = change.current
            this._error = change.error
        }
        this.internalEmitter.emit(change.current, change)
        this.emit(change.current, change)
    }

    internal fun setStatus(status: RoomLifecycle, error: ErrorInfo? = null) {
        val newStatus = object : NewRoomStatus {
            override val status: RoomLifecycle = status
            override val error: ErrorInfo? = error
        }
        this.setStatus(newStatus)
    }
}
