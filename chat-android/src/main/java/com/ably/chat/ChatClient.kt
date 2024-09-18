@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import io.ably.lib.realtime.AblyRealtime

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

fun ChatClient(realtimeClient: RealtimeClient, clientOptions: ClientOptions = ClientOptions()): ChatClient =
    DefaultChatClient(realtimeClient, clientOptions)

internal class DefaultChatClient(
    override val realtime: RealtimeClient,
    override val clientOptions: ClientOptions,
) : ChatClient {

    private val chatApi = ChatApi(realtime, clientId)

    override val rooms: Rooms = DefaultRooms(
        realtimeClient = realtime,
        chatApi = chatApi,
        clientOptions = clientOptions,
    )

    override val connection: Connection
        get() = TODO("Not yet implemented")

    override val clientId: String
        get() = realtime.auth.clientId
}
