package com.ably.chat

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SandboxTest {

    private lateinit var sandbox: Sandbox

    @Before
    fun setUp() = runTest {
        sandbox = Sandbox.createInstance()
    }

    @Test
    fun `should return empty list of presence members if nobody is entered`() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val room = chatClient.rooms.get(UUID.randomUUID().toString())
        room.attach()
        val members = room.presence.get()
        assertEquals(0, members.size)
    }

    @Test
    fun `should return yourself as presence member after you entered`() = runTest {
        val chatClient = sandbox.createSandboxChatClient("sandbox-client")
        val room = chatClient.rooms.get(UUID.randomUUID().toString())
        room.attach()
        room.presence.enter()
        val members = room.presence.get()
        assertEquals(1, members.size)
        assertEquals("sandbox-client", members.first().clientId)
    }

    @Test
    fun `should return typing indication for client`() = runTest {
        val chatClient1 = sandbox.createSandboxChatClient("client1")
        val chatClient2 = sandbox.createSandboxChatClient("client2")
        val roomId = UUID.randomUUID().toString()
        val roomOptions = RoomOptions(typing = TypingOptions(timeoutMs = 10_000))
        val chatClient1Room = chatClient1.rooms.get(roomId, roomOptions)
        chatClient1Room.attach()
        val chatClient2Room = chatClient2.rooms.get(roomId, roomOptions)
        chatClient2Room.attach()

        val deferredValue = CompletableDeferred<TypingEvent>()
        chatClient2Room.typing.subscribe {
            deferredValue.complete(it)
        }
        chatClient1Room.typing.start()
        val typingEvent = deferredValue.await()
        assertEquals(setOf("client1"), typingEvent.currentlyTyping)
        assertEquals(setOf("client1"), chatClient2Room.typing.get())
    }
}
