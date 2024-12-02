@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import com.ably.chat.QueryOptions.ResultOrder.NewestFirst
import com.google.gson.JsonObject
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.ChannelState
import io.ably.lib.realtime.ChannelStateListener
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.MessageAction
import io.ably.lib.realtime.Channel as AblyRealtimeChannel

typealias PubSubMessageListener = AblyRealtimeChannel.MessageListener
typealias PubSubMessage = io.ably.lib.types.Message

/**
 * This interface is used to interact with messages in a chat room: subscribing
 * to new messages, fetching history, or sending messages.
 *
 * Get an instance via [Room.messages].
 */
interface Messages : EmitsDiscontinuities {
    /**
     * Get the underlying Ably realtime channel used for the messages in this chat room.
     *
     * @returns the realtime channel
     */
    val channel: AblyRealtimeChannel

    /**
     * Subscribe to new messages in this chat room.
     * @param listener callback that will be called
     * @return A response object that allows you to control the subscription.
     */
    fun subscribe(listener: Listener): MessagesSubscription

    /**
     * Get messages that have been previously sent to the chat room, based on the provided options.
     *
     * @param start The start of the time window to query from. See [QueryOptions.start]
     * @param end The end of the time window to query from. See [QueryOptions.end]
     * @param limit The maximum number of messages to return in the response. See [QueryOptions.limit]
     * @param orderBy The order of messages in the query result. See [QueryOptions.orderBy]
     *
     * @return Paginated result of messages. This paginated result can be used to fetch more messages if available.
     */
    suspend fun get(
        start: Long? = null,
        end: Long? = null,
        limit: Int = 100,
        orderBy: QueryOptions.ResultOrder = NewestFirst,
    ): PaginatedResult<Message>

    /**
     * Send a message in the chat room.
     *
     * This method uses the Ably Chat API endpoint for sending messages.
     *
     * Note: that the suspending function may resolve before OR after the message is received
     * from the realtime channel. This means you may see the message that was just
     * sent in a callback to `subscribe` before the function resolves.
     *
     * @param text The text of the message. See [SendMessageParams.text]
     * @param metadata Optional metadata of the message. See [SendMessageParams.metadata]
     * @param headers Optional headers of the message. See [SendMessageParams.headers]
     *
     * @return The message was published.
     */
    suspend fun send(text: String, metadata: MessageMetadata? = null, headers: MessageHeaders? = null): Message

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
     * The order of messages in the query result.
     */
    val orderBy: ResultOrder = NewestFirst,
) {
    /**
     * Represents direction to query messages in.
     */
    enum class ResultOrder {
        /**
         * The response will include messages from the start of the time window to the end.
         */
        NewestFirst,

        /**
         * the response will include messages from the end of the time window to the start.
         */
        OldestFirst,
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

interface MessagesSubscription : Subscription {
    /**
     * (CHA-M5j)
     * Get the previous messages that were sent to the room before the listener was subscribed.
     * @return paginated result of messages, in newest-to-oldest order.
     */
    suspend fun getPreviousMessages(start: Long? = null, end: Long? = null, limit: Int = 100): PaginatedResult<Message>
}

internal class DefaultMessagesSubscription(
    private val chatApi: ChatApi,
    private val roomId: String,
    private val subscription: Subscription,
    internal val fromSerialProvider: () -> DeferredValue<String>,
) : MessagesSubscription {
    override fun unsubscribe() {
        subscription.unsubscribe()
    }

    override suspend fun getPreviousMessages(start: Long?, end: Long?, limit: Int): PaginatedResult<Message> {
        val fromSerial = fromSerialProvider().await()
        val queryOptions = QueryOptions(start = start, end = end, limit = limit, orderBy = NewestFirst)
        return chatApi.getMessages(
            roomId = roomId,
            options = queryOptions,
            fromSerial = fromSerial,
        )
    }
}

internal class DefaultMessages(
    private val roomId: String,
    private val realtimeChannels: AblyRealtime.Channels,
    private val chatApi: ChatApi,
    private val logger: Logger,
) : Messages, ContributesToRoomLifecycleImpl(logger) {

    override val featureName: String = "messages"

    private var listeners: Map<Messages.Listener, DeferredValue<String>> = emptyMap()

    private var channelStateListener: ChannelStateListener

    private var lock = Any()

    /**
     * (CHA-M1)
     * the channel name for the chat messages channel.
     */
    private val messagesChannelName = "$roomId::\$chat::\$chatMessages"

    override val channel = realtimeChannels.get(messagesChannelName, ChatChannelOptions())

    override val attachmentErrorCode: ErrorCode = ErrorCode.MessagesAttachmentFailed

    override val detachmentErrorCode: ErrorCode = ErrorCode.MessagesDetachmentFailed

    init {
        channelStateListener = ChannelStateListener {
            if (it.current == ChannelState.attached && !it.resumed) updateChannelSerialsAfterDiscontinuity()
        }
        channel.on(channelStateListener)
    }

    override fun subscribe(listener: Messages.Listener): MessagesSubscription {
        val deferredChannelSerial = DeferredValue<String>()
        addListener(listener, deferredChannelSerial)
        val messageListener = PubSubMessageListener {
            val pubSubMessage = it ?: throw AblyException.fromErrorInfo(
                ErrorInfo("Got empty pubsub channel message", HttpStatusCode.BadRequest, ErrorCode.BadRequest.code),
            )

            // Ignore any action that is not message.create
            if (pubSubMessage.action != MessageAction.MESSAGE_CREATE) return@PubSubMessageListener

            val data = parsePubSubMessageData(pubSubMessage.data)
            val chatMessage = Message(
                roomId = roomId,
                createdAt = pubSubMessage.createdAt,
                clientId = pubSubMessage.clientId,
                serial = pubSubMessage.serial,
                text = data.text,
                metadata = data.metadata,
                headers = pubSubMessage.extras.asJsonObject().get("headers")?.toMap() ?: mapOf(),
                latestAction = MessageAction.MESSAGE_CREATE,
            )
            listener.onEvent(MessageEvent(type = MessageEventType.Created, message = chatMessage))
        }
        // (CHA-M4d)
        channel.subscribe(PubSubMessageNames.ChatMessage, messageListener)
        // (CHA-M5) setting subscription point
        associateWithCurrentChannelSerial(deferredChannelSerial)

        return DefaultMessagesSubscription(
            chatApi = chatApi,
            roomId = roomId,
            subscription = {
                removeListener(listener)
                channel.unsubscribe(PubSubMessageNames.ChatMessage, messageListener)
            },
            fromSerialProvider = {
                listeners[listener] ?: throw AblyException.fromErrorInfo(
                    ErrorInfo(
                        "This messages subscription instance was already unsubscribed",
                        HttpStatusCode.BadRequest,
                        ErrorCode.BadRequest.code,
                    ),
                )
            },
        )
    }

    override suspend fun get(start: Long?, end: Long?, limit: Int, orderBy: QueryOptions.ResultOrder): PaginatedResult<Message> =
        chatApi.getMessages(
            roomId,
            QueryOptions(start, end, limit, orderBy),
        )

    override suspend fun send(text: String, metadata: MessageMetadata?, headers: MessageHeaders?): Message = chatApi.sendMessage(
        roomId,
        SendMessageParams(text, metadata, headers),
    )

    /**
     * Associate deferred channel serial value with the current channel's serial
     *
     * WARN: it not deterministic because of race condition,
     * this can lead to duplicated messages in `getPreviousMessages` calls
     */
    private fun associateWithCurrentChannelSerial(channelSerialProvider: DeferredValue<String>) {
        if (channel.state === ChannelState.attached) {
            channelSerialProvider.completeWith(requireChannelSerial())
            return
        }

        channel.once(ChannelState.attached) {
            channelSerialProvider.completeWith(requireAttachSerial())
        }
    }

    private fun requireChannelSerial(): String {
        return channel.properties.channelSerial
            ?: throw AblyException.fromErrorInfo(
                ErrorInfo(
                    "Channel has been attached, but channelSerial is not defined",
                    HttpStatusCode.BadRequest,
                    ErrorCode.BadRequest.code,
                ),
            )
    }

    private fun requireAttachSerial(): String {
        return channel.properties.attachSerial
            ?: throw AblyException.fromErrorInfo(
                ErrorInfo(
                    "Channel has been attached, but attachSerial is not defined",
                    HttpStatusCode.BadRequest,
                    ErrorCode.BadRequest.code,
                ),
            )
    }

    private fun addListener(listener: Messages.Listener, deferredChannelSerial: DeferredValue<String>) {
        synchronized(lock) {
            listeners += listener to deferredChannelSerial
        }
    }

    private fun removeListener(listener: Messages.Listener) {
        synchronized(lock) {
            listeners -= listener
        }
    }

    /**
     * (CHA-M5c), (CHA-M5d)
     */
    private fun updateChannelSerialsAfterDiscontinuity() {
        val deferredChannelSerial = DeferredValue<String>()
        deferredChannelSerial.completeWith(requireAttachSerial())

        synchronized(lock) {
            listeners = listeners.mapValues {
                if (it.value.completed) deferredChannelSerial else it.value
            }
        }
    }

    override fun release() {
        channel.off(channelStateListener)
        realtimeChannels.release(channel.name)
    }
}

/**
 * Parsed data from the Pub/Sub channel's message data field
 */
private data class PubSubMessageData(val text: String, val metadata: MessageMetadata?)

private fun parsePubSubMessageData(data: Any): PubSubMessageData {
    if (data !is JsonObject) {
        throw AblyException.fromErrorInfo(
            ErrorInfo("Unrecognized Pub/Sub channel's message for `Message.created` event", HttpStatusCode.InternalServerError),
        )
    }
    return PubSubMessageData(
        text = data.requireString("text"),
        metadata = data.get("metadata"),
    )
}
