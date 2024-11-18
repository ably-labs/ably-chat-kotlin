package com.ably.chat.room

import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.HttpStatusCodes
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.RoomStatus
import com.ably.chat.assertWaiter
import com.ably.chat.attachCoroutine
import com.ably.chat.detachCoroutine
import com.ably.utils.atomicCoroutineScope
import com.ably.utils.createRoomFeatureMocks
import com.ably.utils.retry
import com.ably.utils.setState
import io.ably.lib.realtime.ChannelState
import io.ably.lib.realtime.ChannelStateListener
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * Spec: CHA-RL5
 */
class RetryTest {
    private val roomScope = CoroutineScope(
        Dispatchers.Default.limitedParallelism(1) + CoroutineName("roomId"),
    )

    @Test
    fun `(CHA-RL5a) Retry detaches all contributors except the one that's provided (based on underlying channel CHA-RL5a)`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>()

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coJustRun { any<io.ably.lib.realtime.Channel>().attachCoroutine() }

        val capturedDetachedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            capturedDetachedChannels.add(firstArg())
        }

        val contributors = createRoomFeatureMocks()
        Assert.assertEquals(5, contributors.size)
        val messagesContributor = contributors.first { it.featureName == "messages" }
        messagesContributor.channel.setState(ChannelState.attached)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors))

        val result = kotlin.runCatching { roomLifecycle.retry(messagesContributor) }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Attached, statusLifecycle.status)

        Assert.assertEquals(2, capturedDetachedChannels.size)

        Assert.assertEquals("1234::\$chat::\$typingIndicators", capturedDetachedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$reactions", capturedDetachedChannels[1].name)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL5c) If one of the contributor channel goes into failed state during channel windown (CHA-RL5a), then the room enters failed state and retry operation stops`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>()

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coJustRun { any<io.ably.lib.realtime.Channel>().attachCoroutine() }

        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            if (channel.name.contains("typing")) {
                channel.setState(ChannelState.failed)
                error("${channel.name} went into FAILED state")
            }
        }

        val contributors = createRoomFeatureMocks()

        val messagesContributor = contributors.first { it.featureName == "messages" }
        messagesContributor.channel.setState(ChannelState.attached)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors))

        val result = kotlin.runCatching { roomLifecycle.retry(messagesContributor) }
        Assert.assertTrue(result.isFailure)
        Assert.assertEquals(RoomStatus.Failed, statusLifecycle.status)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL5c) If one of the contributor channel goes into failed state during Retry, then the room enters failed state and retry operation stops`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>()

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coJustRun { any<io.ably.lib.realtime.Channel>().detachCoroutine() }

        coEvery { any<io.ably.lib.realtime.Channel>().attachCoroutine() } coAnswers {
            val channel = firstArg<io.ably.lib.realtime.Channel>()
            if (channel.name.contains("typing")) {
                channel.setState(ChannelState.failed)
                error("${channel.name} went into FAILED state")
            }
        }

        val contributors = createRoomFeatureMocks()

        val messagesContributor = contributors.first { it.featureName == "messages" }
        messagesContributor.channel.setState(ChannelState.attached)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors))

        roomLifecycle.retry(messagesContributor)
        Assert.assertEquals(RoomStatus.Failed, statusLifecycle.status)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL5d) If all contributor channels goes into detached (except one provided in suspended state), provided contributor starts attach operation and waits for ATTACHED or FAILED state`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>()

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coJustRun { any<io.ably.lib.realtime.Channel>().attachCoroutine() }
        coJustRun { any<io.ably.lib.realtime.Channel>().detachCoroutine() }

        val contributors = createRoomFeatureMocks()
        val messagesContributor = contributors.first { it.featureName == "messages" }

        every {
            messagesContributor.channel.once(eq(ChannelState.attached), any<ChannelStateListener>())
        } answers {
            secondArg<ChannelStateListener>().onChannelStateChanged(null)
        }
        justRun {
            messagesContributor.channel.once(eq(ChannelState.failed), any<ChannelStateListener>())
        }

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors))

        val result = kotlin.runCatching { roomLifecycle.retry(messagesContributor) }

        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Attached, statusLifecycle.status)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        verify {
            messagesContributor.channel.once(eq(ChannelState.attached), any<ChannelStateListener>())
        }
        verify {
            messagesContributor.channel.once(eq(ChannelState.failed), any<ChannelStateListener>())
        }
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL5e) If, during the CHA-RL5d wait, the contributor channel becomes failed, then the room enters failed state and retry operation stops`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>()

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        coJustRun { any<io.ably.lib.realtime.Channel>().attachCoroutine() }
        coJustRun { any<io.ably.lib.realtime.Channel>().detachCoroutine() }

        val contributors = createRoomFeatureMocks()
        val messagesContributor = contributors.first { it.featureName == "messages" }
        messagesContributor.channel.setState(ChannelState.failed)
        messagesContributor.channel.reason = ErrorInfo("Failed channel messages", HttpStatusCodes.InternalServerError)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors))

        val result = kotlin.runCatching { roomLifecycle.retry(messagesContributor) }
        Assert.assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as AblyException
        Assert.assertEquals("Failed channel messages", exception.errorInfo.message)
        Assert.assertEquals(RoomStatus.Failed, statusLifecycle.status)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Suppress("MaximumLineLength")
    @Test
    fun `(CHA-RL5f) If, during the CHA-RL5d wait, the contributor channel becomes ATTACHED, then attach operation continues for other contributors as per CHA-RL1e`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>()

        mockkStatic(io.ably.lib.realtime.Channel::attachCoroutine)
        val capturedAttachedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().attachCoroutine() } coAnswers {
            capturedAttachedChannels.add(firstArg())
        }

        val capturedDetachedChannels = mutableListOf<io.ably.lib.realtime.Channel>()
        coEvery { any<io.ably.lib.realtime.Channel>().detachCoroutine() } coAnswers {
            capturedDetachedChannels.add(firstArg())
        }

        val contributors = createRoomFeatureMocks()
        Assert.assertEquals(5, contributors.size)
        val messagesContributor = contributors.first { it.featureName == "messages" }
        messagesContributor.channel.setState(ChannelState.attached)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors))

        val result = kotlin.runCatching { roomLifecycle.retry(messagesContributor) }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Attached, statusLifecycle.status)

        Assert.assertEquals(2, capturedDetachedChannels.size)

        Assert.assertEquals("1234::\$chat::\$typingIndicators", capturedDetachedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$reactions", capturedDetachedChannels[1].name)

        Assert.assertEquals(5, capturedAttachedChannels.size)

        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedAttachedChannels[0].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedAttachedChannels[1].name)
        Assert.assertEquals("1234::\$chat::\$chatMessages", capturedAttachedChannels[2].name)
        Assert.assertEquals("1234::\$chat::\$typingIndicators", capturedAttachedChannels[3].name)
        Assert.assertEquals("1234::\$chat::\$reactions", capturedAttachedChannels[4].name)

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }
}
