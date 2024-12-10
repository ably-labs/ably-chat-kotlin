@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import com.google.gson.JsonObject
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.MessageExtras
import io.ably.lib.realtime.Channel as AblyRealtimeChannel

/**
 * This interface is used to interact with room-level reactions in a chat room: subscribing to reactions and sending them.
 *
 * Get an instance via [Room.reactions].
 */
interface RoomReactions : EmitsDiscontinuities {
    /**
     * Returns an instance of the Ably realtime channel used for room-level reactions.
     * Avoid using this directly unless special features that cannot otherwise be implemented are needed.
     *
     * @return The Ably realtime channel instance.
     */
    val channel: AblyRealtimeChannel

    /**
     * Sends a reaction to the specified room along with optional metadata.
     *
     * This method allows you to send a reaction at the room level.
     * It accepts parameters for defining the type of reaction, metadata, and additional headers.
     *
     * @param type The type of the reaction. See [SendReactionParams.type].
     * @param metadata Optional metadata to include with the reaction. Defaults to `null`. See [SendReactionParams.metadata]
     * @param headers Additional headers to include with the reaction. Defaults to an empty map. See [SendReactionParams.headers]
     *
     * @return Unit when the reaction has been successfully sent. Note that it is
     * possible to receive your own reaction via the reactions listener before
     * this method completes.
     */
    suspend fun send(type: String, metadata: ReactionMetadata? = null, headers: ReactionHeaders? = null)

    /**
     * Subscribe to receive room-level reactions.
     *
     * @param listener The listener function to be called when a reaction is received.
     * @returns A response object that allows you to control the subscription.
     */
    fun subscribe(listener: Listener): Subscription

    /**
     * An interface for listening to new reaction events
     */
    fun interface Listener {
        /**
         * A function that can be called when the new reaction happens.
         * @param event The event that happened.
         */
        fun onReaction(event: Reaction)
    }
}

/**
 * Params for sending a room-level reactions. Only `type` is mandatory.
 */
internal data class SendReactionParams(
    /**
     * The type of the reaction, for example an emoji or a short string such as
     * "like".
     *
     * It is the only mandatory parameter to send a room-level reaction.
     */
    val type: String,

    /**
     * Optional metadata of the reaction.
     *
     * The metadata is a map of extra information that can be attached to the
     * room reaction. It is not used by Ably and is sent as part of the realtime
     * message payload. Example use cases are custom animations or other effects.
     *
     * Do not use metadata for authoritative information. There is no server-side
     * validation. When reading the metadata treat it like user input.
     *
     * The key `ably-chat` is reserved and cannot be used. Ably may populate this
     * with different values in the future.
     */
    val metadata: ReactionMetadata? = null,

    /**
     * Optional headers of the room reaction.
     *
     * The headers are a flat key-value map and are sent as part of the realtime
     * message's `extras` inside the `headers` property. They can serve similar
     * purposes as the metadata but they are read by Ably and can be used for
     * features such as
     * [subscription filters](https://faqs.ably.com/subscription-filters).
     *
     * Do not use the headers for authoritative information. There is no
     * server-side validation. When reading the headers treat them like user
     * input.
     *
     * The key prefix `ably-chat` is reserved and cannot be used. Ably may add
     * headers prefixed with `ably-chat` in the future.
     */
    val headers: ReactionHeaders? = null,
)

internal class DefaultRoomReactions(
    private val room: DefaultRoom,
) : RoomReactions, ContributesToRoomLifecycleImpl(room.roomLogger) {

    override val featureName = "reactions"

    private val roomReactionsChannelName = "${room.roomId}::\$chat::\$reactions"

    override val channel: AblyRealtimeChannel = room.realtimeClient.channels.get(roomReactionsChannelName, ChatChannelOptions()) // CHA-RC2f

    override val attachmentErrorCode: ErrorCode = ErrorCode.ReactionsAttachmentFailed

    override val detachmentErrorCode: ErrorCode = ErrorCode.ReactionsDetachmentFailed

    private val logger = room.roomLogger.withContext(tag = "Reactions")

    // (CHA-ER3) Ephemeral room reactions are sent to Ably via the Realtime connection via a send method.
    // (CHA-ER3a) Reactions are sent on the channel using a message in a particular format - see spec for format.
    override suspend fun send(type: String, metadata: ReactionMetadata?, headers: ReactionHeaders?) {
        val pubSubMessage = PubSubMessage().apply {
            name = RoomReactionEventType.Reaction.eventName
            data = JsonObject().apply {
                addProperty("type", type)
                metadata?.let { add("metadata", it) }
            }
            headers?.let {
                extras = MessageExtras(
                    JsonObject().apply {
                        add("headers", it.toJson())
                    },
                )
            }
        }
        room.ensureAttached() // TODO - This check might be removed in the future due to core spec change
        channel.publishCoroutine(pubSubMessage)
    }

    override fun subscribe(listener: RoomReactions.Listener): Subscription {
        val messageListener = PubSubMessageListener {
            val pubSubMessage = it ?: throw AblyException.fromErrorInfo(
                ErrorInfo("Got empty pubsub channel message", HttpStatusCode.BadRequest, ErrorCode.BadRequest.code),
            )
            val data = pubSubMessage.data as? JsonObject ?: throw AblyException.fromErrorInfo(
                ErrorInfo("Unrecognized Pub/Sub channel's message for `roomReaction` event", HttpStatusCode.InternalServerError),
            )
            val reaction = Reaction(
                type = data.requireString("type"),
                createdAt = pubSubMessage.timestamp,
                clientId = pubSubMessage.clientId,
                metadata = data.get("metadata"),
                headers = pubSubMessage.extras?.asJsonObject()?.get("headers")?.toMap() ?: mapOf(),
                isSelf = pubSubMessage.clientId == room.clientId,
            )
            listener.onReaction(reaction)
        }
        channel.subscribe(RoomReactionEventType.Reaction.eventName, messageListener)
        return Subscription { channel.unsubscribe(RoomReactionEventType.Reaction.eventName, messageListener) }
    }

    override fun release() {
        room.realtimeClient.channels.release(channel.name)
    }
}
