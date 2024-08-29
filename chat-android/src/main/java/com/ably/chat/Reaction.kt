package com.ably.chat

/**
 * {@link Headers} type for chat messages.
 */
typealias ReactionHeaders = Headers

/**
 * {@link Metadata} type for chat messages.
 */
typealias ReactionMetadata = Metadata

/**
 * Represents a room-level reaction.
 */
data class Reaction(
    /**
     * The type of the reaction, for example "like" or "love".
     */
    val type: String,

    /**
     * Metadata of the reaction. If no metadata was set this is an empty object.
     */
    val metadata: ReactionMetadata = mapOf(),

    /**
     * Headers of the reaction. If no headers were set this is an empty object.
     */
    val headers: ReactionHeaders = mapOf(),

    /**
     * The timestamp at which the reaction was sent.
     */
    val createdAt: Long,

    /**
     * The clientId of the user who sent the reaction.
     */
    val clientId: String,

    /**
     * Whether the reaction was sent by the current user.
     */
    val isSelf: Boolean,
)
