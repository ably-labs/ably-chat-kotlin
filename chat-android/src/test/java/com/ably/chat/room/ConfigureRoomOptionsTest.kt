package com.ably.chat.room

import com.ably.chat.ChatApi
import com.ably.chat.DefaultRoom
import com.ably.chat.RoomOptions
import com.ably.chat.RoomStatus
import com.ably.chat.TypingOptions
import io.ably.lib.types.AblyException
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Chat rooms are configurable, so as to enable or disable certain features.
 * When requesting a room, options as to which features should be enabled, and
 * the configuration they should take, must be provided
 * Spec: CHA-RC2
 */
class ConfigureRoomOptionsTest {

    private val clientId = "clientId"
    private val logger = createMockLogger()

    @Test
    fun `(CHA-RC2a) If a room is requested with a negative typing timeout, an ErrorInfo with code 40001 must be thrown`() = runTest {
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)

        // Room success when positive typing timeout
        val room = DefaultRoom("1234", RoomOptions(typing = TypingOptions(timeoutMs = 100)), mockRealtimeClient, chatApi, clientId, logger)
        Assert.assertNotNull(room)
        Assert.assertEquals(RoomStatus.Initialized, room.status)

        // Room failure when negative timeout
        val exception = assertThrows(AblyException::class.java) {
            DefaultRoom("1234", RoomOptions(typing = TypingOptions(timeoutMs = -1)), mockRealtimeClient, chatApi, clientId, logger)
        }
        Assert.assertEquals("Typing timeout must be greater than 0", exception.errorInfo.message)
        Assert.assertEquals(40_001, exception.errorInfo.code)
        Assert.assertEquals(400, exception.errorInfo.statusCode)
    }

    @Test
    fun `(CHA-RC2b) Attempting to use disabled feature must result in an ErrorInfo with code 40000 being thrown`() = runTest {
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)

        // Room only supports messages feature, since by default other features are turned off
        val room = DefaultRoom("1234", RoomOptions(), mockRealtimeClient, chatApi, clientId, logger)
        Assert.assertNotNull(room)
        Assert.assertEquals(RoomStatus.Initialized, room.status)

        // Access presence throws exception
        var exception = assertThrows(AblyException::class.java) {
            room.presence
        }
        Assert.assertEquals("Presence is not enabled for this room", exception.errorInfo.message)
        Assert.assertEquals(40_000, exception.errorInfo.code)
        Assert.assertEquals(400, exception.errorInfo.statusCode)

        // Access reactions throws exception
        exception = assertThrows(AblyException::class.java) {
            room.reactions
        }
        Assert.assertEquals("Reactions are not enabled for this room", exception.errorInfo.message)
        Assert.assertEquals(40_000, exception.errorInfo.code)
        Assert.assertEquals(400, exception.errorInfo.statusCode)

        // Access typing throws exception
        exception = assertThrows(AblyException::class.java) {
            room.typing
        }
        Assert.assertEquals("Typing is not enabled for this room", exception.errorInfo.message)
        Assert.assertEquals(40_000, exception.errorInfo.code)
        Assert.assertEquals(400, exception.errorInfo.statusCode)

        // Access occupancy throws exception
        exception = assertThrows(AblyException::class.java) {
            room.occupancy
        }
        Assert.assertEquals("Occupancy is not enabled for this room", exception.errorInfo.message)
        Assert.assertEquals(40_000, exception.errorInfo.code)
        Assert.assertEquals(400, exception.errorInfo.statusCode)

        // room with all features
        val roomWithAllFeatures = DefaultRoom("1234", RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger)
        Assert.assertNotNull(roomWithAllFeatures.presence)
        Assert.assertNotNull(roomWithAllFeatures.reactions)
        Assert.assertNotNull(roomWithAllFeatures.typing)
        Assert.assertNotNull(roomWithAllFeatures.occupancy)
    }
}
