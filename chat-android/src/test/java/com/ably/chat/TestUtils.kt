package com.ably.chat

import com.google.gson.JsonElement
import io.ably.lib.types.AsyncHttpPaginatedResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

internal class EmptyLogger(override val context: LogContext) : Logger {
    override fun withContext(tag: String?, staticContext: Map<String, String>, dynamicContext: Map<String, () -> String>): Logger = this
    override fun log(message: String, level: LogLevel, throwable: Throwable?, newTag: String?, newStaticContext: Map<String, String>) = Unit
}

fun Occupancy.subscribeOnce(listener: Occupancy.Listener) {
    lateinit var subscription: Subscription
    subscription = subscribe {
        listener.onEvent(it)
        subscription.unsubscribe()
    }
}

suspend fun assertWaiter(timeoutInMs: Long = 10_000, block: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(timeoutInMs) {
            do {
                val success = block()
                delay(100)
            } while (!success)
        }
    }
}

fun Any.setPrivateField(name: String, value: Any?) {
    val valueField = javaClass.getDeclaredField(name)
    valueField.isAccessible = true
    return valueField.set(this, value)
}

fun <T>Any.getPrivateField(name: String): T {
    val valueField = javaClass.getDeclaredField(name)
    valueField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    return valueField.get(this) as T
}

fun clientError(errorMessage: String) = ablyException(errorMessage, ErrorCode.BadRequest, HttpStatusCode.BadRequest)

fun serverError(errorMessage: String) = ablyException(errorMessage, ErrorCode.InternalError, HttpStatusCode.InternalServerError)
