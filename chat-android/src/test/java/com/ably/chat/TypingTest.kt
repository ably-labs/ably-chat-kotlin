package com.ably.chat

import com.ably.chat.room.DEFAULT_CLIENT_ID
import com.ably.chat.room.createMockChannel
import com.ably.chat.room.createMockChatApi
import com.ably.chat.room.createMockRealtimeClient
import com.ably.chat.room.createMockRoom
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.realtime.Presence
import io.ably.lib.types.ChannelOptions
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class TypingTest {

    private lateinit var room: DefaultRoom
    private val pubSubPresence = mockk<Presence>(relaxed = true)

    @Before
    fun setUp() {
        val realtimeClient = createMockRealtimeClient()
        val mockRealtimeChannel = realtimeClient.createMockChannel("room1::\$chat::\$messages")
        mockRealtimeChannel.setPrivateField("presence", pubSubPresence)

        every { realtimeClient.channels.get(any<String>(), any<ChannelOptions>()) } returns mockRealtimeChannel
        every { pubSubPresence.enterClient(DEFAULT_CLIENT_ID, any(), any()) } answers {
            val completionListener = arg<CompletionListener>(2)
            completionListener.onSuccess()
        }

        val mockChatApi = createMockChatApi(realtimeClient)
        room = spyk(createMockRoom("room1", realtimeClient = realtimeClient, chatApi = mockChatApi))

        coEvery { room.ensureAttached() } returns Unit
    }

    /**
     * @spec CHA-T4a1
     */
    @Test
    fun `when a typing session is started, the client is entered into presence on the typing channel`() = runTest {
        val typing = DefaultTyping(room)
        typing.start()
        verify(exactly = 1) { pubSubPresence.enterClient("clientId", any(), any()) }
    }

    /**
     * @spec CHA-T4a2
     */
    @Test
    fun `when timeout expires, the typing session is automatically ended by leaving presence`() = runTest {
        val testScheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val typing = DefaultTyping(room, dispatcher)

        scope.launch {
            typing.start()
        }

        testScheduler.advanceTimeBy(5000.milliseconds)
        testScheduler.runCurrent()

        verify(exactly = 1) { pubSubPresence.enterClient("clientId", any(), any()) }
        verify(exactly = 1) { pubSubPresence.leaveClient("clientId", any(), any()) }
    }

    /**
     * @spec CHA-T4b
     */
    @Test
    fun `if typing is already in progress, the timeout is extended to be timeoutMs from now`() = runTest {
        val testScheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val typing = DefaultTyping(room, dispatcher)

        scope.launch {
            typing.start()
        }

        testScheduler.advanceTimeBy(3000.milliseconds)
        testScheduler.runCurrent()

        scope.launch {
            typing.start()
        }

        testScheduler.advanceTimeBy(3000.milliseconds)
        testScheduler.runCurrent()

        verify(exactly = 1) { pubSubPresence.enterClient("clientId", any(), any()) }
        verify(exactly = 0) { pubSubPresence.leaveClient("clientId", any(), any()) }
    }

    /**
     * @spec CHA-T5b
     */
    @Test
    fun `if typing is in progress, the timeout is cancelled, the client then leaves presence`() = runTest {
        val testScheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val typing = DefaultTyping(room, dispatcher)

        scope.launch {
            typing.start()
        }

        testScheduler.advanceTimeBy(1000.milliseconds)
        testScheduler.runCurrent()

        verify(exactly = 1) { pubSubPresence.enterClient("clientId", any(), any()) }

        scope.launch {
            typing.stop()
        }

        testScheduler.advanceTimeBy(5000.milliseconds)
        testScheduler.runCurrent()

        verify(exactly = 1) { pubSubPresence.leaveClient("clientId", any(), any()) }
    }
}
