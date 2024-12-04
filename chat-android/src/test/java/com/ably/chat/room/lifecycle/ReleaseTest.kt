package com.ably.chat.room.lifecycle

import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.assertWaiter
import com.ably.chat.attachCoroutine
import com.ably.chat.detachCoroutine
import com.ably.chat.room.atomicCoroutineScope
import com.ably.chat.room.createMockLogger
import com.ably.chat.room.createRoomFeatureMocks
import com.ably.chat.room.setState
import io.ably.lib.realtime.ChannelState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert
import org.junit.Test

/**
 * Spec: CHA-RL3
 */
class ReleaseTest {
    private val logger = createMockLogger()

    private val roomScope = CoroutineScope(
        Dispatchers.Default.limitedParallelism(1) + CoroutineName("roomId"),
    )

    @After
    fun tearDown() {
        unmockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
    }

    @Test
    fun `(CHA-RL3a) Release success when room is already in released state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Released)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks(), logger))
        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL3b) If room is in detached state, room is immediately transitioned to released`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Detached)
        }
        val states = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            states.add(it)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks(), logger))

        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
        Assert.assertEquals(1, states.size)
        Assert.assertEquals(RoomStatus.Released, states[0].current)
        Assert.assertEquals(RoomStatus.Detached, states[0].previous)
    }

    @Test
    fun `(CHA-RL3j) If room is in initialized state, room is immediately transitioned to released`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Initialized)
        }
        val states = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            states.add(it)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks(), logger))

        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
        Assert.assertEquals(1, states.size)
        Assert.assertEquals(RoomStatus.Released, states[0].current)
        Assert.assertEquals(RoomStatus.Initialized, states[0].previous)
    }

    @Test
    fun `(CHA-RL3l) Release op should transition room into RELEASING state, transient timeouts should be cleared`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Attached)
        }
        val roomStatusChanges = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            roomStatusChanges.add(it)
        }

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, emptyList(), logger), recordPrivateCalls = true)
        justRun { roomLifecycle invokeNoArgs "clearAllTransientDetachTimeouts" }

        roomLifecycle.release()
        Assert.assertEquals(RoomStatus.Releasing, roomStatusChanges[0].current)
        Assert.assertEquals(RoomStatus.Released, roomStatusChanges[1].current)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        verify(exactly = 1) {
            roomLifecycle invokeNoArgs "clearAllTransientDetachTimeouts"
        }
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL3d, CHA-RC2e) Release op should detach each contributor channel sequentially and room should be considered RELEASED`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Attached)
        }

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        val capturedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            capturedChannels.add(firstArg())
        }

        val contributors = createRoomFeatureMocks()
        Assert.assertEquals(5, contributors.size)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger))
        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Released, statusLifecycle.status)

        Assert.assertEquals(5, capturedChannels.size)
        repeat(5) {
            Assert.assertEquals(contributors[it].channel.name, capturedChannels[it].name)
        }
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[1].name)
        Assert.assertEquals("1234::\$chat::\$typingIndicators", capturedChannels[2].name)
        Assert.assertEquals("1234::\$chat::\$reactions", capturedChannels[3].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[4].name)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL3e, CHA-RC2e) If a one of the contributors is in failed state, release op continues for other contributors`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Attached)
        }

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        val capturedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            capturedChannels.add(firstArg())
        }

        val contributors = createRoomFeatureMocks("1234")

        // Put typing contributor into failed state, so it won't be detached
        contributors.first { it.channel.name.contains("typing") }.apply {
            channel.setState(ChannelState.failed)
        }

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)

        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Released, statusLifecycle.status)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        Assert.assertEquals(4, capturedChannels.size)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[1].name)
        Assert.assertEquals("1234::\$chat::\$reactions", capturedChannels[2].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[3].name)
    }

    @Test
    fun `(CHA-RL3f) If a one of the contributors fails to detach, release op continues for all contributors after 250ms delay`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Attached)
        }

        val roomEvents = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            roomEvents.add(it)
        }

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        var failDetachTimes = 5
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            delay(200)
            if (--failDetachTimes >= 0) {
                error("failed to detach channel")
            }
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            channel.setState(ChannelState.detached)
        }

        val contributors = createRoomFeatureMocks("1234")
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)

        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Released, statusLifecycle.status)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        Assert.assertEquals(2, roomEvents.size)
        Assert.assertEquals(RoomStatus.Releasing, roomEvents[0].current)
        Assert.assertEquals(RoomStatus.Released, roomEvents[1].current)

        // Channel release success on 6th call
        coVerify(exactly = 6) {
            roomLifecycle invokeNoArgs "doRelease"
        }
    }

    @Test
    fun `(CHA-RL3g, CHA-RC2e) Release op continues till all contributors enters either DETACHED or FAILED state`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Attached)
        }

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        var failDetachTimes = 5
        val capturedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            delay(200)
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            if (--failDetachTimes >= 0) {
                channel.setState(listOf(ChannelState.attached, ChannelState.suspended).random())
                error("failed to detach channel")
            }
            channel.setState(listOf(ChannelState.detached, ChannelState.failed).random())
            capturedChannels.add(channel)
        }

        val contributors = createRoomFeatureMocks()
        Assert.assertEquals(5, contributors.size)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)
        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Released, statusLifecycle.status)

        Assert.assertEquals(5, capturedChannels.size)
        repeat(5) {
            Assert.assertEquals(contributors[it].channel.name, capturedChannels[it].name)
        }
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[1].name)
        Assert.assertEquals("1234::\$chat::\$typingIndicators", capturedChannels[2].name)
        Assert.assertEquals("1234::\$chat::\$reactions", capturedChannels[3].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedChannels[4].name)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        // Channel release success on 6th call
        coVerify(exactly = 6) {
            roomLifecycle invokeNoArgs "doRelease"
        }
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL3h, CHA-RC2e) Upon channel release, underlying Realtime Channels are released from the core SDK prevent leakage`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Attached)
        }

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            channel.setState(ChannelState.detached)
        }

        val contributors = createRoomFeatureMocks()
        Assert.assertEquals(5, contributors.size)

        val releasedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        for (contributor in contributors) {
            every { contributor.release() } answers {
                releasedChannels.add(contributor.channel)
            }
        }

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger))
        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Released, statusLifecycle.status)

        Assert.assertEquals(5, releasedChannels.size)
        repeat(5) {
            Assert.assertEquals(contributors[it].channel.name, releasedChannels[it].name)
        }
        Assert.assertEquals("1234::\$chat::\$chatMessages", releasedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", releasedChannels[1].name)
        Assert.assertEquals("1234::\$chat::\$typingIndicators", releasedChannels[2].name)
        Assert.assertEquals("1234::\$chat::\$reactions", releasedChannels[3].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", releasedChannels[4].name)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        for (contributor in contributors) {
            verify(exactly = 1) {
                contributor.release()
            }
        }
    }

    @Test
    fun `(CHA-RL3k) Release op should wait for existing operation as per (CHA-RL7)`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger)).apply {
            setStatus(RoomStatus.Attached)
        }

        val roomEvents = mutableListOf<RoomStatusChange>()

        statusLifecycle.onChange {
            roomEvents.add(it)
        }

        mockkStatic(io.ably.lib.realtime.Channel::detachCoroutine)
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            channel.setState(ChannelState.detached)
        }

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks(), logger))

        val roomAttached = Channel<Boolean>()
        coEvery {
            roomLifecycle.attach()
        } coAnswers {
            roomLifecycle.atomicCoroutineScope().async {
                statusLifecycle.setStatus(RoomStatus.Attaching)
                roomAttached.receive()
                statusLifecycle.setStatus(RoomStatus.Attached)
            }
        }

        // ATTACH op started from separate coroutine
        launch { roomLifecycle.attach() }
        assertWaiter { !roomLifecycle.atomicCoroutineScope().finishedProcessing }
        Assert.assertEquals(0, roomLifecycle.atomicCoroutineScope().pendingJobCount) // no queued jobs, one job running
        assertWaiter { statusLifecycle.status == RoomStatus.Attaching }

        // Release op started from separate coroutine
        val roomReleaseOpDeferred = async { roomLifecycle.release() }
        assertWaiter { roomLifecycle.atomicCoroutineScope().pendingJobCount == 1 } // release op queued
        Assert.assertEquals(RoomStatus.Attaching, statusLifecycle.status)

        // Finish room ATTACH
        roomAttached.send(true)

        val result = kotlin.runCatching { roomReleaseOpDeferred.await() }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Released, statusLifecycle.status)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        Assert.assertEquals(4, roomEvents.size)
        Assert.assertEquals(RoomStatus.Attaching, roomEvents[0].current)
        Assert.assertEquals(RoomStatus.Attached, roomEvents[1].current)
        Assert.assertEquals(RoomStatus.Releasing, roomEvents[2].current)
        Assert.assertEquals(RoomStatus.Released, roomEvents[3].current)

        coVerify { roomLifecycle.attach() }
    }
}
