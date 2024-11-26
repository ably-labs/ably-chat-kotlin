package com.ably.chat

import com.ably.chat.room.createMockLogger
import com.google.gson.JsonObject
import io.ably.lib.realtime.AblyRealtime.Channels
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.buildRealtimeChannel
import io.ably.lib.types.MessageExtras
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RoomReactionsTest {
    private val realtimeChannels = mockk<Channels>(relaxed = true)
    private val realtimeChannel = spyk<Channel>(buildRealtimeChannel("room1::\$chat::\$reactions"))
    private lateinit var roomReactions: DefaultRoomReactions
    private val logger = createMockLogger()

    @Before
    fun setUp() {
        every { realtimeChannels.get(any(), any()) } answers {
            val channelName = firstArg<String>()
            if (channelName == "room1::\$chat::\$reactions") {
                realtimeChannel
            } else {
                buildRealtimeChannel(channelName)
            }
        }

        roomReactions = DefaultRoomReactions(
            roomId = "room1",
            clientId = "client1",
            realtimeChannels = realtimeChannels,
            logger,
        )
    }

    /**
     * @spec CHA-ER1
     */
    @Test
    fun `channel name is set according to the spec`() = runTest {
        val roomReactions = DefaultRoomReactions(
            roomId = "foo",
            clientId = "client1",
            realtimeChannels = realtimeChannels,
            logger,
        )

        assertEquals(
            "foo::\$chat::\$reactions",
            roomReactions.channel.name,
        )
    }

    /**
     * @spec CHA-ER3a
     */
    @Test
    fun `should be able to subscribe to incoming reactions`() = runTest {
        val pubSubMessageListenerSlot = slot<PubSubMessageListener>()

        every { realtimeChannel.subscribe("roomReaction", capture(pubSubMessageListenerSlot)) } returns Unit

        val deferredValue = DeferredValue<Reaction>()

        roomReactions.subscribe {
            deferredValue.completeWith(it)
        }

        verify { realtimeChannel.subscribe("roomReaction", any()) }

        pubSubMessageListenerSlot.captured.onMessage(
            PubSubMessage().apply {
                data = JsonObject().apply {
                    addProperty("type", "like")
                }
                clientId = "clientId"
                timestamp = 1000L
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
            },
        )

        val reaction = deferredValue.await()

        assertEquals(
            Reaction(
                type = "like",
                createdAt = 1000L,
                clientId = "clientId",
                metadata = mapOf(),
                headers = mapOf("foo" to "bar"),
                isSelf = false,
            ),
            reaction,
        )
    }
}
