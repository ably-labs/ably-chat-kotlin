package com.ably.chat

import com.google.gson.JsonElement
import io.ably.lib.types.AsyncHttpPaginatedResponse
import io.mockk.every
import io.mockk.mockk

fun buildAsyncHttpPaginatedResponse(items: List<JsonElement>): AsyncHttpPaginatedResponse {
    val response = mockk<AsyncHttpPaginatedResponse>()
    every {
        response.items()
    } returns items.toTypedArray()
    return response
}

fun mockMessagesApiResponse(realtimeClientMock: RealtimeClient, response: List<JsonElement>, roomId: String = "roomId") {
    every {
        realtimeClientMock.requestAsync("GET", "/chat/v1/rooms/$roomId/messages", any(), any(), any(), any())
    } answers {
        val callback = lastArg<AsyncHttpPaginatedResponse.Callback>()
        callback.onResponse(
            buildAsyncHttpPaginatedResponse(response),
        )
    }
}

fun mockSendMessageApiResponse(realtimeClientMock: RealtimeClient, response: JsonElement, roomId: String = "roomId") {
    every {
        realtimeClientMock.requestAsync("POST", "/chat/v1/rooms/$roomId/messages", any(), any(), any(), any())
    } answers {
        val callback = lastArg<AsyncHttpPaginatedResponse.Callback>()
        callback.onResponse(
            buildAsyncHttpPaginatedResponse(
                listOf(response),
            ),
        )
    }
}

fun mockOccupancyApiResponse(realtimeClientMock: RealtimeClient, response: JsonElement, roomId: String = "roomId") {
    every {
        realtimeClientMock.requestAsync("GET", "/chat/v1/rooms/$roomId/occupancy", any(), any(), any(), any())
    } answers {
        val callback = lastArg<AsyncHttpPaginatedResponse.Callback>()
        callback.onResponse(
            buildAsyncHttpPaginatedResponse(
                listOf(response),
            ),
        )
    }
}

internal class EmptyLogger(override val context: LogContext) : Logger {
    override fun withContext(tag: String?, staticContext: Map<String, String>, dynamicContext: Map<String, () -> String>): Logger = this
    override fun log(message: String, level: LogLevel, throwable: Throwable?, newTag: String?, newStaticContext: Map<String, String>) = Unit
}
