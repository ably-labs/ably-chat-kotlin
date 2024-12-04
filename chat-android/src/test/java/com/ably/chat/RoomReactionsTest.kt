package com.ably.chat

import com.ably.chat.room.createMockChannel
import com.ably.chat.room.createMockRealtimeClient
import com.ably.chat.room.createMockRoom
import com.google.gson.JsonObject
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.buildRealtimeChannel
import io.ably.lib.types.MessageExtras
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RoomReactionsTest {
    private lateinit var realtimeChannel: Channel
    private lateinit var roomReactions: DefaultRoomReactions
    private lateinit var room: DefaultRoom

    @Before
    fun setUp() {
        val realtimeClient = createMockRealtimeClient()
        realtimeChannel = realtimeClient.createMockChannel("room1::\$chat::\$reactions")

        every { realtimeClient.channels.get(any(), any()) } answers {
            val channelName = firstArg<String>()
            if (channelName == "room1::\$chat::\$reactions") {
                realtimeChannel
            } else {
                buildRealtimeChannel(channelName)
            }
        }
        room = createMockRoom("room1", "client1", realtimeClient = realtimeClient)
        roomReactions = DefaultRoomReactions(room)
    }

    /**
     * @spec CHA-ER1
     */
    @Test
    fun `channel name is set according to the spec`() = runTest {
        val roomReactions = DefaultRoomReactions(room)

        assertEquals(
            "room1::\$chat::\$reactions",
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

        val deferredValue = CompletableDeferred<Reaction>()

        roomReactions.subscribe {
            deferredValue.complete(it)
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
                metadata = null,
                headers = mapOf("foo" to "bar"),
                isSelf = false,
            ),
            reaction,
        )
    }
}
