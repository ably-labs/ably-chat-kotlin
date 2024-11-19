package com.ably.chat.room

import com.ably.chat.ChatApi
import com.ably.chat.ClientOptions
import com.ably.chat.DefaultRoom
import com.ably.chat.DefaultRooms
import com.ably.chat.RoomOptions
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.assertWaiter
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * Spec: CHA-RC1g
 */
class RoomReleaseTest {
    @Test
    fun `(CHA-RC1g) Should be able to release existing room, makes it eligible for garbage collection`() = runTest {
        val roomId = "1234"
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions()), recordPrivateCalls = true)

        val defaultRoom = spyk(
            DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, null),
            recordPrivateCalls = true,
        )
        coJustRun { defaultRoom.release() }

        every { rooms["makeRoom"](any<String>(), any<RoomOptions>()) } returns defaultRoom

        // Creates original room and adds to the room map
        val room = rooms.get(roomId, RoomOptions())
        Assert.assertEquals(1, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room, rooms.RoomIdToRoom[roomId])

        // Release the room
        rooms.release(roomId)

        Assert.assertEquals(0, rooms.RoomIdToRoom.size)
    }

    @Test
    fun `(CHA-RC1g1, CHA-RC1g5) Release operation only returns after channel goes into Released state`() = runTest {
        val roomId = "1234"
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions()), recordPrivateCalls = true)

        val defaultRoom = spyk(
            DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, null),
            recordPrivateCalls = true,
        )

        val roomStateChanges = mutableListOf<RoomStatusChange>()
        defaultRoom.onStatusChange {
            roomStateChanges.add(it)
        }

        coEvery {
            defaultRoom.release()
        } coAnswers {
            defaultRoom.StatusLifecycle.setStatus(RoomStatus.Releasing)
            defaultRoom.StatusLifecycle.setStatus(RoomStatus.Released)
        }

        every { rooms["makeRoom"](any<String>(), any<RoomOptions>()) } returns defaultRoom

        // Creates original room and adds to the room map
        val room = rooms.get(roomId, RoomOptions())
        Assert.assertEquals(1, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room, rooms.RoomIdToRoom[roomId])

        // Release the room
        rooms.release(roomId)

        // CHA-RC1g5 - Room is removed after release operation
        Assert.assertEquals(0, rooms.RoomIdToRoom.size)

        Assert.assertEquals(2, roomStateChanges.size)
        Assert.assertEquals(RoomStatus.Releasing, roomStateChanges[0].current)
        Assert.assertEquals(RoomStatus.Released, roomStateChanges[1].current)
    }

    @Test
    fun `(CHA-RC1g2) If the room does not exist in the room map, and no release operation is in progress, there is no-op`() = runTest {
        val roomId = "1234"
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions()), recordPrivateCalls = true)

        // No room exists
        Assert.assertEquals(0, rooms.RoomIdToRoom.size)
        Assert.assertEquals(0, rooms.RoomReleaseDeferred.size)
        Assert.assertEquals(0, rooms.RoomGetDeferred.size)

        // Release the room
        rooms.release(roomId)

        Assert.assertEquals(0, rooms.RoomIdToRoom.size)
        Assert.assertEquals(0, rooms.RoomReleaseDeferred.size)
        Assert.assertEquals(0, rooms.RoomGetDeferred.size)
    }

    @Test
    fun `(CHA-RC1g3) If the release operation is already in progress, then the associated deferred will be resolved`() = runTest {
        val roomId = "1234"
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions()), recordPrivateCalls = true)

        val defaultRoom = spyk(
            DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, null),
            recordPrivateCalls = true,
        )
        every { rooms["makeRoom"](any<String>(), any<RoomOptions>()) } returns defaultRoom

        val roomReleased = Channel<Unit>()
        coEvery {
            defaultRoom.release()
        } coAnswers {
            defaultRoom.StatusLifecycle.setStatus(RoomStatus.Releasing)
            roomReleased.receive()
            defaultRoom.StatusLifecycle.setStatus(RoomStatus.Released)
        }

        // Creates a room and adds to the room map
        val room = rooms.get(roomId, RoomOptions())
        Assert.assertEquals(1, rooms.RoomIdToRoom.size)
        Assert.assertEquals(room, rooms.RoomIdToRoom[roomId])

        // Release the room in separate coroutine, keep it in progress
        val releasedDeferredList = mutableListOf<Deferred<Unit>>()

        repeat(1000) {
            val roomReleaseDeferred = async(Dispatchers.IO) {
                rooms.release(roomId)
            }
            releasedDeferredList.add(roomReleaseDeferred)
        }

        // Wait for room to get into releasing state
        assertWaiter { room.status == RoomStatus.Releasing }
        Assert.assertEquals(1, rooms.RoomReleaseDeferred.size)
        Assert.assertNotNull(rooms.RoomReleaseDeferred[roomId])

        // Release the room, room release deferred gets empty
        roomReleased.send(Unit)
        releasedDeferredList.awaitAll()
        Assert.assertEquals(RoomStatus.Released, room.status)

        Assert.assertTrue(rooms.RoomReleaseDeferred.isEmpty())
        Assert.assertTrue(rooms.RoomIdToRoom.isEmpty())

        coVerify(exactly = 1) {
            defaultRoom.release()
        }
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RC1g4, CHA-RC1f6) Pending room get operation waiting for room release should be cancelled and deferred associated with previous release operation will be resolved`() = runTest {
    }
}
