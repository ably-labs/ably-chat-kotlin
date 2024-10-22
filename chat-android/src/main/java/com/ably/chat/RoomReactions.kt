@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import io.ably.lib.realtime.Channel as AblyRealtimeChannel

/**
 * This interface is used to interact with room-level reactions in a chat room: subscribing to reactions and sending them.
 *
 * Get an instance via {@link Room.reactions}.
 */
interface RoomReactions : EmitsDiscontinuities {
    /**
     * Returns an instance of the Ably realtime channel used for room-level reactions.
     * Avoid using this directly unless special features that cannot otherwise be implemented are needed.
     *
     * @returns The Ably realtime channel instance.
     */
    val channel: AblyRealtimeChannel

    /**
     * Send a reaction to the room including some metadata.
     *
     * This method accepts parameters for a room-level reaction. It accepts an object
     *
     *
     * @param params an object containing {type, headers, metadata} for the room
     * reaction to be sent. Type is required, metadata and headers are optional.
     * @returns The returned promise resolves when the reaction was sent. Note
     * that it is possible to receive your own reaction via the reactions
     * listener before this promise resolves.
     */
    suspend fun send(params: SendReactionParams)

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
data class SendReactionParams(
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
    roomId: String,
    private val realtimeClient: RealtimeClient,
) : RoomReactions, ContributesToRoomLifecycle, ResolvedContributor {

    private val roomReactionsChannelName = "$roomId::\$chat::\$reactions"

    override val channel: AblyRealtimeChannel = realtimeClient.channels.get(roomReactionsChannelName, ChatChannelOptions())

    override val contributor: ContributesToRoomLifecycle = this

    override val attachmentErrorCode: ErrorCodes = ErrorCodes.ReactionsAttachmentFailed

    override val detachmentErrorCode: ErrorCodes = ErrorCodes.ReactionsDetachmentFailed

    override suspend fun send(params: SendReactionParams) {
        TODO("Not yet implemented")
    }

    override fun subscribe(listener: RoomReactions.Listener): Subscription {
        TODO("Not yet implemented")
    }

    override fun onDiscontinuity(listener: EmitsDiscontinuities.Listener): Subscription {
        TODO("Not yet implemented")
    }
}
