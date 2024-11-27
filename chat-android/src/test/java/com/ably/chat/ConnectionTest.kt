package com.ably.chat

import io.ably.lib.realtime.ConnectionState
import io.ably.lib.realtime.ConnectionStateListener
import io.ably.lib.realtime.ConnectionStateListener.ConnectionStateChange
import io.ably.lib.realtime.buildRealtimeConnection
import io.ably.lib.types.ErrorInfo
import io.mockk.every
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import io.ably.lib.realtime.Connection as PubSubConnection

class ConnectionTest {

    private val pubSubConnection = spyk<PubSubConnection>(buildRealtimeConnection())

    private lateinit var connection: Connection

    private val pubSubConnectionStateListenerSlot = slot<ConnectionStateListener>()

    @Before
    fun setUp() {
        every { pubSubConnection.on(capture(pubSubConnectionStateListenerSlot)) } returns Unit
        pubSubConnection.state = ConnectionState.initialized
        connection = DefaultConnection(pubSubConnection, EmptyLogger(LogContext(tag = "TEST")))
    }

    /**
     * @spec: CHA-CS3
     */
    @Test
    fun `initial status and error of the connection must be whatever status the realtime client returns`() = runTest {
        pubSubConnection.reason = ErrorInfo("some error", 400)
        pubSubConnection.state = ConnectionState.disconnected

        val connection = DefaultConnection(pubSubConnection, EmptyLogger(LogContext(tag = "TEST")))
        assertEquals(ConnectionStatus.Disconnected, connection.status)
        assertEquals(pubSubConnection.reason, connection.error)
    }

    /**
     * @spec: CHA-CS4a, CHA-CS4b, CHA-CS4c, CHA-CS4d
     */
    @Test
    fun `status update events must contain the newly entered connection status`() = runTest {
        val deferredEvent = CompletableDeferred<ConnectionStatusChange>()

        connection.onStatusChange {
            deferredEvent.complete(it)
        }

        pubSubConnectionStateListenerSlot.captured.onConnectionStateChanged(
            ConnectionStateChange(
                ConnectionState.initialized,
                ConnectionState.connecting,
                0,
                null,
            ),
        )

        assertEquals(
            ConnectionStatusChange(
                current = ConnectionStatus.Connecting,
                previous = ConnectionStatus.Initialized,
                retryIn = 0,
                error = null,
            ),
            deferredEvent.await(),
        )
    }
}
