@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

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
     * (CHA-RS2)
     * Returns an object that can be used to observe the status of the room.
     *
     * @returns The status observable.
     */
    val status: RoomStatus

    /**
     * Returns the room options.
     *
     * @returns A copy of the options used to create the room.
     */
    val options: RoomOptions

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
    realtimeClient: RealtimeClient,
    chatApi: ChatApi,
) : Room {

    private val _messages = DefaultMessages(
        roomId = roomId,
        realtimeChannels = realtimeClient.channels,
        chatApi = chatApi,
    )

    override val messages: Messages = _messages

    override val presence: Presence = DefaultPresence(
        messages = messages,
    )

    override val reactions: RoomReactions = DefaultRoomReactions(
        roomId = roomId,
        realtimeClient = realtimeClient,
    )

    override val typing: Typing = DefaultTyping(
        roomId = roomId,
        realtimeClient = realtimeClient,
    )

    override val occupancy: Occupancy = DefaultOccupancy(
        messages = messages,
    )

    override val status: RoomStatus
        get() {
            TODO("Not yet implemented")
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
    }
}
