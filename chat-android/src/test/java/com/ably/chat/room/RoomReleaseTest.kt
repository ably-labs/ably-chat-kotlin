package com.ably.chat.room

import com.ably.chat.ChatApi
import com.ably.chat.ClientOptions
import com.ably.chat.DefaultRoom
import com.ably.chat.DefaultRooms
import com.ably.chat.ErrorCode
import com.ably.chat.Room
import com.ably.chat.RoomOptions
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.assertWaiter
import io.ably.lib.types.AblyException
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * Spec: CHA-RC1g
 */
class RoomReleaseTest {

    private val clientId = "clientId"
    private val logger = createMockLogger()

    @Test
    fun `(CHA-RC1g) Should be able to release existing room, makes it eligible for garbage collection`() = runTest {
        val roomId = "1234"
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions(), clientId, logger), recordPrivateCalls = true)

        val defaultRoom = spyk(
            DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger),
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
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions(), clientId, logger), recordPrivateCalls = true)

        val defaultRoom = spyk(
            DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger),
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
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions(), clientId, logger), recordPrivateCalls = true)

        // No room exists
        Assert.assertEquals(0, rooms.RoomIdToRoom.size)
        Assert.assertEquals(0, rooms.RoomReleaseDeferredMap.size)
        Assert.assertEquals(0, rooms.RoomGetDeferredMap.size)

        // Release the room
        rooms.release(roomId)

        Assert.assertEquals(0, rooms.RoomIdToRoom.size)
        Assert.assertEquals(0, rooms.RoomReleaseDeferredMap.size)
        Assert.assertEquals(0, rooms.RoomGetDeferredMap.size)
    }

    @Test
    fun `(CHA-RC1g3) If the release operation is already in progress, then the associated deferred will be resolved`() = runTest {
        val roomId = "1234"
        val mockRealtimeClient = createMockRealtimeClient()
        val chatApi = mockk<ChatApi>(relaxed = true)
        val rooms = spyk(DefaultRooms(mockRealtimeClient, chatApi, ClientOptions(), clientId, logger), recordPrivateCalls = true)

        val defaultRoom = spyk(
            DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger),
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
        Assert.assertEquals(1, rooms.RoomReleaseDeferredMap.size)
        Assert.assertNotNull(rooms.RoomReleaseDeferredMap[roomId])

        // Release the room, room release deferred gets empty
        roomReleased.send(Unit)
        releasedDeferredList.awaitAll()
        Assert.assertEquals(RoomStatus.Released, room.status)

        Assert.assertTrue(rooms.RoomReleaseDeferredMap.isEmpty())
        Assert.assertTrue(rooms.RoomIdToRoom.isEmpty())

        coVerify(exactly = 1) {
            defaultRoom.release()
        }
    }

    @Suppress("MaximumLineLength", "LongMethod")
    @Test
    fun `(CHA-RC1g4, CHA-RC1f6) Pending room get operation waiting for room release should be cancelled and deferred associated with previous release operation will be resolved`() = runTest {
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
        launch { rooms.release(roomId) }

        // Room is in releasing state, hence RoomReleaseDeferred contain deferred for given roomId
        assertWaiter { originalRoom.status == RoomStatus.Releasing }
        Assert.assertEquals(1, rooms.RoomReleaseDeferredMap.size)
        Assert.assertNotNull(rooms.RoomReleaseDeferredMap[roomId])

        // Call roomGet Dispatchers.IO scope, it should wait for release op
        val roomGetDeferredList = mutableListOf<Deferred<Room>>()
        repeat(100) {
            val roomGetDeferred = async(Dispatchers.IO + SupervisorJob()) {
                rooms.get(roomId)
            }
            roomGetDeferredList.add(roomGetDeferred)
        }
        // CHA-RC1f5 - Room Get is in waiting state, for room to get released
        assertWaiter { rooms.RoomGetDeferredMap.size == 1 }
        Assert.assertNotNull(rooms.RoomGetDeferredMap[roomId])

        // Call the release again, so that all pending roomGet gets cancelled
        val roomReleaseDeferred = launch { rooms.release(roomId) }

        // All RoomGetDeferred got cancelled due to room release.
        assertWaiter { rooms.RoomGetDeferredMap.isEmpty() }

        // Call RoomGet after release, so this should return a new room when room is released
        val roomGetDeferred = async { rooms.get(roomId) }

        // CHA-RC1f5 - Room Get is in waiting state, for room to get released
        assertWaiter { rooms.RoomGetDeferredMap.size == 1 }
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

        roomReleaseDeferred.join()

        val newRoom = roomGetDeferred.await()
        Assert.assertNotSame(newRoom, originalRoom) // Check new room created

        for (deferred in roomGetDeferredList) {
            val result = kotlin.runCatching { deferred.await() }
            Assert.assertTrue(result.isFailure)
            val exception = result.exceptionOrNull() as AblyException
            Assert.assertEquals(ErrorCode.RoomReleasedBeforeOperationCompleted.code, exception.errorInfo.code)
            Assert.assertEquals("room released before get operation could complete", exception.errorInfo.message)
        }

        verify(exactly = 2) {
            rooms["makeRoom"](any<String>(), any<RoomOptions>())
        }
        coVerify(exactly = 1) {
            defaultRoom.release()
        }
    }
}
