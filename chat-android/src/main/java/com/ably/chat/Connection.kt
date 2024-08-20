package com.ably.chat

/**
 * Represents a connection to Ably.
 */
interface Connection {
    /**
     * The current status of the connection.
     */
    val status: ConnectionStatus
}
