package com.ably.chat.room

import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.ErrorCodes
import com.ably.chat.HttpStatusCodes
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.RoomStatus
import com.ably.chat.assertWaiter
import com.ably.utils.atomicCoroutineScope
import com.ably.utils.createRoomFeatureMocks
import io.ably.lib.types.AblyException
import io.mockk.spyk
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
}
