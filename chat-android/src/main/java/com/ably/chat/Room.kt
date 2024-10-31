@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat
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

    override val status = DefaultStatus(roomScope, logger)

    override val messages = DefaultMessages(
        roomId = roomId,
        realtimeChannels = realtimeClient.channels,
        chatApi = chatApi,
    )

    override val presence = DefaultPresence(
        messages = messages,
    )

    override val typing = DefaultTyping(
        roomId = roomId,
        realtimeClient = realtimeClient,
    )

    override val reactions = DefaultRoomReactions(
        roomId = roomId,
        realtimeClient = realtimeClient,
    )

    override val occupancy = DefaultOccupancy(
        messages = messages,
    )

    private var _lifecycleManager: RoomLifecycleManager? = null

    init {
        /**
         * TODO
         * Initialize features based on provided RoomOptions.
         * By default, only messages feature should be initialized.
         * Currently, all features are initialized by default.
         */
        val features = listOf(messages, presence, typing, reactions, occupancy)
        _lifecycleManager = RoomLifecycleManager(roomScope, status, features, _logger)
        /**
         * TODO
         * Make sure previous release op. for same was a success.
         * Make sure channels were removed using realtime.channels.release(contributor.channel.name);
         * Once this is a success, set room to initialized, if not set it to failed and throw error.
         * Note that impl. can change based on recent proposed changes to chat-room-lifecycle DR.
         */
        this.status.setStatus(RoomLifecycle.Initialized)
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

    fun release() {
        messages.release()
    }
}
