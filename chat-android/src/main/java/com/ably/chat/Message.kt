package com.ably.chat

/**
 * {@link Headers} type for chat messages.
 */
typealias MessageHeaders = Headers

/**
 * {@link Metadata} type for chat messages.
 */
typealias MessageMetadata = Metadata

/**
 * Represents a single message in a chat room.
 */
data class Message(
    /**
     * The unique identifier of the message.
     */
    val timeserial: String,

    /**
     * The clientId of the user who created the message.
     */
    val clientId: String,

    /**
     * The roomId of the chat room to which the message belongs.
     */
    val roomId: String,

    /**
     * The text of the message.
     */
    val text: String,

    /**
     * The timestamp at which the message was created.
     */
    val createdAt: Long,

    /**
     * The metadata of a chat message. Allows for attaching extra info to a message,
     * which can be used for various features such as animations, effects, or simply
     * to link it to other resources such as images, relative points in time, etc.
     *
     * Metadata is part of the Ably Pub/sub message content and is not read by Ably.
     *
     * This value is always set. If there is no metadata, this is an empty object.
     *
     * Do not use metadata for authoritative information. There is no server-side
     * validation. When reading the metadata treat it like user input.
     */
    val metadata: MessageMetadata,

    /**
     * The headers of a chat message. Headers enable attaching extra info to a message,
     * which can be used for various features such as linking to a relative point in
     * time of a livestream video or flagging this message as important or pinned.
     *
     * Headers are part of the Ably realtime message extras.headers and they can be used
     * for Filtered Subscriptions and similar.
     *
     * This value is always set. If there are no headers, this is an empty object.
     *
     * Do not use the headers for authoritative information. There is no server-side
     * validation. When reading the headers treat them like user input.
     */
    val headers: MessageHeaders,
)
