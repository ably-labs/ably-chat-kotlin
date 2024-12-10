package com.ably.chat.room

import io.ably.lib.realtime.buildRealtimeChannel
import io.ably.lib.types.ChannelMode
import io.ably.lib.types.ChannelOptions
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * Test to check shared channel for room features.
 * Spec: CHA-RC3, CHA-RC2f
 */
class RoomFeatureSharedChannelTest {

    @Test
    fun `(CHA-RC3a, CHA-RC2f) Features with shared channel should call channels#get only once with combined modes+options`() = runTest {
        val mockRealtimeClient = createMockRealtimeClient()
        val capturedChannelOptions = mutableListOf<ChannelOptions>()

        every {
            mockRealtimeClient.channels.get("1234::\$chat::\$chatMessages", any<ChannelOptions>())
        } answers {
            capturedChannelOptions.add(secondArg())
            buildRealtimeChannel()
        }

        // Create room with all feature enabled,
        val room = createMockRoom(realtimeClient = mockRealtimeClient)

        // Messages, occupancy and presence features uses the same channel
        Assert.assertEquals(room.messages.channel, room.presence.channel)
        Assert.assertEquals(room.messages.channel, room.occupancy.channel)

        // Reactions and typing uses independent channel
        Assert.assertNotEquals(room.messages.channel, room.typing.channel)
        Assert.assertNotEquals(room.messages.channel, room.reactions.channel)
        Assert.assertNotEquals(room.reactions.channel, room.typing.channel)

        Assert.assertEquals(1, capturedChannelOptions.size)
        // Check for set presence modes
        Assert.assertEquals(4, capturedChannelOptions[0].modes.size)
        Assert.assertEquals(ChannelMode.publish, capturedChannelOptions[0].modes[0])
        Assert.assertEquals(ChannelMode.subscribe, capturedChannelOptions[0].modes[1])
        Assert.assertEquals(ChannelMode.presence, capturedChannelOptions[0].modes[2])
        Assert.assertEquals(ChannelMode.presence_subscribe, capturedChannelOptions[0].modes[3])
        // Check if occupancy matrix is set
        Assert.assertEquals("metrics", capturedChannelOptions[0].params["occupancy"])

        // channels.get is called only once for Messages, occupancy and presence since they share the same channel
        verify(exactly = 1) {
            mockRealtimeClient.channels.get("1234::\$chat::\$chatMessages", any<ChannelOptions>())
        }
        // channels.get called separately for typing since it uses it's own channel
        verify(exactly = 1) {
            mockRealtimeClient.channels.get("1234::\$chat::\$typingIndicators", any<ChannelOptions>())
        }
        // channels.get called separately for reactions since it uses it's own channel
        verify(exactly = 1) {
            mockRealtimeClient.channels.get("1234::\$chat::\$reactions", any<ChannelOptions>())
        }
        // channels.get is called thrice for all features
        verify(exactly = 3) {
            mockRealtimeClient.channels.get(any<String>(), any<ChannelOptions>())
        }
    }
}
