package com.ably.chat.room

import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.assertWaiter
import com.ably.utils.atomicCoroutineScope
import com.ably.utils.createRoomFeatureMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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

    @Test
    fun `(CHA-RL3c) If room is in Releasing status, op should return result of pending release op`() = runTest {
        // TODO - need more clarity regarding test case as per https://github.com/ably/ably-chat-js/issues/399
        // TODO - There might be a need to rephrase the spec statement
        val statusLifecycle = spyk<DefaultRoomLifecycle>()
        Assert.assertEquals(RoomStatus.Initializing, statusLifecycle.status)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, createRoomFeatureMocks()))

        val roomReleased = Channel<Boolean>()
        var callOriginalRelease = false
        coEvery {
            roomLifecycle.release()
        } coAnswers {
            roomLifecycle.atomicCoroutineScope().async {
                if (callOriginalRelease) {
                    callOriginal()
                } else {
                    statusLifecycle.setStatus(RoomStatus.Releasing)
                    roomReleased.receive()
                    statusLifecycle.setStatus(RoomStatus.Released)
                }
            }
        }

        // Release op started from separate coroutine
        launch { roomLifecycle.release() }
        assertWaiter { !roomLifecycle.atomicCoroutineScope().finishedProcessing }
        Assert.assertEquals(0, roomLifecycle.atomicCoroutineScope().pendingJobCount) // no queued jobs, release op running
        assertWaiter { statusLifecycle.status == RoomStatus.Releasing }

        // Original release op started from separate coroutine
        callOriginalRelease = true
        val roomReleaseOpDeferred = async { roomLifecycle.release() }
        assertWaiter { roomLifecycle.atomicCoroutineScope().pendingJobCount == 1 } // release op queued
        Assert.assertEquals(RoomStatus.Releasing, statusLifecycle.status)

        // Finish previous release op, so new Release op can start
        roomReleased.send(true)
        assertWaiter { statusLifecycle.status == RoomStatus.Released }

        val result = kotlin.runCatching { roomReleaseOpDeferred.await() }
        Assert.assertTrue(result.isSuccess)
        Assert.assertEquals(RoomStatus.Released, statusLifecycle.status)

        Assert.assertTrue(roomLifecycle.atomicCoroutineScope().finishedProcessing)

        coVerify { roomLifecycle.release() }
    }
}
