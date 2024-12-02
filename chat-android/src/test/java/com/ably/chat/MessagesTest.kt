package com.ably.chat

import com.ably.chat.room.createMockChannel
import com.ably.chat.room.createMockChatApi
import com.ably.chat.room.createMockRealtimeClient
import com.ably.chat.room.createMockRoom
import com.google.gson.JsonObject
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.ChannelBase
import io.ably.lib.realtime.ChannelState
import io.ably.lib.realtime.ChannelStateListener
import io.ably.lib.realtime.buildChannelStateChange
import io.ably.lib.types.AblyException
import io.ably.lib.types.MessageAction
import io.ably.lib.types.MessageExtras
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.lang.reflect.Field
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class MessagesTest {

    private val realtimeClient = createMockRealtimeClient()
    private val realtimeChannel = realtimeClient.createMockChannel()

    private lateinit var messages: DefaultMessages
    private val channelStateListenerSlot = slot<ChannelStateListener>()

    @Before
    fun setUp() {
        every { realtimeClient.channels.get(any(), any()) } returns realtimeChannel
        every { realtimeChannel.on(capture(channelStateListenerSlot)) } answers {
            println("Channel state listener registered")
        }

        val chatApi = createMockChatApi(realtimeClient)
        val room = createMockRoom("room1", realtimeClient = realtimeClient, chatApi = chatApi)

        messages = DefaultMessages(room)
    }

    /**
     * @spec CHA-M3a
     */
    @Test
    fun `should be able to send message and get it back from response`() = runTest {
        mockSendMessageApiResponse(
            realtimeClient,
            JsonObject().apply {
                addProperty("serial", "abcdefghij@1672531200000-123")
                addProperty("createdAt", 1_000_000)
            },
            roomId = "room1",
        )

        val sentMessage = messages.send(
            text = "lala",
            headers = mapOf("foo" to "bar"),
            metadata = JsonObject().apply { addProperty("meta", "data") },
        )

        assertEquals(
            Message(
                serial = "abcdefghij@1672531200000-123",
                clientId = "clientId",
                roomId = "room1",
                text = "lala",
                createdAt = 1_000_000,
                metadata = JsonObject().apply { addProperty("meta", "data") },
                headers = mapOf("foo" to "bar"),
                latestAction = MessageAction.MESSAGE_CREATE,
            ),
            sentMessage,
        )
    }

    /**
     * @spec CHA-M4a
     */
    @Test
    fun `should be able to subscribe to incoming messages`() = runTest {
        val pubSubMessageListenerSlot = slot<PubSubMessageListener>()

        every { realtimeChannel.subscribe("chat.message", capture(pubSubMessageListenerSlot)) } answers {
            println("Pub/Sub message listener registered")
        }

        val deferredValue = DeferredValue<MessageEvent>()

        messages.subscribe {
            deferredValue.completeWith(it)
        }

        verify { realtimeChannel.subscribe("chat.message", any()) }

        pubSubMessageListenerSlot.captured.onMessage(
            PubSubMessage().apply {
                data = JsonObject().apply {
                    addProperty("text", "some text")
                }
                serial = "abcdefghij@1672531200000-123"
                clientId = "clientId"
                timestamp = 1000L
                createdAt = 1000L
                extras = MessageExtras(
                    JsonObject().apply {
                        add(
                            "headers",
                            JsonObject().apply {
                                addProperty("foo", "bar")
                            },
                        )
                    },
                )
                action = MessageAction.MESSAGE_CREATE
            },
        )

        val messageEvent = deferredValue.await()

        assertEquals(MessageEventType.Created, messageEvent.type)
        assertEquals(
            Message(
                roomId = "room1",
                createdAt = 1000L,
                clientId = "clientId",
                serial = "abcdefghij@1672531200000-123",
                text = "some text",
                metadata = null,
                headers = mapOf("foo" to "bar"),
                latestAction = MessageAction.MESSAGE_CREATE,
            ),
            messageEvent.message,
        )
    }

    /**
     * @nospec
     */
    @Test
    fun `should throw an exception for listener history if not subscribed`() = runTest {
        val subscription = messages.subscribe {}

        subscription.unsubscribe()

        val exception = assertThrows(AblyException::class.java) {
            runBlocking { subscription.getPreviousMessages() }
        }

        assertEquals(40_000, exception.errorInfo.code)
    }

    /**
     * @spec CHA-M5a
     */
    @Test
    fun `every subscription should have own channel serial`() = runTest {
        messages.channel.properties.channelSerial = "channel-serial-1"
        messages.channel.state = ChannelState.attached

        val subscription1 = (messages.subscribe {}) as DefaultMessagesSubscription
        assertEquals("channel-serial-1", subscription1.fromSerialProvider().await())

        messages.channel.properties.channelSerial = "channel-serial-2"
        val subscription2 = (messages.subscribe {}) as DefaultMessagesSubscription

        assertEquals("channel-serial-2", subscription2.fromSerialProvider().await())
        assertEquals("channel-serial-1", subscription1.fromSerialProvider().await())
    }

    /**
     * @spec CHA-M5c
     */
    @Test
    fun `subscription should update channel serial after reattach with resume = false`() = runTest {
        messages.channel.properties.channelSerial = "channel-serial-1"
        messages.channel.state = ChannelState.attached

        val subscription1 = (messages.subscribe {}) as DefaultMessagesSubscription
        assertEquals("channel-serial-1", subscription1.fromSerialProvider().await())

        messages.channel.properties.channelSerial = "channel-serial-2"
        messages.channel.properties.attachSerial = "attach-serial-2"
        channelStateListenerSlot.captured.onChannelStateChanged(
            buildChannelStateChange(
                current = ChannelState.attached,
                previous = ChannelState.attaching,
                resumed = false,
            ),
        )

        assertEquals("attach-serial-2", subscription1.fromSerialProvider().await())
    }

    @Test
    fun `subscription should invoke once for each incoming message`() = runTest {
        val listener1 = mockk<Messages.Listener>(relaxed = true)
        val listener2 = mockk<Messages.Listener>(relaxed = true)

        messages.subscribe(listener1)

        messages.channel.channelMulticaster.onMessage(buildDummyPubSubMessage())

        verify(exactly = 1) { listener1.onEvent(any()) }

        messages.subscribe(listener2)

        messages.channel.channelMulticaster.onMessage(buildDummyPubSubMessage())

        verify(exactly = 2) { listener1.onEvent(any()) }
        verify(exactly = 1) { listener2.onEvent(any()) }
    }

    /**
     * @spec CHA-M3d
     */
    @Test
    fun `should throw exception if headers contains ably-chat prefix`() = runTest {
        val exception = assertThrows(AblyException::class.java) {
            runBlocking {
                messages.send(
                    text = "lala",
                    headers = mapOf("ably-chat-foo" to "bar"),
                )
            }
        }
        assertEquals(40_001, exception.errorInfo.code)
    }

    /**
     * @spec CHA-M3c
     */
    @Test
    fun `should throw exception if metadata contains ably-chat key`() = runTest {
        val exception = assertThrows(AblyException::class.java) {
            runBlocking {
                messages.send(
                    text = "lala",
                    metadata = mapOf("ably-chat" to "data").toJson(),
                )
            }
        }
        assertEquals(40_001, exception.errorInfo.code)
    }
}

private val Channel.channelMulticaster: ChannelBase.MessageListener
    get() {
        val field: Field = (ChannelBase::class.java).getDeclaredField("eventListeners")
        field.isAccessible = true
        val eventListeners = field.get(this) as HashMap<*, *>
        return eventListeners["chat.message"] as ChannelBase.MessageListener
    }

private fun buildDummyPubSubMessage() = PubSubMessage().apply {
    data = JsonObject().apply {
        addProperty("text", "dummy text")
    }
    serial = "abcdefghij@1672531200000-123"
    clientId = "dummy"
    timestamp = 1000L
    createdAt = 1000L
    extras = MessageExtras(
        JsonObject().apply {},
    )
    action = MessageAction.MESSAGE_CREATE
}
