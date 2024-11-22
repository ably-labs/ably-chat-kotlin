package com.ably.chat.room.lifecycle

import com.ably.chat.ContributesToRoomLifecycle
import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.ErrorCodes
import com.ably.chat.HttpStatusCodes
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.ablyException
import com.ably.chat.assertWaiter
import com.ably.chat.detachCoroutine
import com.ably.chat.room.atomicCoroutineScope
import com.ably.chat.room.createMockLogger
import com.ably.chat.room.createRoomFeatureMocks
import com.ably.chat.room.setState
import io.ably.lib.realtime.ChannelState
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * Spec: CHA-RL2
 */
class DetachTest {

    private val logger = createMockLogger()

    private val roomScope = CoroutineScope(
        Dispatchers.Default.limitedParallelism(1) + CoroutineName("roomId"),
    )

    @Test
    fun `(CHA-RL2a) Detach success when room is already in detached state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Detached)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks(), logger))
        val result = kotlin.runCatching { roomLifecycle.detach() }
        Assert.assertTrue(result.isSuccess)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL2b) Detach throws exception when room in releasing state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Releasing)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks(), logger))
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
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Released)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, listOf(), logger))
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
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Failed)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, listOf(), logger))
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
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))
        val roomStatusChanges = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            roomStatusChanges.add(it)
        }

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, emptyList(), logger), recordPrivateCalls = true)
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
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        val capturedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            capturedChannels.add(firstArg())
        }

        val contributors = createRoomFeatureMocks()
        Assert.assertEquals(5, contributors.size)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger))
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
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))
        Assert.assertEquals(RoomStatus.Initialized, statusLifecycle.status) // CHA-RS3

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks(), logger))

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

    // All of the following tests cover sub-spec points under CHA-RL2h ( channel detach failure )
    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL2h1) If a one of the contributors fails to detach (enters failed state), then room enters failed state, detach op continues for other contributors`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        // Fail detach for both typing and reactions, should capture error for first failed contributor
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            if ("typing" in channel.name) { // Throw error for typing contributor
                val error = ErrorInfo("error detaching channel ${channel.name}", 500)
                channel.setState(ChannelState.failed, error)
                throw ablyException(error)
            }

            if ("reactions" in channel.name) { // Throw error for reactions contributor
                val error = ErrorInfo("error detaching channel ${channel.name}", 500)
                channel.setState(ChannelState.failed, error)
                throw ablyException(error)
            }
        }

        val contributors = createRoomFeatureMocks("1234")
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)

        val result = kotlin.runCatching { roomLifecycle.detach() }

        Assert.assertTrue(result.isFailure)
        Assert.assertEquals(RoomStatus.Failed, statusLifecycle.status)

        val exception = result.exceptionOrNull() as AblyException

        // ErrorInfo for the first failed contributor
        Assert.assertEquals(
            "failed to detach typing feature, error detaching channel 1234::\$chat::\$typingIndicators",
            exception.errorInfo.message,
        )
        Assert.assertEquals(ErrorCodes.TypingDetachmentFailed.errorCode, exception.errorInfo.code)
        Assert.assertEquals(HttpStatusCodes.InternalServerError, exception.errorInfo.statusCode)

        // The same ErrorInfo must accompany the FAILED room status
        Assert.assertSame(statusLifecycle.error, exception.errorInfo)

        // First fail for typing, second fail for reactions, third is a success
        coVerify(exactly = 3) {
            roomLifecycle["doChannelWindDown"](any<ContributesToRoomLifecycle>())
        }
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL2h2) If multiple contributors fails to detach (enters failed state), then failed status should be emitted only once`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))
        val failedRoomEvents = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            if (it.current == RoomStatus.Failed) {
                failedRoomEvents.add(it)
            }
        }

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            if ("typing" in channel.name) {
                val error = ErrorInfo("error detaching channel ${channel.name}", 500)
                channel.setState(ChannelState.failed, error)
                throw ablyException(error)
            }

            if ("reactions" in channel.name) {
                val error = ErrorInfo("error detaching channel ${channel.name}", 500)
                channel.setState(ChannelState.failed, error)
                throw ablyException(error)
            }
        }

        val contributors = createRoomFeatureMocks("1234")
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)

        val result = kotlin.runCatching { roomLifecycle.detach() }

        Assert.assertTrue(result.isFailure)
        Assert.assertEquals(RoomStatus.Failed, statusLifecycle.status)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        Assert.assertEquals(1, failedRoomEvents.size)
        Assert.assertEquals(RoomStatus.Detaching, failedRoomEvents[0].previous)
        Assert.assertEquals(RoomStatus.Failed, failedRoomEvents[0].current)

        // Emit error for the first failed contributor
        val error = failedRoomEvents[0].error as ErrorInfo
        Assert.assertEquals(
            "failed to detach typing feature, error detaching channel 1234::\$chat::\$typingIndicators",
            error.message,
        )
        Assert.assertEquals(ErrorCodes.TypingDetachmentFailed.errorCode, error.code)
        Assert.assertEquals(HttpStatusCodes.InternalServerError, error.statusCode)
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL2h3) If channel fails to detach entering another state (ATTACHED), detach will be retried until finally detached`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))
        val roomEvents = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            roomEvents.add(it)
        }

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        var failDetachTimes = 5
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            delay(200)
            if (--failDetachTimes >= 0) {
                channel.setState(ChannelState.attached)
                error("failed to detach channel")
            }
        }

        val contributors = createRoomFeatureMocks("1234")
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)

        val result = kotlin.runCatching { roomLifecycle.detach() }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Detached, statusLifecycle.status)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        Assert.assertEquals(0, roomEvents.filter { it.current == RoomStatus.Failed }.size) // Zero failed room status events emitted
        Assert.assertEquals(1, roomEvents.filter { it.current == RoomStatus.Detached }.size) // Only one detach event received

        // Channel detach success on 6th call
        coVerify(exactly = 6) {
            roomLifecycle["doChannelWindDown"](any<ContributesToRoomLifecycle>())
        }
    }
}
