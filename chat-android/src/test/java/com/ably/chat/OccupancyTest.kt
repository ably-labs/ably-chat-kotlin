package com.ably.chat

import com.ably.chat.room.createMockChannel
import com.ably.chat.room.createMockChatApi
import com.ably.chat.room.createMockRealtimeClient
import com.ably.chat.room.createMockRoom
import com.google.gson.JsonObject
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OccupancyTest {

    private lateinit var occupancy: Occupancy
    private val pubSubMessageListenerSlot = slot<PubSubMessageListener>()
    private val realtimeClient = createMockRealtimeClient()

    @Before
    fun setUp() {
        val mockRealtimeChannel = realtimeClient.createMockChannel()
        every { mockRealtimeChannel.subscribe(capture(pubSubMessageListenerSlot)) } returns Unit

        every { realtimeClient.channels.get(any(), any()) } returns mockRealtimeChannel

        val mockChatApi = createMockChatApi(realtimeClient)
        val room = createMockRoom("room1", realtimeClient = realtimeClient, chatApi = mockChatApi)
        occupancy = DefaultOccupancy(room)
    }

    /**
     * @spec CHA-O3
     */
    @Test
    fun `user should be able to receive occupancy via #get()`() = runTest {
        mockOccupancyApiResponse(
            realtimeClient,
            JsonObject().apply {
                addProperty("connections", 2)
                addProperty("presenceMembers", 1)
            },
            roomId = "room1",
        )

        assertEquals(OccupancyEvent(connections = 2, presenceMembers = 1), occupancy.get())
    }

    /**
     * @spec CHA-O4a
     * @spec CHA-04c
     */
    @Test
    fun `user should be able to register a listener that receives occupancy events in realtime`() = runTest {
        val occupancyEventMessage = PubSubMessage().apply {
            data = JsonObject().apply {
                add(
                    "metrics",
                    JsonObject().apply {
                        addProperty("connections", 2)
                        addProperty("presenceMembers", 1)
                    },
                )
            }
        }

        val deferredEvent = CompletableDeferred<OccupancyEvent>()
        occupancy.subscribe {
            deferredEvent.complete(it)
        }

        pubSubMessageListenerSlot.captured.onMessage(occupancyEventMessage)

        assertEquals(OccupancyEvent(connections = 2, presenceMembers = 1), deferredEvent.await())
    }

    /**
     * @spec CHA-04d
     */
    @Test
    fun `invalid occupancy event should be dropped`() = runTest {
        val validOccupancyEvent = PubSubMessage().apply {
            data = JsonObject().apply {
                add(
                    "metrics",
                    JsonObject().apply {
                        addProperty("connections", 1)
                        addProperty("presenceMembers", 1)
                    },
                )
            }
        }

        val invalidOccupancyEvent = PubSubMessage().apply {
            data = JsonObject().apply {
                add("metrics", JsonObject())
            }
        }

        val deferredEvent = CompletableDeferred<OccupancyEvent>()
        occupancy.subscribe {
            deferredEvent.complete(it)
        }

        pubSubMessageListenerSlot.captured.onMessage(invalidOccupancyEvent)
        pubSubMessageListenerSlot.captured.onMessage(validOccupancyEvent)

        assertEquals(OccupancyEvent(connections = 1, presenceMembers = 1), deferredEvent.await())
    }

    /**
     * @spec CHA-04b
     */
    @Test
    fun `user should be able to remove a listener`() = runTest {
        val subscription = occupancy.subscribe {
            error("Should not be called")
        }
        subscription.unsubscribe()

        val fakeMessage = PubSubMessage().apply {
            data = JsonObject().apply {
                add(
                    "metrics",
                    JsonObject().apply {
                        addProperty("connections", 1)
                        addProperty("presenceMembers", 1)
                    },
                )
            }
        }

        pubSubMessageListenerSlot.captured.onMessage(fakeMessage)

        val deferredEvent = CompletableDeferred<OccupancyEvent>()
        occupancy.subscribe {
            deferredEvent.complete(it)
        }

        pubSubMessageListenerSlot.captured.onMessage(fakeMessage)

        assertEquals(OccupancyEvent(connections = 1, presenceMembers = 1), deferredEvent.await())
    }
}
