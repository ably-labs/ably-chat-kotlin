@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import io.ably.lib.types.ErrorInfo

/**
 * Represents a chat room.
 */
interface Room {
    /**
     * The unique identifier of the room.
     * @returns The room identifier.
     */
    val roomId: String

    /**
     * Allows you to send, subscribe-to and query messages in the room.
     *
     * @returns The messages instance for the room.
     */
    val messages: Messages

    /**
     * Allows you to subscribe to presence events in the room.
     *
     * @throws {@link ErrorInfo}} if presence is not enabled for the room.
     * @returns The presence instance for the room.
     */
    val presence: Presence

    /**
     * Allows you to interact with room-level reactions.
     *
     * @throws {@link ErrorInfo} if reactions are not enabled for the room.
     * @returns The room reactions instance for the room.
     */
    val reactions: RoomReactions

    /**
     * Allows you to interact with typing events in the room.
     *
     * @throws {@link ErrorInfo} if typing is not enabled for the room.
     * @returns The typing instance for the room.
     */
    val typing: Typing

    /**
     * Allows you to interact with occupancy metrics for the room.
     *
     * @throws {@link ErrorInfo} if occupancy is not enabled for the room.
     * @returns The occupancy instance for the room.
     */
    val occupancy: Occupancy

    /**
     * Returns the room options.
     *
     * @returns A copy of the options used to create the room.
     */
    val options: RoomOptions

    /**
     * (CHA-RS2)
     * The current status of the room.
     *
     * @returns The current status.
     */
    val status: RoomStatus

    /**
     * The current error, if any, that caused the room to enter the current status.
     */
    val error: ErrorInfo?

    /**
     * Registers a listener that will be called whenever the room status changes.
     * @param listener The function to call when the status changes.
     * @returns An object that can be used to unregister the listener.
     */
    fun onStatusChange(listener: RoomLifecycle.Listener): Subscription

    /**
     * Removes all listeners that were added by the `onStatusChange` method.
     */
    fun offAllStatusChange()

    /**
     * Attaches to the room to receive events in realtime.
     *
     * If a room fails to attach, it will enter either the {@link RoomLifecycle.Suspended} or {@link RoomLifecycle.Failed} state.
     *
     * If the room enters the failed state, then it will not automatically retry attaching and intervention is required.
     *
     * If the room enters the suspended state, then the call to attach will reject with the {@link ErrorInfo} that caused the suspension. However,
     * the room will automatically retry attaching after a delay.
     */
    suspend fun attach()

    /**
     * Detaches from the room to stop receiving events in realtime.
     */
    suspend fun detach()
}

internal class DefaultRoom(
    override val roomId: String,
    override val options: RoomOptions,
    private val realtimeClient: RealtimeClient,
    chatApi: ChatApi,
    clientId: String,
    private val logger: Logger,
) : Room {

    private val _messages = DefaultMessages(
        roomId = roomId,
        realtimeChannels = realtimeClient.channels,
        chatApi = chatApi,
    )

    private val _typing: DefaultTyping = DefaultTyping(
        roomId = roomId,
        realtimeClient = realtimeClient,
        options = options.typing,
        clientId = clientId,
        logger = logger.withContext(tag = "Typing"),
    )

    private val _occupancy = DefaultOccupancy(
        roomId = roomId,
        realtimeChannels = realtimeClient.channels,
        chatApi = chatApi,
        logger = logger.withContext(tag = "Occupancy"),
    )

    override val messages: Messages
        get() = _messages

    override val typing: Typing
        get() = _typing

    override val occupancy: Occupancy
        get() = _occupancy

    override val presence: Presence = DefaultPresence(
        channel = messages.channel,
        clientId = clientId,
        presence = messages.channel.presence,
    )

    override val reactions: RoomReactions = DefaultRoomReactions(
        roomId = roomId,
        clientId = clientId,
        realtimeChannels = realtimeClient.channels,
    )

    private val statusLifecycle = DefaultRoomLifecycle(logger)

    override val status: RoomStatus
        get() = statusLifecycle.status

    override val error: ErrorInfo?
        get() = statusLifecycle.error

    override fun onStatusChange(listener: RoomLifecycle.Listener): Subscription = statusLifecycle.onChange(listener)

    override fun offAllStatusChange() {
        statusLifecycle.offAll()
    }

    override suspend fun attach() {
        messages.channel.attachCoroutine()
        typing.channel.attachCoroutine()
        reactions.channel.attachCoroutine()
    }

    override suspend fun detach() {
        messages.channel.detachCoroutine()
        typing.channel.detachCoroutine()
        reactions.channel.detachCoroutine()
    }

    fun release() {
        _messages.release()
        _typing.release()
        _occupancy.release()
    }
}
