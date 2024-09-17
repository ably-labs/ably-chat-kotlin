package com.ably.chat

import io.ably.lib.types.ErrorInfo

/**
 * Default timeout for transient states before we attempt handle them as a state change.
 */
const val TRANSIENT_TIMEOUT = 5000

/**
 * Represents a connection to Ably.
 */
interface ConnectionStatus {
    /**
     * The current status of the connection.
     */
    val current: ConnectionLifecycle

    /**
     * The current error, if any, that caused the connection to enter the current status.
     */
    val error: ErrorInfo?

    /**
     * Registers a listener that will be called whenever the connection status changes.
     * @param listener The function to call when the status changes.
     */
    fun on(listener: Listener): Subscription

    /**
     * An interface for listening to changes for the connection status
     */
    fun interface Listener {
        /**
         * A function that can be called when the connection status changes.
         * @param change The change in status.
         */
        fun connectionStatusChanged(change: ConnectionStatusChange)
    }
}

/**
 * The different states that the connection can be in through its lifecycle.
 */
enum class ConnectionLifecycle(val stateName: String) {
    /**
     * A temporary state for when the library is first initialized.
     */
    Initialized("initialized"),

    /**
     * The library is currently connecting to Ably.
     */
    Connecting("connecting"),

    /**
     * The library is currently connected to Ably.
     */
    Connected("connected"),

    /**
     * The library is currently disconnected from Ably, but will attempt to reconnect.
     */
    Disconnected("disconnected"),

    /**
     * The library is in an extended state of disconnection, but will attempt to reconnect.
     */
    Suspended("suspended"),

    /**
     * The library is currently disconnected from Ably and will not attempt to reconnect.
     */
    Failed("failed"),
}

/**
 * Represents a change in the status of the connection.
 */
data class ConnectionStatusChange(
    /**
     * The new status of the connection.
     */
    val current: ConnectionLifecycle,

    /**
     * The previous status of the connection.
     */
    val previous: ConnectionLifecycle,

    /**
     * An error that provides a reason why the connection has
     * entered the new status, if applicable.
     */
    val error: ErrorInfo?,

    /**
     * The time in milliseconds that the client will wait before attempting to reconnect.
     */
    val retryIn: Long?,
)
