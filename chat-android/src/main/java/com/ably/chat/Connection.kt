package com.ably.chat

import io.ably.lib.realtime.ConnectionState
import io.ably.lib.types.ErrorInfo
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.ably.lib.realtime.Connection as PubSubConnection

/**
 * Default timeout for transient states before we attempt handle them as a state change.
 */
internal const val TRANSIENT_TIMEOUT = 5000

/**
 * (CHA-CS1) The different states that the connection can be in through its lifecycle.
 */
enum class ConnectionStatus(val stateName: String) {
    /**
     * (CHA-CS1a) A temporary state for when the library is first initialized.
     */
    Initialized("initialized"),

    /**
     * (CHA-CS1b) The library is currently connecting to Ably.
     */
    Connecting("connecting"),

    /**
     * (CHA-CS1c) The library is currently connected to Ably.
     */
    Connected("connected"),

    /**
     * (CHA-CS1d) The library is currently disconnected from Ably, but will attempt to reconnect.
     */
    Disconnected("disconnected"),

    /**
     * (CHA-CS1e) The library is in an extended state of disconnection, but will attempt to reconnect.
     */
    Suspended("suspended"),

    /**
     * (CHA-CS1f) The library is currently disconnected from Ably and will not attempt to reconnect.
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
    val current: ConnectionStatus,

    /**
     * The previous status of the connection.
     */
    val previous: ConnectionStatus,

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

/**
 * Represents a connection to Ably.
 */
interface Connection {
    /**
     * (CHA-CS2a) The current status of the connection.
     */
    val status: ConnectionStatus

    /**
     * (CHA-CS2b) The current error, if any, that caused the connection to enter the current status.
     */
    val error: ErrorInfo?

    /**
     * (CHA-CS4) Registers a listener that will be called whenever the connection status changes.
     * @param listener The function to call when the status changes.
     * @returns An object that can be used to unregister the listener.
     */
    fun onStatusChange(listener: Listener): Subscription

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

internal class DefaultConnection(
    pubSubConnection: PubSubConnection,
    private val logger: Logger,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Connection {

    private val connectionScope = CoroutineScope(dispatcher.limitedParallelism(1) + SupervisorJob())

    private val listeners: MutableList<Connection.Listener> = CopyOnWriteArrayList()

    private var transientDisconnectJob: Job? = null

    // (CHA-CS3)
    override var status: ConnectionStatus = mapPubSubStatusToChat(pubSubConnection.state)
        private set

    override var error: ErrorInfo? = pubSubConnection.reason
        private set

    init {
        pubSubConnection.on { stateChange ->
            val nextStatus = mapPubSubStatusToChat(stateChange.current)

            val transientDisconnectTimerIsActive = transientDisconnectJob != null

            // (CHA-CS5a2)
            if (transientDisconnectTimerIsActive && nextStatus in listOf(ConnectionStatus.Connecting, ConnectionStatus.Disconnected)) {
                return@on
            }

            // (CHA-CS5a1)
            if (nextStatus == ConnectionStatus.Disconnected && status == ConnectionStatus.Connected) {
                transientDisconnectJob = connectionScope.launch {
                    delay(TRANSIENT_TIMEOUT.milliseconds)
                    applyStatusChange(nextStatus, stateChange.reason, stateChange.retryIn)
                    transientDisconnectJob = null
                }
            } else {
                // (CHA-CS5a3)
                transientDisconnectJob?.cancel()
                transientDisconnectJob = null
                applyStatusChange(nextStatus, stateChange.reason, stateChange.retryIn)
            }
        }
    }

    override fun onStatusChange(listener: Connection.Listener): Subscription {
        logger.trace("Connection.onStatusChange()")
        listeners.add(listener)

        return Subscription {
            logger.trace("Connection.offStatusChange()")
            listeners.remove(listener)
        }
    }

    private fun applyStatusChange(nextStatus: ConnectionStatus, error: ErrorInfo?, retryIn: Long?) {
        val previous = status
        this.status = nextStatus
        this.error = error
        logger.info("Connection state changed from ${previous.stateName} to ${nextStatus.stateName}")
        emitStateChange(
            ConnectionStatusChange(
                current = status,
                previous = previous,
                error = error,
                retryIn = retryIn,
            ),
        )
    }

    private fun emitStateChange(statusChange: ConnectionStatusChange) {
        listeners.forEach { it.connectionStatusChanged(statusChange) }
    }
}

private fun mapPubSubStatusToChat(status: ConnectionState): ConnectionStatus {
    return when (status) {
        ConnectionState.initialized -> ConnectionStatus.Initialized
        ConnectionState.connecting -> ConnectionStatus.Connecting
        ConnectionState.connected -> ConnectionStatus.Connected
        ConnectionState.disconnected -> ConnectionStatus.Disconnected
        ConnectionState.suspended -> ConnectionStatus.Suspended
        ConnectionState.failed -> ConnectionStatus.Failed
        ConnectionState.closing -> ConnectionStatus.Failed
        ConnectionState.closed -> ConnectionStatus.Failed
    }
}
