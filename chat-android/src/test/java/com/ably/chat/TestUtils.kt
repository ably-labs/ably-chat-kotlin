package com.ably.chat

import com.google.gson.JsonElement
import io.ably.lib.types.AsyncHttpPaginatedResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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

suspend fun assertWaiter(timeoutInMs: Long = 10000, block : () -> Boolean): Job {
    // Need to create coroutineScope because delay doesn't work in runTest default CoroutineScope
    val scope = CoroutineScope(Dispatchers.Default)
    return scope.launch {
        withTimeout(timeoutInMs) {
            do {
                val success = block()
                delay(100)
            } while (!success)
        }
    }
}
