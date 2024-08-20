package com.ably.chat

import io.ably.lib.realtime.Channel
import io.ably.lib.types.PaginatedResult

/**
 * This interface is used to interact with messages in a chat room: subscribing
 * to new messages, fetching history, or sending messages.
 *
 * Get an instance via {@link Room.messages}.
 */
interface Messages : EmitsDiscontinuities {
    /**
     * Get the underlying Ably realtime channel used for the messages in this chat room.
     *
     * @returns the realtime channel
     */
    val channel: Channel

    /**
     * Subscribe to new messages in this chat room.
     * @param listener callback that will be called
     * @returns A response object that allows you to control the subscription.
     */
    fun subscribe(listener: Listener)

    /**
     * Unsubscribe listener
     * @param listener callback that will be unsubscribed
     */
    fun unsubscribe(listener: Listener)

    /**
     * Get messages that have been previously sent to the chat room, based on the provided options.
     *
     * @param options Options for the query.
     * @returns A promise that resolves with the paginated result of messages. This paginated result can
     * be used to fetch more messages if available.
     */
    suspend fun get(options: QueryOptions): PaginatedResult<Message>

    /**
     * Send a message in the chat room.
     *
     * This method uses the Ably Chat API endpoint for sending messages.
     *
     * Note that the Promise may resolve before OR after the message is received
     * from the realtime channel. This means you may see the message that was just
     * sent in a callback to `subscribe` before the returned promise resolves.
     *
     * @param params an object containing {text, headers, metadata} for the message
     * to be sent. Text is required, metadata and headers are optional.
     * @returns A promise that resolves when the message was published.
     */
    fun send(params: SendMessageParams): Message

    /**
     * An interface for listening to new messaging event
     */
    fun interface Listener {
        /**
         * A function that can be called when the new messaging event happens.
         * @param event The event that happened.
         */
        fun onEvent(event: MessageEvent)
    }
}

/**
 * Options for querying messages in a chat room.
 */
data class QueryOptions(
    /**
     * The start of the time window to query from. If provided, the response will include
     * messages with timestamps equal to or greater than this value.
     *
     * @defaultValue The beginning of time
     */
    val start: Long? = null,

    /**
     * The end of the time window to query from. If provided, the response will include
     * messages with timestamps less than this value.
     *
     * @defaultValue Now
     */
    val end: Long? = null,

    /**
     * The maximum number of messages to return in the response.
     */
    val limit: Int = 100,

    /**
     * The direction to query messages in.
     */
    val direction: Direction = Direction.FORWARDS,
) {
    /**
     * Represents direction to query messages in.
     */
    enum class Direction {
        /**
         * The response will include messages from the start of the time window to the end.
         */
        FORWARDS,

        /**
         * the response will include messages from the end of the time window to the start.
         */
        BACKWARDS,
    }
}

/**
 * Payload for a message event.
 */
data class MessageEvent(
    /**
     * The type of the message event.
     */
    val type: MessageEventType,

    /**
     * The message that was received.
     */
    val message: Message,
)

/**
 * Params for sending a text message. Only `text` is mandatory.
 */
data class SendMessageParams(
    /**
     * The text of the message.
     */
    val text: String,

    /**
     * Optional metadata of the message.
     *
     * The metadata is a map of extra information that can be attached to chat
     * messages. It is not used by Ably and is sent as part of the realtime
     * message payload. Example use cases are setting custom styling like
     * background or text colors or fonts, adding links to external images,
     * emojis, etc.
     *
     * Do not use metadata for authoritative information. There is no server-side
     * validation. When reading the metadata treat it like user input.
     *
     * The key `ably-chat` is reserved and cannot be used. Ably may populate
     * this with different values in the future.
     */
    val metadata: MessageMetadata? = null,

    /**
     * Optional headers of the message.
     *
     * The headers are a flat key-value map and are sent as part of the realtime
     * message's extras inside the `headers` property. They can serve similar
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
    val headers: MessageHeaders? = null,
)
