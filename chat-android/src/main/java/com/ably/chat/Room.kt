@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import io.ably.lib.types.ErrorInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
     * @throws [ErrorInfo] if presence is not enabled for the room.
     * @returns The presence instance for the room.
     */
    val presence: Presence

    /**
     * Allows you to interact with room-level reactions.
     *
     * @throws [ErrorInfo] if reactions are not enabled for the room.
     * @returns The room reactions instance for the room.
     */
    val reactions: RoomReactions

    /**
     * Allows you to interact with typing events in the room.
     *
     * @throws [ErrorInfo] if typing is not enabled for the room.
     * @returns The typing instance for the room.
     */
    val typing: Typing

    /**
     * Allows you to interact with occupancy metrics for the room.
     *
     * @throws [ErrorInfo] if occupancy is not enabled for the room.
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
     * If a room fails to attach, it will enter either the [RoomLifecycle.Suspended] or [RoomLifecycle.Failed] state.
     *
     * If the room enters the failed state, then it will not automatically retry attaching and intervention is required.
     *
     * If the room enters the suspended state, then the call to attach will reject with the [ErrorInfo] that caused the suspension. However,
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
    internal val realtimeClient: RealtimeClient,
    internal val chatApi: ChatApi,
    internal val clientId: String,
    logger: Logger,
) : Room {
    internal val logger = logger.withContext("Room", mapOf("roomId" to roomId))

    /**
     * RoomScope is a crucial part of the Room lifecycle. It manages sequential and atomic operations.
     * Parallelism is intentionally limited to 1 to ensure that only one coroutine runs at a time,
     * preventing concurrency issues. Every operation within Room must be performed through this scope.
     */
    private val roomScope =
        CoroutineScope(Dispatchers.Default.limitedParallelism(1) + CoroutineName(roomId) + SupervisorJob())

    override val messages = DefaultMessages(room = this)

    private var _presence: Presence? = null
    override val presence: Presence
        get() {
            if (_presence == null) { // CHA-RC2b
                logger.error("Presence access failed, not enabled in provided RoomOptions: $options")
                throw clientError("Presence is not enabled for this room")
            }
            return _presence as Presence
        }

    private var _reactions: RoomReactions? = null
    override val reactions: RoomReactions
        get() {
            if (_reactions == null) { // CHA-RC2b
                logger.error("Reactions access failed, not enabled in provided RoomOptions: $options")
                throw clientError("Reactions are not enabled for this room")
            }
            return _reactions as RoomReactions
        }

    private var _typing: Typing? = null
    override val typing: Typing
        get() {
            if (_typing == null) { // CHA-RC2b
                logger.error("Typing access failed, not enabled in provided RoomOptions: $options")
                throw clientError("Typing is not enabled for this room")
            }
            return _typing as Typing
        }

    private var _occupancy: Occupancy? = null
    override val occupancy: Occupancy
        get() {
            if (_occupancy == null) { // CHA-RC2b
                logger.error("Occupancy access failed, not enabled in provided RoomOptions: $options")
                throw clientError("Occupancy is not enabled for this room")
            }
            return _occupancy as Occupancy
        }

    private val statusLifecycle = DefaultRoomLifecycle(this.logger)

    override val status: RoomStatus
        get() = statusLifecycle.status

    override val error: ErrorInfo?
        get() = statusLifecycle.error

    private var lifecycleManager: RoomLifecycleManager

    init {
        this.logger.debug("Initializing based on provided RoomOptions: $options")

        options.validateRoomOptions(this.logger) // CHA-RC2a

        // CHA-RC2e - Add contributors/features as per the order of precedence
        val roomFeatures = mutableListOf<ContributesToRoomLifecycle>(messages)

        options.presence?.let {
            val presenceContributor = DefaultPresence(room = this)
            roomFeatures.add(presenceContributor)
            _presence = presenceContributor
        }

        options.typing?.let {
            val typingContributor = DefaultTyping(room = this)
            roomFeatures.add(typingContributor)
            _typing = typingContributor
        }

        options.reactions?.let {
            val reactionsContributor = DefaultRoomReactions(room = this)
            roomFeatures.add(reactionsContributor)
            _reactions = reactionsContributor
        }

        options.occupancy?.let {
            val occupancyContributor = DefaultOccupancy(room = this)
            roomFeatures.add(occupancyContributor)
            _occupancy = occupancyContributor
        }

        lifecycleManager = RoomLifecycleManager(roomScope, statusLifecycle, roomFeatures, this.logger)

        this.logger.debug("Initialized with features: ${roomFeatures.map { it.featureName }.joinWithBrackets}")
    }

    override fun onStatusChange(listener: RoomLifecycle.Listener): Subscription =
        statusLifecycle.onChange(listener)

    override fun offAllStatusChange() {
        statusLifecycle.offAll()
    }

    override suspend fun attach() {
        logger.trace("attach();")
        lifecycleManager.attach()
    }

    override suspend fun detach() {
        logger.trace("detach();")
        lifecycleManager.detach()
    }

    /**
     * Releases the room, underlying channels are removed from the core SDK to prevent leakage.
     * This is an internal method and only called from Rooms interface implementation.
     */
    internal suspend fun release() {
        logger.trace("release();")
        lifecycleManager.release()
    }

    /**
     * Ensures that the room is attached before performing any realtime room operation.
     * Accepts featureLogger as a param, to log respective operation.
     * @throws roomInvalidStateException if room is not in ATTACHING/ATTACHED state.
     * Spec: CHA-RL9
     */
    internal suspend fun ensureAttached(featureLogger: Logger) {
        featureLogger.trace("ensureAttached();")
        // CHA-PR3e, CHA-PR10e, CHA-PR4d, CHA-PR6d, CHA-T2d, CHA-T4a1, CHA-T5e
        when (val currentRoomStatus = statusLifecycle.status) {
            RoomStatus.Attached -> {
                featureLogger.debug("ensureAttached(); Room is already attached")
                return
            }
            // CHA-PR3d, CHA-PR10d, CHA-PR4b, CHA-PR6c, CHA-T2c, CHA-T4a3, CHA-T5c
            RoomStatus.Attaching -> { // CHA-RL9
                featureLogger.debug("ensureAttached(); Room is in attaching state, waiting for attach to complete")
                val attachDeferred = CompletableDeferred<Unit>()
                roomScope.launch {
                    when (statusLifecycle.status) {
                        RoomStatus.Attached -> {
                            featureLogger.debug("ensureAttached(); waiting complete, room is now ATTACHED")
                            attachDeferred.complete(Unit)
                        }
                        RoomStatus.Attaching -> statusLifecycle.onChangeOnce {
                            if (it.current == RoomStatus.Attached) {
                                featureLogger.debug("ensureAttached(); waiting complete, room is now ATTACHED")
                                attachDeferred.complete(Unit)
                            } else {
                                featureLogger.error("ensureAttached(); waiting complete, room ATTACHING failed with error: ${it.error}")
                                val exception =
                                    roomInvalidStateException(roomId, statusLifecycle.status, HttpStatusCode.InternalServerError)
                                attachDeferred.completeExceptionally(exception)
                            }
                        }
                        else -> {
                            featureLogger.error(
                                "ensureAttached(); waiting complete, room ATTACHING failed with error: ${statusLifecycle.error}",
                            )
                            val exception = roomInvalidStateException(roomId, statusLifecycle.status, HttpStatusCode.InternalServerError)
                            attachDeferred.completeExceptionally(exception)
                        }
                    }
                }
                attachDeferred.await()
                return
            }
            // CHA-PR3h, CHA-PR10h, CHA-PR4c, CHA-PR6h, CHA-T2g, CHA-T4a4, CHA-T5d
            else -> {
                featureLogger.error("ensureAttached(); Room is in invalid state: $currentRoomStatus, error: ${statusLifecycle.error}")
                throw roomInvalidStateException(roomId, currentRoomStatus, HttpStatusCode.BadRequest)
            }
        }
    }
}
