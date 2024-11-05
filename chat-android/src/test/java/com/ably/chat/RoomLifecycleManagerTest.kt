package com.ably.chat

import io.ably.lib.types.AblyException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
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

class RoomLifecycleManagerTest {

    private val roomScope = CoroutineScope(
        Dispatchers.Default.limitedParallelism(1) + CoroutineName("roomId"),
    )

    @Test
    fun `(CHA-RL1a) Attach success when channel in already in attached state`() = runTest {
        val status = spyk<DefaultStatus>().apply {
            setStatus(RoomLifecycle.Attached)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, status, emptyList()))
        val result = kotlin.runCatching { roomLifecycle.attach() }
        Assert.assertTrue(result.isSuccess)
    }

    @Test
    fun `(CHA-RL1b) Attach throws exception when channel in releasing state`() = runTest {
        val status = spyk<DefaultStatus>().apply {
            setStatus(RoomLifecycle.Releasing)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, status, emptyList()))
        val exception = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                roomLifecycle.attach()
            }
        }
        Assert.assertEquals("unable to attach room; room is releasing", exception.errorInfo.message)
        Assert.assertEquals(102_102, exception.errorInfo.code)
        Assert.assertEquals(500, exception.errorInfo.statusCode)
    }

    @Test
    fun `(CHA-RL1c) Attach throws exception when channel in released state`() = runTest {
        val status = spyk<DefaultStatus>().apply {
            setStatus(RoomLifecycle.Released)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, status, listOf()))
        val exception = Assert.assertThrows(AblyException::class.java) {
            runBlocking {
                roomLifecycle.attach()
            }
        }
        Assert.assertEquals("unable to attach room; room is released", exception.errorInfo.message)
        Assert.assertEquals(102_103, exception.errorInfo.code)
        Assert.assertEquals(500, exception.errorInfo.statusCode)
    }

    @Test
    fun `(CHA-RL1d) Attach op should wait for existing operation as per (CHA-RL7)`() = runTest {
        val status = spyk<DefaultStatus>().apply {
            setStatus(RoomLifecycle.Released)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, status, emptyList()))

        val channelReleased = Channel<Unit>()
        coEvery {
            roomLifecycle.release()
        } coAnswers {
            roomLifecycle.atomicCoroutineScope.async {
                status.setStatus(RoomLifecycle.Releasing)
                channelReleased.receive()
                status.setStatus(RoomLifecycle.Released)
            }
        }
        launch { roomLifecycle.release() }

        // Release op started
        assertWaiter { !roomLifecycle.atomicCoroutineScope.finishedProcessing }
        assertWaiter { status.current == RoomLifecycle.Releasing }

        val roomAttachOpDeferred = async(SupervisorJob()) { roomLifecycle.attach() }
        Assert.assertEquals(RoomLifecycle.Releasing, status.current)
        channelReleased.send(Unit)

        // Release op finished
        assertWaiter { roomLifecycle.atomicCoroutineScope.finishedProcessing }
        assertWaiter { status.current == RoomLifecycle.Released }

        val result = kotlin.runCatching { roomAttachOpDeferred.await() }
        Assert.assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as AblyException

        Assert.assertEquals("unable to attach room; room is released", exception.errorInfo.message)
        Assert.assertEquals(102_103, exception.errorInfo.code)
        Assert.assertEquals(500, exception.errorInfo.statusCode)

        coVerify { roomLifecycle.release() }
    }

    @Test
    fun `(CHA-RL1e) Attach op should transition room into ATTACHING state`() = runTest {
    }

    @Test
    fun `(CHA-RL1f) Attach op should attach each contributor channel sequentially`() = runTest {
    }

    @Test
    fun `(CHA-RL1g) When all contributor channels ATTACH, op is complete and room should be considered ATTACHED`() = runTest {
    }
}
