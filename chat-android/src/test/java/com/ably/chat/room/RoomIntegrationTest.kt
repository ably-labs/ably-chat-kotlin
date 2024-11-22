package com.ably.chat.room

import com.ably.chat.ChatClient
import com.ably.chat.Room
import com.ably.chat.RoomStatus
import com.ably.chat.RoomStatusChange
import com.ably.chat.Sandbox
import com.ably.chat.assertWaiter
import com.ably.chat.createSandboxChatClient
import com.ably.chat.getConnectedChatClient
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class RoomIntegrationTest {
    private lateinit var sandbox: Sandbox

    @Before
    fun setUp() = runTest {
        sandbox = Sandbox.createInstance()
    }

    private suspend fun validateAllOps(room: Room, chatClient: ChatClient) {
        Assert.assertEquals(RoomStatus.Initialized, room.status)

        // Listen for underlying state changes
        val stateChanges = mutableListOf<RoomStatusChange>()
        room.onStatusChange {
            stateChanges.add(it)
        }

        // Perform attach operation
        room.attach()
        Assert.assertEquals(RoomStatus.Attached, room.status)

        // Perform detach operation
        room.detach()
        Assert.assertEquals(RoomStatus.Detached, room.status)

        // Perform release operation
        chatClient.rooms.release(room.roomId)
        Assert.assertEquals(RoomStatus.Released, room.status)

        assertWaiter { room.LifecycleManager.atomicCoroutineScope().finishedProcessing }

        Assert.assertEquals(5, stateChanges.size)
        Assert.assertEquals(RoomStatus.Attaching, stateChanges[0].current)
        Assert.assertEquals(RoomStatus.Attached, stateChanges[1].current)
        Assert.assertEquals(RoomStatus.Detaching, stateChanges[2].current)
        Assert.assertEquals(RoomStatus.Detached, stateChanges[3].current)
        Assert.assertEquals(RoomStatus.Released, stateChanges[4].current)
    }

    private suspend fun validateAttachAndRelease(room: Room, chatClient: ChatClient) {
        // Listen for underlying state changes
        val stateChanges = mutableListOf<RoomStatusChange>()
        room.onStatusChange {
            stateChanges.add(it)
        }

        // Perform attach operation
        room.attach()
        Assert.assertEquals(RoomStatus.Attached, room.status)

        // Perform release operation
        chatClient.rooms.release(room.roomId)
        Assert.assertEquals(RoomStatus.Released, room.status)

        assertWaiter { room.LifecycleManager.atomicCoroutineScope().finishedProcessing }

        Assert.assertEquals(4, stateChanges.size)
        Assert.assertEquals(RoomStatus.Attaching, stateChanges[0].current)
        Assert.assertEquals(RoomStatus.Attached, stateChanges[1].current)
        Assert.assertEquals(RoomStatus.Releasing, stateChanges[2].current)
        Assert.assertEquals(RoomStatus.Released, stateChanges[3].current)
    }

    @Test
    fun `should be able to Attach, Detach and Release Room`() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val room1 = chatClient.rooms.get(UUID.randomUUID().toString())
        validateAllOps(room1, chatClient)

        val room2 = chatClient.rooms.get(UUID.randomUUID().toString())
        validateAttachAndRelease(room2, chatClient)

        chatClient.realtime.close()
    }

    @Test
    fun `should be able to Attach, Detach and Release Room for connected client`() = runTest {
        val chatClient = sandbox.getConnectedChatClient()
        val room1 = chatClient.rooms.get(UUID.randomUUID().toString())
        validateAllOps(room1, chatClient)

        val room2 = chatClient.rooms.get(UUID.randomUUID().toString())
        validateAttachAndRelease(room2, chatClient)

        chatClient.realtime.close()
    }
}
