package com.ably.chat.room

import com.ably.chat.ChatApi
import com.ably.chat.ClientOptions
import com.ably.chat.DefaultRoom
import com.ably.chat.DefaultRooms
import com.ably.chat.PresenceOptions
import com.ably.chat.RoomOptions
import com.ably.chat.RoomStatus
import com.ably.chat.TypingOptions
import com.ably.chat.assertWaiter
import io.ably.lib.types.AblyException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Spec: CHA-RC1f
 */
class RoomGetTest {
    private val clientId = "clientId"
    private val logger = createMockLogger()

    @Test
    fun `(CHA-RC1f) Requesting a room from the Chat Client return instance of a room with the provided id and options`() = runTest {
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = DefaultRooms(mockRealtimeClient, chatApi, ClientOptions(), clientId, logger)
        val room = rooms.get("1234", RoomOptions())
        Assert.assertNotNull(room)
        Assert.assertEquals("1234", room.roomId)
        Assert.assertEquals(RoomOptions(), room.options)
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RC1f1) If the room id already exists, and newly requested with different options, then ErrorInfo with code 40000 is thrown`() = runTest {
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions(), clientId, logger), recordPrivateCalls = true)

        // Create room with id "1234"
        val room = rooms.get("1234", RoomOptions())
        Assert.assertEquals(1, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room, rooms.RoomIdToRoom["1234"])

        // Throws exception for requesting room for different roomOptions
        val exception = assertThrows(AblyException::class.java) {
            runBlocking {
                rooms.get("1234", RoomOptions(typing = TypingOptions()))
            }
        }
        Assert.assertNotNull(exception)
        Assert.assertEquals(40_000, exception.errorInfo.code)
        Assert.assertEquals("room already exists with different options", exception.errorInfo.message)
    }

    @Test
    fun `(CHA-RC1f2) If the room id already exists, and newly requested with same options, then returns same room`() = runTest {
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions(), clientId, logger), recordPrivateCalls = true)

        val room1 = rooms.get("1234", RoomOptions())
        Assert.assertEquals(1, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room1, rooms.RoomIdToRoom["1234"])

        val room2 = rooms.get("1234", RoomOptions())
        Assert.assertEquals(1, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room1, room2)

        val room3 = rooms.get("5678", RoomOptions(typing = TypingOptions()))
        Assert.assertEquals(2, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room3, rooms.RoomIdToRoom["5678"])

        val room4 = rooms.get("5678", RoomOptions(typing = TypingOptions()))
        Assert.assertEquals(2, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room3, room4)

        val room5 = rooms.get(
            "7890",
            RoomOptions(
                typing = TypingOptions(timeoutMs = 1500),
                presence = PresenceOptions(
                    enter = true,
                    subscribe = false,
                ),
            ),
        )
        Assert.assertEquals(3, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room5, rooms.RoomIdToRoom["7890"])

        val room6 = rooms.get(
            "7890",
            RoomOptions(
                typing = TypingOptions(timeoutMs = 1500),
                presence = PresenceOptions(
                    enter = true,
                    subscribe = false,
                ),
            ),
        )
        Assert.assertEquals(3, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room5, room6)
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RC1f3) If no CHA-RC1g release operation is in progress, a new room instance shall be created, and added to the room map`() = runTest {
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions(), clientId, logger), recordPrivateCalls = true)
        val roomId = "1234"

        // No release op. in progress
        Assert.assertEquals(0, rooms.RoomReleaseDeferredMap.size)
        Assert.assertNull(rooms.RoomReleaseDeferredMap[roomId])

        // Creates a new room and adds to the room map
        val room = rooms.get("1234", RoomOptions())
        Assert.assertEquals(1, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room, rooms.RoomIdToRoom[roomId])
    }

    @Suppress("MaximumLineLength", "LongMethod")
    @Test
    fun `(CHA-RC1f4, CHA-RC1f5) If CHA-RC1g release operation is in progress, new instance should not be returned until release operation completes`() = runTest {
        val roomId = "1234"
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions(), clientId, logger), recordPrivateCalls = true)

        val defaultRoom = spyk(
            DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger),
            recordPrivateCalls = true,
        )

        val roomReleased = Channel<Unit>()
        coEvery {
            defaultRoom.release()
        } coAnswers {
            defaultRoom.StatusLifecycle.setStatus(RoomStatus.Releasing)
            roomReleased.receive()
            defaultRoom.StatusLifecycle.setStatus(RoomStatus.Released)
            roomReleased.close()
        }

        every {
            rooms["makeRoom"](any<String>(), any<RoomOptions>())
        } answers {
            var room = defaultRoom
            if (roomReleased.isClosedForSend) {
                room = DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger)
            }
            room
        }

        // Creates original room and adds to the room map
        val originalRoom = rooms.get(roomId, RoomOptions())
        Assert.assertEquals(1, rooms.RoomIdToRoom.size)
        Assert.assertEquals(originalRoom, rooms.RoomIdToRoom[roomId])

        // Release the room in separate coroutine, keep it in progress
        val invocationOrder = mutableListOf<String>()
        val roomReleaseDeferred = launch { rooms.release(roomId) }
        roomReleaseDeferred.invokeOnCompletion {
            invocationOrder.add("room.released")
        }

        // Get the same room in separate coroutine, it should wait for release op
        val roomGetDeferred = async { rooms.get(roomId) }
        roomGetDeferred.invokeOnCompletion {
            invocationOrder.add("room.get")
        }

        // Room is in releasing state, hence RoomReleaseDeferred contain deferred for given roomId
        assertWaiter { originalRoom.status == RoomStatus.Releasing }
        Assert.assertEquals(1, rooms.RoomReleaseDeferredMap.size)
        Assert.assertNotNull(rooms.RoomReleaseDeferredMap[roomId])

        // CHA-RC1f5 - Room Get is in waiting state, for room to get released
        assertWaiter { rooms.RoomGetDeferredMap.size == 1 }
        Assert.assertEquals(1, rooms.RoomGetDeferredMap.size)
        Assert.assertNotNull(rooms.RoomGetDeferredMap[roomId])

        // Release the room, room release deferred gets empty
        roomReleased.send(Unit)
        assertWaiter { originalRoom.status == RoomStatus.Released }
        assertWaiter { rooms.RoomReleaseDeferredMap.isEmpty() }
        Assert.assertNull(rooms.RoomReleaseDeferredMap[roomId])

        // Room Get in waiting state gets cleared, so it's map for the same is cleared
        assertWaiter { rooms.RoomGetDeferredMap.isEmpty() }
        Assert.assertEquals(0, rooms.RoomGetDeferredMap.size)
        Assert.assertNull(rooms.RoomGetDeferredMap[roomId])

        val newRoom = roomGetDeferred.await()
        roomReleaseDeferred.join()

        // Check order of invocations
        Assert.assertEquals(listOf("room.released", "room.get"), invocationOrder)

        // Check if new room is returned
        Assert.assertNotSame(newRoom, originalRoom)

        verify(exactly = 2) {
            rooms["makeRoom"](any<String>(), any<RoomOptions>())
        }
    }
}
