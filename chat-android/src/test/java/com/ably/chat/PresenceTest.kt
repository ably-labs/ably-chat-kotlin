package com.ably.chat

import com.ably.chat.room.createMockChannel
import com.ably.chat.room.createMockRealtimeClient
import com.ably.chat.room.createMockRoom
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.ably.lib.realtime.Presence.PresenceListener
import io.ably.lib.types.ChannelOptions
import io.ably.lib.types.PresenceMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import io.ably.lib.realtime.Presence as PubSubPresence

class PresenceTest {

    private val pubSubPresence = mockk<PubSubPresence>(relaxed = true)
    private lateinit var presence: DefaultPresence

    @Before
    fun setUp() {
        val realtimeClient = createMockRealtimeClient()
        val mockRealtimeChannel = realtimeClient.createMockChannel("room1::\$chat::\$messages")
        mockRealtimeChannel.setPrivateField("presence", pubSubPresence)

        every { realtimeClient.channels.get(any<String>(), any<ChannelOptions>()) } returns mockRealtimeChannel

        presence = DefaultPresence(createMockRoom(realtimeClient = realtimeClient))
    }

    /**
     * @spec CHA-PR2a
     */
    @Test
    fun `should transform PresenceMessage into Chat's PresenceEvent if there is no data`() = runTest {
        val presenceListenerSlot = slot<PresenceListener>()

        every { pubSubPresence.subscribe(capture(presenceListenerSlot)) } returns Unit

        val deferredValue = CompletableDeferred<PresenceEvent>()

        presence.subscribe {
            deferredValue.complete(it)
        }

        presenceListenerSlot.captured.onPresenceMessage(
            PresenceMessage().apply {
                action = PresenceMessage.Action.leave
                clientId = "client1"
                timestamp = 100_000L
            },
        )

        val presenceEvent = deferredValue.await()

        assertEquals(
            PresenceEvent(
                action = PresenceMessage.Action.leave,
                clientId = "client1",
                timestamp = 100_000L,
                data = null,
            ),
            presenceEvent,
        )
    }

    /**
     * @spec CHA-PR2a
     */
    @Test
    fun `should transform PresenceMessage into Chat's PresenceEvent if there is no 'userCustomData'`() = runTest {
        val presenceListenerSlot = slot<PresenceListener>()

        every { pubSubPresence.subscribe(capture(presenceListenerSlot)) } returns Unit

        val deferredValue = CompletableDeferred<PresenceEvent>()

        presence.subscribe {
            deferredValue.complete(it)
        }

        presenceListenerSlot.captured.onPresenceMessage(
            PresenceMessage().apply {
                action = PresenceMessage.Action.leave
                clientId = "client1"
                timestamp = 100_000L
                data = JsonObject()
            },
        )

        val presenceEvent = deferredValue.await()

        assertEquals(
            PresenceEvent(
                action = PresenceMessage.Action.leave,
                clientId = "client1",
                timestamp = 100_000L,
                data = null,
            ),
            presenceEvent,
        )
    }

    /**
     * @spec CHA-PR2a
     */
    @Test
    fun `should transform PresenceMessage into Chat's PresenceEvent if 'userCustomData' is primitive`() = runTest {
        val presenceListenerSlot = slot<PresenceListener>()

        every { pubSubPresence.subscribe(capture(presenceListenerSlot)) } returns Unit

        val deferredValue = CompletableDeferred<PresenceEvent>()

        presence.subscribe {
            deferredValue.complete(it)
        }

        presenceListenerSlot.captured.onPresenceMessage(
            PresenceMessage().apply {
                action = PresenceMessage.Action.leave
                clientId = "client1"
                timestamp = 100_000L
                data = JsonObject().apply {
                    addProperty("userCustomData", "user")
                }
            },
        )

        val presenceEvent = deferredValue.await()

        assertEquals(
            PresenceEvent(
                action = PresenceMessage.Action.leave,
                clientId = "client1",
                timestamp = 100_000L,
                data = JsonPrimitive("user"),
            ),
            presenceEvent,
        )
    }
}
