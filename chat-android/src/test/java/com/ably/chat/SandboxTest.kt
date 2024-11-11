package com.ably.chat

import io.ably.lib.realtime.ChannelState
import java.util.UUID
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
    fun basicIntegrationTest() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val room = chatClient.rooms.get(UUID.randomUUID().toString())
        room.attach()
        assertEquals(ChannelState.attached, room.messages.channel.state)
    }
}
