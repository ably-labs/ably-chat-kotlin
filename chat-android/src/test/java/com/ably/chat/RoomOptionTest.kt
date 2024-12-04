package com.ably.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class RoomOptionTest {

    @Test
    fun `default occupancy options should be equal`() {
        assertEquals(OccupancyOptions(), OccupancyOptions())
    }

    @Test
    fun `default room reaction options should be equal`() {
        assertEquals(RoomReactionsOptions(), RoomReactionsOptions())
    }

    @Test
    fun `default room options should be equal`() {
        assertEquals(
            RoomOptions.default,
            RoomOptions(
                typing = TypingOptions(),
                presence = PresenceOptions(),
                reactions = RoomReactionsOptions(),
                occupancy = OccupancyOptions(),
            ),
        )
    }
}
