package com.ably.chat

import com.google.gson.JsonObject
import io.ably.lib.types.AblyException
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApiTest {

    private val realtime = mockk<RealtimeClient>(relaxed = true)
    private val chatApi = ChatApi(realtime, "clientId")

    /**
     * @nospec
     */
    @Test
    fun `getMessages should ignore unknown fields for Chat Backend`() = runTest {
        mockMessagesApiResponse(
            realtime,
            listOf(
                JsonObject().apply {
                    addProperty("foo", "bar")
                    addProperty("timeserial", "timeserial")
                    addProperty("roomId", "roomId")
                    addProperty("clientId", "clientId")
                    addProperty("text", "hello")
                    addProperty("createdAt", 1_000_000)
                },
            ),
        )

        val messages = chatApi.getMessages("roomId", QueryOptions())

        assertEquals(
            listOf(
                Message(
                    timeserial = "timeserial",
                    roomId = "roomId",
                    clientId = "clientId",
                    text = "hello",
                    createdAt = 1_000_000L,
                    metadata = mapOf(),
                    headers = mapOf(),
                ),
            ),
            messages.items,
        )
    }

    /**
     * @nospec
     */
    @Test
    fun `getMessages should throws AblyException if some required fields are missing`() = runTest {
        mockMessagesApiResponse(
            realtime,
            listOf(
                JsonObject().apply {
                    addProperty("foo", "bar")
                },
            ),
        )

        val exception = assertThrows(AblyException::class.java) {
            runBlocking { chatApi.getMessages("roomId", QueryOptions()) }
        }

        assertTrue(exception.message!!.matches(""".*Required field "\w+" is missing""".toRegex()))
    }

    /**
     * @nospec
     */
    @Test
    fun `sendMessage should ignore unknown fields for Chat Backend`() = runTest {
        mockSendMessageApiResponse(
            realtime,
            JsonObject().apply {
                addProperty("foo", "bar")
                addProperty("timeserial", "timeserial")
                addProperty("createdAt", 1_000_000)
            },
        )

        val message = chatApi.sendMessage("roomId", SendMessageParams(text = "hello"))

        assertEquals(
            Message(
                timeserial = "timeserial",
                roomId = "roomId",
                clientId = "clientId",
                text = "hello",
                createdAt = 1_000_000L,
                headers = mapOf(),
                metadata = mapOf(),
            ),
            message,
        )
    }

    /**
     * @nospec
     */
    @Test
    fun `sendMessage should throw exception if 'timeserial' field is not presented`() = runTest {
        mockSendMessageApiResponse(
            realtime,
            JsonObject().apply {
                addProperty("foo", "bar")
                addProperty("createdAt", 1_000_000)
            },
        )

        assertThrows(AblyException::class.java) {
            runBlocking { chatApi.sendMessage("roomId", SendMessageParams(text = "hello")) }
        }
    }

    /**
     * @nospec
     */
    @Test
    fun `getOccupancy should throw exception if 'connections' field is not presented`() = runTest {
        mockOccupancyApiResponse(
            realtime,
            JsonObject().apply {
                addProperty("presenceMembers", 1_000)
            },
        )

        assertThrows(AblyException::class.java) {
            runBlocking { chatApi.getOccupancy("roomId") }
        }
    }
}
