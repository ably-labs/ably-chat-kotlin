package com.ably.chat.room

import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.assertWaiter
import com.ably.utils.atomicCoroutineScope
import com.ably.utils.createRoomFeatureMocks
import io.mockk.spyk
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Test

/**
 * Spec: CHA-RL3
 */
class ReleaseTest {
    private val roomScope = CoroutineScope(
        Dispatchers.Default.limitedParallelism(1) + CoroutineName("roomId"),
    )

    @Test
    fun `(CHA-RL3a) Release success when room is already in released state`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>().apply {
            setStatus(RoomStatus.Released)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks()))
        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
    }

    @Test
    fun `(CHA-RL3b) If room is in detached state, room is immediately transitioned to released`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>().apply {
            setStatus(RoomStatus.Detached)
        }
        val states = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            states.add(it)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks()))

        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
        Assert.assertEquals(1, states.size)
        Assert.assertEquals(RoomStatus.Released, states[0].current)
        Assert.assertEquals(RoomStatus.Detached, states[0].previous)
    }

    @Test
    fun `(CHA-RL3j) If room is in initialized state, room is immediately transitioned to released`() = runTest {
        val statusLifecycle = spyk<DefaultRoomLifecycle>().apply {
            setStatus(RoomStatus.Initialized)
        }
        val states = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            states.add(it)
        }
        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks()))

        val result = kotlin.runCatching { roomLifecycle.release() }
        Assert.assertTrue(result.isSuccess)
        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }
        Assert.assertEquals(1, states.size)
        Assert.assertEquals(RoomStatus.Released, states[0].current)
        Assert.assertEquals(RoomStatus.Initialized, states[0].previous)
    }
}
