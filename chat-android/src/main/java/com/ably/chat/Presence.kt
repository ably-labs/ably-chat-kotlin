@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import android.text.PrecomputedText.Params
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.PresenceMessage
import io.ably.lib.realtime.Channel as AblyRealtimeChannel

typealias PresenceData = Any

/**
 * This interface is used to interact with presence in a chat room: subscribing to presence events,
 * fetching presence members, or sending presence events (join,update,leave).
 *
 * Get an instance via {@link Room.presence}.
 */
interface Presence : EmitsDiscontinuities {
    /**
     * Get the underlying Ably realtime channel used for presence in this chat room.
     * @returns The realtime channel.
     */
    val channel: AblyRealtimeChannel

    /**
     * Method to get list of the current online users and returns the latest presence messages associated to it.
     * @param {Ably.RealtimePresenceParams} params - Parameters that control how the presence set is retrieved.
     * @returns {Promise<PresenceMessage[]>} or upon failure, the promise will be rejected with an [[Ably.ErrorInfo]] object which explains the error.
     */
    suspend fun get(params: List<Params>): List<PresenceMember>

    /**
     * Method to check if user with supplied clientId is online
     * @param {string} clientId - The client ID to check if it is present in the room.
     * @returns true if user with specified clientId is present, false otherwise
     */
    suspend fun isUserPresent(clientId: String): Boolean

    /**
     * Method to join room presence, will emit an enter event to all subscribers. Repeat calls will trigger more enter events.
     * @param {PresenceData} data - The users data, a JSON serializable object that will be sent to all subscribers.
     * @returns {Promise<void>} or upon failure, the promise will be rejected with an {@link ErrorInfo} object which explains the error.
     */
    suspend fun enter(data: PresenceData?)

    /**
     * Method to update room presence, will emit an update event to all subscribers. If the user is not present, it will be treated as a join event.
     * @param {PresenceData} data - The users data, a JSON serializable object that will be sent to all subscribers.
     * @returns {Promise<void>} or upon failure, the promise will be rejected with an {@link ErrorInfo} object which explains the error.
     */
    suspend fun update(data: PresenceData?)

    /**
     * Method to leave room presence, will emit a leave event to all subscribers. If the user is not present, it will be treated as a no-op.
     * @param {PresenceData} data - The users data, a JSON serializable object that will be sent to all subscribers.
     * @returns {Promise<void>} or upon failure, the promise will be rejected with an {@link ErrorInfo} object which explains the error.
     */
    suspend fun leave(data: PresenceData?)

    /**
     * Subscribe the given listener to all presence events.
     * @param listener listener to subscribe
     */
    fun subscribe(listener: Listener): Subscription

    /**
     * An interface for listening to new presence event
     */
    fun interface Listener {
        /**
         * A function that can be called when the new presence event happens.
         * @param event The event that happened.
         */
        fun onEvent(event: PresenceEvent)
    }
}

/**
 * Type for PresenceMember
 */
data class PresenceMember(
    /**
     * The clientId of the presence member.
     */
    val clientId: String,

    /**
     * The data associated with the presence member.
     */
    val data: PresenceData,

    /**
     * The current state of the presence member.
     */
    val action: PresenceMessage.Action,

    /**
     * The timestamp of when the last change in state occurred for this presence member.
     */
    val updatedAt: Long,

    /**
     * The extras associated with the presence member.
     */
    val extras: Map<String, String>? = null,
)

/**
 * Type for PresenceEvent
 */
data class PresenceEvent(
    /**
     * The type of the presence event.
     */
    val action: PresenceMessage.Action,

    /**
     * The clientId of the client that triggered the presence event.
     */
    val clientId: String,

    /**
     * The timestamp of the presence event.
     */
    val timestamp: Int,

    /**
     * The data associated with the presence event.
     */
    val data: PresenceData,
)

internal class DefaultPresence(
    private val messages: Messages,
) : Presence, ContributesToRoomLifecycle, ResolvedContributor {

    override val featureName = "presence"

    override val channel = messages.channel

    override val contributor: ContributesToRoomLifecycle = this

    override val attachmentErrorCode: ErrorCodes = ErrorCodes.PresenceAttachmentFailed

    override val detachmentErrorCode: ErrorCodes = ErrorCodes.PresenceDetachmentFailed

    override suspend fun get(params: List<Params>): List<PresenceMember> {
        TODO("Not yet implemented")
    }

    override suspend fun isUserPresent(clientId: String): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun enter(data: PresenceData?) {
        TODO("Not yet implemented")
    }

    override suspend fun update(data: PresenceData?) {
        TODO("Not yet implemented")
    }

    override suspend fun leave(data: PresenceData?) {
        TODO("Not yet implemented")
    }

    override fun subscribe(listener: Presence.Listener): Subscription {
        TODO("Not yet implemented")
    }

    private val discontinuityEmitter = DiscontinuityEmitter()

    override fun onDiscontinuity(listener: EmitsDiscontinuities.Listener): Subscription {
        discontinuityEmitter.on(listener)
        return Subscription {
            discontinuityEmitter.off(listener)
        }
    }

    override fun discontinuityDetected(reason: ErrorInfo?) {
        discontinuityEmitter.emit("discontinuity", reason)
    }
}
