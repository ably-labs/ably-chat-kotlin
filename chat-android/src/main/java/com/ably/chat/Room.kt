@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import io.ably.lib.types.ErrorInfo
import io.ably.lib.util.Log.LogHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

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

    /**
     * Releases the room, underlying channels are removed from the core SDK to prevent leakage.
     */
    suspend fun release()
}

internal class DefaultRoom(
    override val roomId: String,
    override val options: RoomOptions,
    val realtimeClient: RealtimeClient,
    chatApi: ChatApi,
    val logger: LogHandler?,
) : Room {

    private val _logger = logger

    /**
     * RoomScope is a crucial part of the Room lifecycle. It manages sequential and atomic operations.
     * Parallelism is intentionally limited to 1 to ensure that only one coroutine runs at a time,
     * preventing concurrency issues. Every operation within Room must be performed through this scope.
     */
    private val roomScope =
        CoroutineScope(Dispatchers.Default.limitedParallelism(1) + CoroutineName(roomId))

    private val clientId get() = realtimeClient.auth.clientId

    private val _messages = DefaultMessages(
        roomId = roomId,
        realtimeChannels = realtimeClient.channels,
        chatApi = chatApi,
    )

    override val messages: Messages = _messages

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

    override val typing: Typing = DefaultTyping(
        roomId = roomId,
        realtimeClient = realtimeClient,
    )

    override val occupancy: Occupancy = DefaultOccupancy(
        messages = messages,
    )
    private var _lifecycleManager: RoomLifecycleManager? = null

    private val _statusLifecycle = DefaultRoomLifecycle(logger)
    internal val statusLifecycle: DefaultRoomLifecycle
        get() = _statusLifecycle

    override val status: RoomStatus
        get() = _statusLifecycle.status

    override val error: ErrorInfo?
        get() = _statusLifecycle.error

    init {
        /**
         * TODO
         * Initialize features based on provided RoomOptions.
         * By default, only messages feature should be initialized.
         * Currently, all features are initialized by default.
         */
        val features = listOf(messages, presence, typing, reactions, occupancy)
        _lifecycleManager = RoomLifecycleManager(roomScope, _statusLifecycle, features, _logger)
        /**
         * TODO
         * Make sure previous release op. for same was a success.
         * Make sure channels were removed using realtime.channels.release(contributor.channel.name);
         * Once this is a success, set room to initialized, if not set it to failed and throw error.
         * Note that impl. can change based on recent proposed changes to chat-room-lifecycle DR.
         */
        this._statusLifecycle.setStatus(RoomStatus.Initialized)
    }

    override fun onStatusChange(listener: RoomLifecycle.Listener): Subscription = _statusLifecycle.onChange(listener)

    override fun offAllStatusChange() {
        _statusLifecycle.offAll()
    }

    override suspend fun attach() {
        if (_lifecycleManager == null) {
            // TODO - wait for room to be initialized inside init
        }
        _lifecycleManager?.attach()
    }

    override suspend fun detach() {
        messages.channel.detachCoroutine()
        typing.channel.detachCoroutine()
        reactions.channel.detachCoroutine()
    }

    override suspend fun release() {
        _lifecycleManager?.release()
    }
}
