package com.ably.chat

import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.types.ClientOptions

typealias RealtimeClient = AblyRealtime

/**
 * This is the core client for Ably chat. It provides access to chat rooms.
 */
interface ChatClient {
    /**
     * The rooms object, which provides access to chat rooms.
     */
    val rooms: Rooms

    /**
     * The underlying connection to Ably, which can be used to monitor the clients
     * connection to Ably servers.
     */
    val connection: Connection

    /**
     * The clientId of the current client.
     */
    val clientId: String

    /**
     * The underlying Ably Realtime client.
     */
    val realtime: RealtimeClient

    /**
     * The resolved client options for the client, including any defaults that have been set.
     */
    val clientOptions: ClientOptions
}
