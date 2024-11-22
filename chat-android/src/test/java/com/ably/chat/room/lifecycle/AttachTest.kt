package com.ably.chat.room.lifecycle

import com.ably.chat.ContributesToRoomLifecycle
import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.ErrorCode
import com.ably.chat.HttpStatusCode
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.ablyException
import com.ably.chat.assertWaiter
import com.ably.chat.attachCoroutine
import com.ably.chat.detachCoroutine
import com.ably.chat.room.atomicCoroutineScope
import com.ably.chat.room.createMockLogger
import com.ably.chat.room.createRoomFeatureMocks
import com.ably.chat.room.setState
import com.ably.chat.serverError
import com.ably.chat.setPrivateField
import io.ably.lib.realtime.ChannelState
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkStatic
import io.mockk.slot
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
 * Spec: CHA-RL1
 */
class AttachTest {

    private val logger = createMockLogger()

    private val roomScope = CoroutineScope(
        Dispatchers.Default.limitedParallelism(1) + CoroutineName("roomId"),
    )

    @Test
    fun `(CHA-RL1a) Attach success when room is already in attached state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Attached)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks(), logger))
        val result = kotlin.runCatching { roomLifecycle.attach() }
        Assert.assertTrue(result.isSuccess)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL1b) Attach throws exception when room in releasing state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Releasing)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks(), logger))
        val exception = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                roomLifecycle.attach()
            }
        }
        Assert.assertEquals("unable to attach room; room is releasing", exception.errorInfo.message)
        Assert.assertEquals(ErrorCode.RoomIsReleasing.code, exception.errorInfo.code)
        Assert.assertEquals(HttpStatusCode.InternalServerError, exception.errorInfo.statusCode)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL1c) Attach throws exception when room in released state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Released)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, listOf(), logger))
        val exception = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                roomLifecycle.attach()
            }
        }
        Assert.assertEquals("unable to attach room; room is released", exception.errorInfo.message)
        Assert.assertEquals(ErrorCode.RoomIsReleased.code, exception.errorInfo.code)
        Assert.assertEquals(HttpStatusCode.InternalServerError, exception.errorInfo.statusCode)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL1d) Attach op should wait for existing operation as per (CHA-RL7)`() = runTest {
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

        // Attach op started from separate coroutine
        val roomAttachOpDeferred = async(SupervisorJob()) { roomLifecycle.attach() }
        assertWaiter { roomLifecycle.atomicCoroutineScope().pendingJobCount == 1 } // attach op queued
        Assert.assertEquals(RoomStatus.Releasing, statusLifecycle.status)

        // Finish release op, so ATTACH op can start
        roomReleased.send(true)
        assertWaiter { statusLifecycle.status == RoomStatus.Released }

        val result = kotlin.runCatching { roomAttachOpDeferred.await() }
        Assert.assertTrue(roomLifecycle.atomicCoroutineScope().finishedProcessing)

        Assert.assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as AblyException

        Assert.assertEquals("unable to attach room; room is released", exception.errorInfo.message)
        Assert.assertEquals(ErrorCode.RoomIsReleased.code, exception.errorInfo.code)
        Assert.assertEquals(HttpStatusCode.InternalServerError, exception.errorInfo.statusCode)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        coVerify { roomLifecycle.release() }
    }

    @Test
    fun `(CHA-RL1e) Attach op should transition room into ATTACHING state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))
        val roomStatusChanges = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            roomStatusChanges.add(it)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, emptyList(), logger))
        roomLifecycle.attach()

        Assert.assertEquals(RoomStatus.Attaching, roomStatusChanges[0].current)
        Assert.assertEquals(RoomStatus.Attached, roomStatusChanges[1].current)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL1f) Attach op should attach each contributor channel sequentially`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        val capturedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().attachCoroutine() } coAnswers {
            capturedChannels.add(firstArg())
        }

        val contributors = createRoomFeatureMocks()
        Assert.assertEquals(5, contributors.size)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger))
        roomLifecycle.attach()
        val result = kotlin.runCatching { roomLifecycle.attach() }
        Assert.assertTrue(result.isSuccess)

        Assert.assertEquals(5, capturedChannels.size)
        repeat(5) {
            Assert.assertEquals(contributors[it].channel.name, capturedChannels[it].name)
        }
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[1].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[2].name)
        Assert.assertEquals("1234::\$chat::\$typingIndicators", capturedChannels[3].name)
        Assert.assertEquals("1234::\$chat::\$reactions", capturedChannels[4].name)
    }

    @Test
    fun `(CHA-RL1g) When all contributor channels ATTACH, op is complete and room should be considered ATTACHED`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coEvery { any<io.ably.lib.realtime.Channel>().attachCoroutine() } returns Unit

        val contributors = createRoomFeatureMocks("1234")
        val contributorErrors = mutableListOf<ErrorInfo>()
        for (contributor in contributors) {
            every {
                contributor.discontinuityDetected(capture(contributorErrors))
            } returns Unit
        }
        Assert.assertEquals(5, contributors.size)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true) {
            val pendingDiscontinuityEvents = mutableMapOf<ContributesToRoomLifecycle, ErrorInfo?>().apply {
                for (contributor in contributors) {
                    put(contributor, ErrorInfo("${contributor.channel.name} error", 500))
                }
            }
            this.setPrivateField("pendingDiscontinuityEvents", pendingDiscontinuityEvents)
        }
        justRun { roomLifecycle invokeNoArgs "clearAllTransientDetachTimeouts" }

        val result = kotlin.runCatching { roomLifecycle.attach() }

        // CHA-RL1g1
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Attached, statusLifecycle.status)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        // CHA-RL1g2
        verify(exactly = 1) {
            for (contributor in contributors) {
                contributor.discontinuityDetected(any<ErrorInfo>())
            }
        }
        Assert.assertEquals(5, contributorErrors.size)

        // CHA-RL1g3
        verify(exactly = 1) {
            roomLifecycle invokeNoArgs "clearAllTransientDetachTimeouts"
        }
    }

    // All of the following tests cover sub-spec points under CHA-RL1h ( channel attach failure )
    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL1h1, CHA-RL1h2) If a one of the contributors fails to attach (enters suspended state), attach throws related error and room enters suspended state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coEvery { any<io.ably.lib.realtime.Channel>().attachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            if ("reactions" in channel.name) {
                // Throw error for typing contributor, likely to throw because it uses different channel
                channel.setState(ChannelState.suspended)
                throw serverError("error attaching channel ${channel.name}")
            }
        }

        val contributors = createRoomFeatureMocks("1234")
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)

        val result = kotlin.runCatching { roomLifecycle.attach() }

        Assert.assertTrue(result.isFailure)
        Assert.assertEquals(RoomStatus.Suspended, statusLifecycle.status)

        val exception = result.exceptionOrNull() as AblyException

        Assert.assertEquals("failed to attach reactions feature", exception.errorInfo.message)
        Assert.assertEquals(ErrorCode.ReactionsAttachmentFailed.code, exception.errorInfo.code)
        Assert.assertEquals(500, exception.errorInfo.statusCode)
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL1h1, CHA-RL1h4) If a one of the contributors fails to attach (enters failed state), attach throws related error and room enters failed state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coEvery { any<io.ably.lib.realtime.Channel>().attachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            if ("typing" in channel.name) {
                // Throw error for typing contributor, likely to throw because it uses different channel
                val error = ErrorInfo("error attaching channel ${channel.name}", 500)
                channel.setState(ChannelState.failed, error)
                throw ablyException(error)
            }
        }

        val contributors = createRoomFeatureMocks("1234")
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)

        val result = kotlin.runCatching { roomLifecycle.attach() }

        Assert.assertTrue(result.isFailure)
        Assert.assertEquals(RoomStatus.Failed, statusLifecycle.status)

        val exception = result.exceptionOrNull() as AblyException

        Assert.assertEquals(
            "failed to attach typing feature, error attaching channel 1234::\$chat::\$typingIndicators",
            exception.errorInfo.message,
        )
        Assert.assertEquals(ErrorCode.TypingAttachmentFailed.code, exception.errorInfo.code)
        Assert.assertEquals(500, exception.errorInfo.statusCode)
    }

    @Test
    fun `(CHA-RL1h3) When room enters suspended state (CHA-RL1h2), it should enter recovery loop as per (CHA-RL5)`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coEvery { any<io.ably.lib.realtime.Channel>().attachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            if ("reactions" in channel.name) {
                // Throw error for typing contributor, likely to throw because it uses different channel
                channel.setState(ChannelState.suspended)
                throw serverError("error attaching channel ${channel.name}")
            }
        }

        val contributors = createRoomFeatureMocks("1234")
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)

        val capturedContributors = slot<ContributesToRoomLifecycle>()

        // Behaviour for CHA-RL5 will be tested as a part of sub spec for the same
        coEvery { roomLifecycle["doRetry"](capture(capturedContributors)) } coAnswers {
            delay(1000)
        }

        val result = kotlin.runCatching { roomLifecycle.attach() }
        assertWaiter { !roomLifecycle.atomicCoroutineScope().finishedProcessing } // internal attach retry in progress

        Assert.assertTrue(result.isFailure)
        Assert.assertEquals(RoomStatus.Suspended, statusLifecycle.status)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing } // Wait for doRetry to finish

        coVerify(exactly = 1) {
            roomLifecycle["doRetry"](capturedContributors.captured)
        }
        Assert.assertEquals("reactions", capturedContributors.captured.featureName)
    }

    @Test
    fun `(CHA-RL1h5) When room enters failed state (CHA-RL1h4), room detach all channels not in failed state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coEvery { any<io.ably.lib.realtime.Channel>().attachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            if ("typing" in channel.name) {
                // Throw error for typing contributor, likely to throw because it uses different channel
                val error = ErrorInfo("error attaching channel ${channel.name}", 500)
                channel.setState(ChannelState.failed, error)
                throw ablyException(error)
            }
        }

        val detachedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            delay(200)
            detachedChannels.add(firstArg())
        }

        val contributors = createRoomFeatureMocks("1234")
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)

        val result = kotlin.runCatching { roomLifecycle.attach() }
        Assert.assertFalse(roomLifecycle.atomicCoroutineScope().finishedProcessing) // Internal channels detach in progress

        Assert.assertTrue(result.isFailure)
        Assert.assertEquals(RoomStatus.Failed, statusLifecycle.status)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing } // Wait for channels detach

        coVerify {
            roomLifecycle invokeNoArgs "runDownChannelsOnFailedAttach"
        }

        coVerify(exactly = 1) {
            roomLifecycle["doChannelWindDown"](any<ContributesToRoomLifecycle>())
        }

        Assert.assertEquals("1234::\$chat::\$chatMessages", detachedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", detachedChannels[1].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", detachedChannels[2].name)
        Assert.assertEquals("1234::\$chat::\$reactions", detachedChannels[3].name)
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL1h6) When room enters failed state, when CHA-RL1h5 fails to detach, op will be repeated till all channels are detached`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coEvery { any<io.ably.lib.realtime.Channel>().attachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            if ("typing" in channel.name) {
                // Throw error for typing contributor, likely to throw because it uses different channel
                val error = ErrorInfo("error attaching channel ${channel.name}", 500)
                channel.setState(ChannelState.failed, error)
                throw ablyException(error)
            }
        }

        var failDetachTimes = 5
        val detachedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            delay(200)
            if (--failDetachTimes >= 0) {
                error("failed to detach channel")
            }
            detachedChannels.add(firstArg())
        }

        val contributors = createRoomFeatureMocks("1234")
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)

        val result = kotlin.runCatching { roomLifecycle.attach() }
        Assert.assertFalse(roomLifecycle.atomicCoroutineScope().finishedProcessing) // Internal channels detach in progress

        Assert.assertTrue(result.isFailure)
        Assert.assertEquals(RoomStatus.Failed, statusLifecycle.status)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing } // Wait for channels detach

        coVerify {
            roomLifecycle invokeNoArgs "runDownChannelsOnFailedAttach"
        }

        // Channel detach success on 6th call
        coVerify(exactly = 6) {
            roomLifecycle["doChannelWindDown"](any<ContributesToRoomLifecycle>())
        }

        Assert.assertEquals("1234::\$chat::\$chatMessages", detachedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", detachedChannels[1].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", detachedChannels[2].name)
        Assert.assertEquals("1234::\$chat::\$reactions", detachedChannels[3].name)
    }
}
