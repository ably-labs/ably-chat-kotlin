package com.ably.chat

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test

class SandboxTest {

    private val roomOptions = RoomOptions.default

    @Test
    fun `should return empty list of presence members if nobody is entered`() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val room = chatClient.rooms.get(UUID.randomUUID().toString(), roomOptions)
        room.attach()
        val members = room.presence.get()
        assertEquals(0, members.size)
    }

    @Test
    fun `should return yourself as presence member after you entered`() = runTest {
        val chatClient = sandbox.createSandboxChatClient("sandbox-client")
        val room = chatClient.rooms.get(UUID.randomUUID().toString(), roomOptions)
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

    @Test
    fun `should return occupancy for the client`() = runTest {
        val chatClient = sandbox.createSandboxChatClient("client1")
        val roomId = UUID.randomUUID().toString()
        val roomOptions = RoomOptions(occupancy = OccupancyOptions())

        val chatClientRoom = chatClient.rooms.get(roomId, roomOptions)

        val firstOccupancyEvent = CompletableDeferred<OccupancyEvent>()
        chatClientRoom.occupancy.subscribeOnce {
            firstOccupancyEvent.complete(it)
        }

        chatClientRoom.attach()
        assertEquals(OccupancyEvent(1, 0), firstOccupancyEvent.await())
    }

    @Test
    fun `should observe connection status`() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val connectionStatusChange = CompletableDeferred<ConnectionStatusChange>()
        chatClient.connection.onStatusChange {
            if (it.current == ConnectionStatus.Connected) connectionStatusChange.complete(it)
        }
        assertEquals(
            ConnectionStatusChange(
                current = ConnectionStatus.Connected,
                previous = ConnectionStatus.Connecting,
                error = null,
                retryIn = 0,
            ),
            connectionStatusChange.await(),
        )
    }

    @Test
    fun `should observe room reactions`() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val roomId = UUID.randomUUID().toString()
        val roomOptions = RoomOptions(reactions = RoomReactionsOptions())

        val room = chatClient.rooms.get(roomId, roomOptions)
        room.attach()

        val reactionEvent = CompletableDeferred<Reaction>()

        room.reactions.subscribe { reactionEvent.complete(it) }

        room.reactions.send("heart")

        assertEquals(
            "heart",
            reactionEvent.await().type,
        )
    }

    @Test
    fun `should be able to send and retrieve messages without room features`() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val roomId = UUID.randomUUID().toString()

        val room = chatClient.rooms.get(roomId)

        room.attach()

        val messageEvent = CompletableDeferred<MessageEvent>()

        room.messages.subscribe { messageEvent.complete(it) }
        room.messages.send("hello")

        assertEquals(
            "hello",
            messageEvent.await().message.text,
        )
    }

    @Test
    fun `should be able to send and retrieve messages with all room features enabled`() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val roomId = UUID.randomUUID().toString()

        val room = chatClient.rooms.get(roomId, RoomOptions.default)

        room.attach()

        val messageEvent = CompletableDeferred<MessageEvent>()

        room.messages.subscribe { messageEvent.complete(it) }
        room.messages.send("hello")

        assertEquals(
            "hello",
            messageEvent.await().message.text,
        )
    }

    @Test
    fun `should be able to send and retrieve messages from history`() = runTest {
        val chatClient = sandbox.createSandboxChatClient()
        val roomId = UUID.randomUUID().toString()

        val room = chatClient.rooms.get(roomId)

        room.attach()

        room.messages.send("hello")

        lateinit var messages: List<Message>

        assertWaiter {
            messages = room.messages.get().items
            messages.isNotEmpty()
        }

        assertEquals(1, messages.size)
        assertEquals("hello", messages.first().text)
        assertEquals("sandbox-client", messages.first().clientId)
    }

    companion object {

        private lateinit var sandbox: Sandbox

        @JvmStatic
        @BeforeClass
        fun setUp() = runTest {
            sandbox = Sandbox.createInstance()
        }
    }
}
