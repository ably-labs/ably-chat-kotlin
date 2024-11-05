package com.ably.chat

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.Presence.PresenceListener
import io.ably.lib.realtime.buildRealtimeChannel
import io.ably.lib.types.PresenceMessage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import io.ably.lib.realtime.Presence as PubSubPresence

class PresenceTest {
    private val pubSubChannel = spyk<Channel>(buildRealtimeChannel("room1::\$chat::\$messages"))
    private val pubSubPresence = mockk<PubSubPresence>(relaxed = true)
    private lateinit var presence: DefaultPresence

    @Before
    fun setUp() {
        presence = DefaultPresence(
            clientId = "client1",
            channel = pubSubChannel,
            presence = pubSubPresence,
        )
    }

    /**
     * @spec CHA-PR2a
     */
    @Test
    fun `should transform PresenceMessage into Chat's PresenceEvent if there is no data`() = runTest {
        val presenceListenerSlot = slot<PresenceListener>()

        every { pubSubPresence.subscribe(capture(presenceListenerSlot)) } returns Unit

        val deferredValue = DeferredValue<PresenceEvent>()

        presence.subscribe {
            deferredValue.completeWith(it)
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

        val deferredValue = DeferredValue<PresenceEvent>()

        presence.subscribe {
            deferredValue.completeWith(it)
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

        val deferredValue = DeferredValue<PresenceEvent>()

        presence.subscribe {
            deferredValue.completeWith(it)
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
