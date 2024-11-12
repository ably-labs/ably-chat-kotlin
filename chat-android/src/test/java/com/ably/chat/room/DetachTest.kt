package com.ably.chat.room

import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.ErrorCodes
import com.ably.chat.HttpStatusCodes
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.assertWaiter
import com.ably.chat.detachCoroutine
import com.ably.utils.atomicCoroutineScope
import com.ably.utils.createRoomFeatureMocks
import io.ably.lib.types.AblyException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.justRun
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * Spec: CHA-RL2
 */
class DetachTest {
    private val roomScope = CoroutineScope(
        Dispatchers.Default.limitedParallelism(1) + CoroutineName("roomId"),
    )

    @Test
    fun `(CHA-RL2a) Detach success when room is already in detached state`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>().apply {
            setStatus(RoomStatus.Detached)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks()))
        val result = kotlin.runCatching { roomLifecycle.detach() }
        Assert.assertTrue(result.isSuccess)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL2b) Detach throws exception when room in releasing state`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>().apply {
            setStatus(RoomStatus.Releasing)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks()))
        val exception = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                roomLifecycle.detach()
            }
        }
        Assert.assertEquals("unable to detach room; room is releasing", exception.errorInfo.message)
        Assert.assertEquals(ErrorCodes.RoomIsReleasing.errorCode, exception.errorInfo.code)
        Assert.assertEquals(HttpStatusCodes.InternalServerError, exception.errorInfo.statusCode)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL2c) Detach throws exception when room in released state`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>().apply {
            setStatus(RoomStatus.Released)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, listOf()))
        val exception = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                roomLifecycle.detach()
            }
        }
        Assert.assertEquals("unable to detach room; room is released", exception.errorInfo.message)
        Assert.assertEquals(ErrorCodes.RoomIsReleased.errorCode, exception.errorInfo.code)
        Assert.assertEquals(HttpStatusCodes.InternalServerError, exception.errorInfo.statusCode)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL2d) Detach throws exception when room in failed state`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>().apply {
            setStatus(RoomStatus.Failed)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, listOf()))
        val exception = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                roomLifecycle.detach()
            }
        }
        Assert.assertEquals("unable to detach room; room has failed", exception.errorInfo.message)
        Assert.assertEquals(ErrorCodes.RoomInFailedState.errorCode, exception.errorInfo.code)
        Assert.assertEquals(HttpStatusCodes.InternalServerError, exception.errorInfo.statusCode)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL2e) Detach op should transition room into DETACHING state, transient timeouts should be cleared`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>()
        val roomStatusChanges = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            roomStatusChanges.add(it)
        }

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, emptyList()), recordPrivateCalls = true)
        justRun { roomLifecycle invokeNoArgs "clearAllTransientDetachTimeouts" }

        roomLifecycle.detach()
        Assert.assertEquals(RoomStatus.Detaching, roomStatusChanges[0].current)
        Assert.assertEquals(RoomStatus.Detached, roomStatusChanges[1].current)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        verify(exactly = 1) {
            roomLifecycle invokeNoArgs "clearAllTransientDetachTimeouts"
        }
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL2f, CHA-RL2g) Detach op should detach each contributor channel sequentially and room should be considered DETACHED`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>()

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        val capturedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            capturedChannels.add(firstArg())
        }

        val contributors = createRoomFeatureMocks()
        Assert.assertEquals(5, contributors.size)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors))
        val result = kotlin.runCatching { roomLifecycle.detach() }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Detached, statusLifecycle.status)

        Assert.assertEquals(5, capturedChannels.size)
        repeat(5) {
            Assert.assertEquals(contributors[it].channel.name, capturedChannels[it].name)
        }
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[1].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[2].name)
        Assert.assertEquals("1234::\$chat::\$typingIndicators", capturedChannels[3].name)
        Assert.assertEquals("1234::\$chat::\$reactions", capturedChannels[4].name)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL2i) Detach op should wait for existing operation as per (CHA-RL7)`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>()
        Assert.assertEquals(RoomStatus.Initializing, statusLifecycle.status)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks()))

        val roomReleased = Channel<Boolean>()
        coEvery {
            roomLifecycle.release()
        } coAnswers {
            roomLifecycle.atomicCoroutineScope().async {
                statusLifecycle.setStatus(RoomStatus.Releasing)
                roomReleased.receive()
                statusLifecycle.setStatus(RoomStatus.Released)
            }
        }

        // Release op started from separate coroutine
        launch { roomLifecycle.release() }
        assertWaiter { !roomLifecycle.atomicCoroutineScope().finishedProcessing }
        Assert.assertEquals(0, roomLifecycle.atomicCoroutineScope().pendingJobCount) // no queued jobs, one job running
        assertWaiter { statusLifecycle.status == RoomStatus.Releasing }

        // Detach op started from separate coroutine
        val roomDetachOpDeferred = async(SupervisorJob()) { roomLifecycle.detach() }
        assertWaiter { roomLifecycle.atomicCoroutineScope().pendingJobCount == 1 } // detach op queued
        Assert.assertEquals(RoomStatus.Releasing, statusLifecycle.status)

        // Finish release op, so DETACH op can start
        roomReleased.send(true)
        assertWaiter { statusLifecycle.status == RoomStatus.Released }

        val result = kotlin.runCatching { roomDetachOpDeferred.await() }
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        Assert.assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as AblyException

        Assert.assertEquals("unable to detach room; room is released", exception.errorInfo.message)
        Assert.assertEquals(ErrorCodes.RoomIsReleased.errorCode, exception.errorInfo.code)
        Assert.assertEquals(HttpStatusCodes.InternalServerError, exception.errorInfo.statusCode)

        coVerify { roomLifecycle.release() }
    }
}
