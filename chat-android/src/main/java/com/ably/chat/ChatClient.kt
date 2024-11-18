@file:Suppress("NotImplementedDeclaration")

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

    private val logger: Logger = if (clientOptions.logHandler != null) {
        CustomLogger(
            clientOptions.logHandler,
            clientOptions.logLevel,
            buildLogContext(),
        )
    } else {
        AndroidLogger(clientOptions.logLevel, buildLogContext())
    }

    private val chatApi = ChatApi(realtime, clientId, logger.withContext(tag = "AblyChatAPI"))

    override val rooms: Rooms = DefaultRooms(
        realtimeClient = realtime,
        chatApi = chatApi,
        clientOptions = clientOptions,
        clientId = clientId,
    )

    override val connection: Connection
        get() = TODO("Not yet implemented")

    override val clientId: String
        get() = realtime.auth.clientId

    private fun buildLogContext() = LogContext(
        tag = "ChatClient",
        staticContext = mapOf(
            "clientId" to clientId,
            "instanceId" to generateUUID(),
        ),
        dynamicContext = mapOf(
            "connectionId" to { realtime.connection.id },
            "connectionState" to { realtime.connection.state.name },
        ),
    )
}
