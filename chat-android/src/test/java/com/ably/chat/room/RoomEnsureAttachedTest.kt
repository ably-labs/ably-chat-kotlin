package com.ably.chat.room

import com.ably.chat.ChatApi
import com.ably.chat.DefaultRoom
import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.ErrorCode
import com.ably.chat.HttpStatusCode
import com.ably.chat.RoomLifecycle
import com.ably.chat.RoomOptions
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.assertWaiter
import com.ably.chat.setPrivateField
import io.ably.lib.types.AblyException
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * Spec: CHA-RL9
 * Common spec: CHA-PR3d, CHA-PR3h, CHA-PR10d, CHA-PR10h, CHA-PR6c, CHA-PR6h, CHA-PR6c, CHA-T2g
 * All of the remaining spec items are specified at Room#ensureAttached method
 */
class RoomEnsureAttachedTest {

    private val clientId = "clientId"
    private val logger = createMockLogger()
    private val roomId = "1234"
    private val mockRealtimeClient = createMockRealtimeClient()
    private val chatApi = mockk<ChatApi>(relaxed = true)

    @Test
    fun `(CHA-PR3d, CHA-PR10d, CHA-PR6c, CHA-PR6c) When room is already ATTACHED, ensureAttached is a success`() = runTest {
        val room = DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger)
        Assert.assertEquals(RoomStatus.Initialized, room.status)

        // Set room status to ATTACHED
        room.StatusLifecycle.setStatus(RoomStatus.Attached)
        Assert.assertEquals(RoomStatus.Attached, room.status)

        val result = kotlin.runCatching { room.ensureAttached() }
        Assert.assertTrue(result.isSuccess)
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-PR3h, CHA-PR10h, CHA-PR6h, CHA-T2g) When room is not ATTACHED or ATTACHING, ensureAttached throws error with code RoomInInvalidState`() = runTest {
        val room = DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger)
        Assert.assertEquals(RoomStatus.Initialized, room.status)

        // List of room status other than ATTACHED/ATTACHING
        val invalidStatuses = listOf(
            RoomStatus.Initialized,
            RoomStatus.Detaching,
            RoomStatus.Detached,
            RoomStatus.Suspended,
            RoomStatus.Failed,
            RoomStatus.Releasing,
            RoomStatus.Released,
        )

        for (invalidStatus in invalidStatuses) {
            room.StatusLifecycle.setStatus(invalidStatus)
            Assert.assertEquals(invalidStatus, room.status)

            // Check for exception when ensuring room ATTACHED
            val result = kotlin.runCatching { room.ensureAttached() }
            Assert.assertTrue(result.isFailure)
            val exception = result.exceptionOrNull() as AblyException
            Assert.assertEquals(ErrorCode.RoomInInvalidState.code, exception.errorInfo.code)
            Assert.assertEquals(HttpStatusCode.BadRequest, exception.errorInfo.statusCode)
            val errMsg = "Can't perform operation; the room '$roomId' is in an invalid state: $invalidStatus"
            Assert.assertEquals(errMsg, exception.errorInfo.message)
        }
    }

    @Test
    fun `(CHA-RL9a) When room is ATTACHING, subscribe once for next room status`() = runTest {
        val room = DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger)
        Assert.assertEquals(RoomStatus.Initialized, room.status)

        val roomLifecycleMock = spyk(DefaultRoomLifecycle(logger))
        every {
            roomLifecycleMock.onChangeOnce(any<RoomLifecycle.Listener>())
        } answers {
            val listener = firstArg<RoomLifecycle.Listener>()
            listener.roomStatusChanged(RoomStatusChange(RoomStatus.Attached, RoomStatus.Attaching))
        }
        room.setPrivateField("statusLifecycle", roomLifecycleMock)

        // Set room status to ATTACHING
        room.StatusLifecycle.setStatus(RoomStatus.Attaching)
        Assert.assertEquals(RoomStatus.Attaching, room.status)

        room.ensureAttached()

        verify(exactly = 1) {
            roomLifecycleMock.onChangeOnce(any<RoomLifecycle.Listener>())
        }
    }

    @Test
    fun `(CHA-RL9b) When room is ATTACHING, subscription is registered, ensureAttached is a success`() = runTest {
        val room = DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger)
        Assert.assertEquals(RoomStatus.Initialized, room.status)

        // Set room status to ATTACHING
        room.StatusLifecycle.setStatus(RoomStatus.Attaching)
        Assert.assertEquals(RoomStatus.Attaching, room.status)

        val ensureAttachJob = async { room.ensureAttached() }

        // Wait for listener to be registered
        assertWaiter { room.StatusLifecycle.InternalEmitter.Filters.size == 1 }

        // Set ATTACHED status
        room.StatusLifecycle.setStatus(RoomStatus.Attached)

        val result = kotlin.runCatching { ensureAttachJob.await() }
        Assert.assertTrue(result.isSuccess)

        Assert.assertEquals(0, room.StatusLifecycle.InternalEmitter.Filters.size) // Emitted event processed
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL9c) When room is ATTACHING and subscription is registered and fails, ensureAttached throws error with code RoomInInvalidState`() = runTest {
        val room = DefaultRoom(roomId, RoomOptions.default, mockRealtimeClient, chatApi, clientId, logger)
        Assert.assertEquals(RoomStatus.Initialized, room.status)

        // List of room status other than ATTACHED/ATTACHING
        val invalidStatuses = listOf(
            RoomStatus.Initialized,
            RoomStatus.Detaching,
            RoomStatus.Detached,
            RoomStatus.Suspended,
            RoomStatus.Failed,
            RoomStatus.Releasing,
            RoomStatus.Released,
        )

        for (invalidStatus in invalidStatuses) {
            // Set room status to ATTACHING
            room.StatusLifecycle.setStatus(RoomStatus.Attaching)
            Assert.assertEquals(RoomStatus.Attaching, room.status)

            val ensureAttachJob = async(SupervisorJob()) { room.ensureAttached() }

            // Wait for listener to be registered
            assertWaiter { room.StatusLifecycle.InternalEmitter.Filters.size == 1 }

            // set invalid room status
            room.StatusLifecycle.setStatus(invalidStatus)

            // Check for exception when ensuring room ATTACHED
            val result = kotlin.runCatching { ensureAttachJob.await() }
            Assert.assertTrue(result.isFailure)
            val exception = result.exceptionOrNull() as AblyException
            Assert.assertEquals(ErrorCode.RoomInInvalidState.code, exception.errorInfo.code)
            Assert.assertEquals(HttpStatusCode.InternalServerError, exception.errorInfo.statusCode)
            val errMsg = "Can't perform operation; the room '$roomId' is in an invalid state: $invalidStatus"
            Assert.assertEquals(errMsg, exception.errorInfo.message)

            Assert.assertEquals(0, room.StatusLifecycle.InternalEmitter.Filters.size) // Emitted event processed
        }
    }
}
