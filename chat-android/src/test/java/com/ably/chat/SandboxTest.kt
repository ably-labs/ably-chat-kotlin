package com.ably.chat

import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

class SandboxTest {

    private lateinit var sandbox: Sandbox

    @Before
    fun setUp() = runTest {
        sandbox = Sandbox.createInstance()
    }

    @Test
    @Ignore
    fun `should return empty list of presence members if nobody is entered`() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val room = chatClient.rooms.get(UUID.randomUUID().toString())
        room.attach()
        val members = room.presence.get()
        assertEquals(0, members.size)
    }

    @Test
    @Ignore
    fun `should return yourself as presence member after you entered`() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val room = chatClient.rooms.get(UUID.randomUUID().toString())
        room.attach()
        room.presence.enter()
        val members = room.presence.get()
        assertEquals(1, members.size)
        assertEquals("sandbox-client", members.first().clientId)
    }
}
