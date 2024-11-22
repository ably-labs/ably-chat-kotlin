package com.ably.chat.room.lifecycle

import com.ably.chat.ContributesToRoomLifecycle
import com.ably.chat.DefaultRoomAttachmentResult
import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.assertWaiter
import com.ably.chat.room.atomicCoroutineScope
import com.ably.chat.room.atomicRetry
import com.ably.chat.room.createMockLogger
import com.ably.chat.room.createRoomFeatureMocks
import io.mockk.coEvery
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert
import org.junit.Test

/**
 * Room lifecycle operations are atomic and exclusive operations: one operation must complete (whether that’s failure or success) before the next one may begin.
 * Spec: CHA-RL7
 */
class PrecedenceTest {
    private val logger = createMockLogger()

    private val roomScope = CoroutineScope(
        Dispatchers.Default.limitedParallelism(1) + CoroutineName("roomId"),
    )

    /**
     * 1. RETRY (CHA-RL7a1) - Internal operation.
     * 2. RELEASE (CHA-RL7a2) - External operation.
     * 3. ATTACH or DETACH (CHA-RL7a3) - External operation.
     */
    @Suppress("LongMethod")
    @Test
    fun `(CHA-RL7a) If multiple operations are scheduled to run, they run as per LifecycleOperationPrecedence`() = runTest {
        val statusLifecycle = spyk(DefaultRoomLifecycle(logger))
        val roomStatusChanges = mutableListOf<RoomStatusChange>()
        statusLifecycle.onChange {
            roomStatusChanges.add(it)
        }

        val contributors = createRoomFeatureMocks("1234")
        Assert.assertEquals(5, contributors.size)

        val roomLifecycle = spyk(RoomLifecycleManager(roomScope, statusLifecycle, contributors, logger), recordPrivateCalls = true)
        // Internal operation
        coEvery { roomLifecycle["doRetry"](any<ContributesToRoomLifecycle>()) } coAnswers {
            delay(200)
            statusLifecycle.setStatus(RoomStatus.Suspended)
            statusLifecycle.setStatus(RoomStatus.Failed)
            error("throwing error to avoid continuation getting stuck :( ")
        }
        // Attach operation
        coEvery { roomLifecycle invokeNoArgs "doAttach" } coAnswers {
            delay(500)
            statusLifecycle.setStatus(RoomStatus.Attached)
            DefaultRoomAttachmentResult()
        }
        // Detach operation
        coEvery { roomLifecycle invokeNoArgs "doDetach" } coAnswers {
            delay(200)
            statusLifecycle.setStatus(RoomStatus.Detached)
        }
        // Release operation
        coEvery { roomLifecycle invokeNoArgs "releaseChannels" } coAnswers {
            delay(200)
            statusLifecycle.setStatus(RoomStatus.Released)
        }
        withContext(Dispatchers.Default.limitedParallelism(1)) {
            launch {
                roomLifecycle.attach()
            }
            assertWaiter { !roomLifecycle.atomicCoroutineScope().finishedProcessing } // Get attach into processing
            launch {
                kotlin.runCatching { roomLifecycle.detach() } // Attach in process, Queue -> Detach
            }
            launch {
                kotlin.runCatching { roomLifecycle.atomicRetry(contributors[0]) } // Attach in process, Queue -> Retry, Detach
            }
            launch {
                kotlin.runCatching { roomLifecycle.attach() } // Attach in process, Queue -> Retry, Detach, Attach
            }

            // Because of release, detach and attach won't be able to execute their operations
            launch {
                roomLifecycle.release() // Attach in process, Queue -> Retry, Release, Detach, Attach
            }
        }

        assertWaiter { roomLifecycle.atomicCoroutineScope().finishedProcessing }

        Assert.assertEquals(6, roomStatusChanges.size)
        Assert.assertEquals(RoomStatus.Attaching, roomStatusChanges[0].current)
        Assert.assertEquals(RoomStatus.Attached, roomStatusChanges[1].current)
        Assert.assertEquals(RoomStatus.Suspended, roomStatusChanges[2].current)
        Assert.assertEquals(RoomStatus.Failed, roomStatusChanges[3].current)
        Assert.assertEquals(RoomStatus.Releasing, roomStatusChanges[4].current)
        Assert.assertEquals(RoomStatus.Released, roomStatusChanges[5].current)

        verify {
            roomLifecycle["doRetry"](any<ContributesToRoomLifecycle>())
            roomLifecycle invokeNoArgs "doAttach"
            roomLifecycle invokeNoArgs "releaseChannels"
        }

        verify(exactly = 0) {
            roomLifecycle invokeNoArgs "doDetach"
        }
    }
}
