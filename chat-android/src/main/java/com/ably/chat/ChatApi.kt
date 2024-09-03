package com.ably.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.ably.lib.http.HttpCore
import io.ably.lib.http.HttpUtils
import io.ably.lib.types.AblyException
import io.ably.lib.types.AsyncHttpPaginatedResponse
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.Param
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val API_PROTOCOL_VERSION = 3
private const val PROTOCOL_VERSION_PARAM_NAME = "v"
private val apiProtocolParam = Param(PROTOCOL_VERSION_PARAM_NAME, API_PROTOCOL_VERSION.toString())

// TODO make this class internal
class ChatApi(private val realtimeClient: RealtimeClient) {

    suspend fun getMessages(roomId: String, params: QueryOptions): PaginatedResult<Message> {
        return makeAuthorizedPaginatedRequest(
            url = "/chat/v1/rooms/$roomId/messages",
            method = "GET",
            params = params.toParams(),
        ) {
            Message(
                timeserial = it.asJsonObject.get("timeserial").asString,
                clientId = it.asJsonObject.get("clientId").asString,
                roomId = it.asJsonObject.get("roomId").asString,
                text = it.asJsonObject.get("text").asString,
                createdAt = it.asJsonObject.get("createdAt").asLong,
                metadata = it.asJsonObject.get("metadata")?.asJsonObject?.toMap() ?: mapOf(),
                headers = it.asJsonObject.get("headers")?.asJsonObject?.toMap() ?: mapOf(),
            )
        }
    }

    suspend fun sendMessage(roomId: String, params: SendMessageParams): CreateMessageResponse {
        val body = JsonObject().apply {
            addProperty("text", params.text)
            params.headers?.let {
                add("headers", it.toJson())
            }
            params.metadata?.let {
                add("metadata", it.toJson())
            }
        }

        return makeAuthorizedRequest(
            "/chat/v1/rooms/$roomId/messages",
            "POST",
            body,
        )?.let {
            CreateMessageResponse(
                timeserial = it.asJsonObject.get("timeserial").asString,
                createdAt = it.asJsonObject.get("createdAt").asLong,
            )
        } ?: throw AblyException.fromErrorInfo(ErrorInfo("Send message endpoint returned empty value", HttpStatusCodes.InternalServerError))
    }

    suspend fun getOccupancy(roomId: String): OccupancyEvent {
        return this.makeAuthorizedRequest("/chat/v1/rooms/$roomId/occupancy", "GET")?.let {
            OccupancyEvent(
                connections = it.asJsonObject.get("connections").asInt,
                presenceMembers = it.asJsonObject.get("presenceMembers").asInt,
            )
        } ?: throw AblyException.fromErrorInfo(ErrorInfo("Occupancy endpoint returned empty value", HttpStatusCodes.InternalServerError))
    }

    private suspend fun makeAuthorizedRequest(
        url: String,
        method: String,
        body: JsonElement? = null,
    ): JsonElement? = suspendCoroutine { continuation ->
        val requestBody = body.toRequestBody()
        realtimeClient.requestAsync(
            method,
            url,
            arrayOf(apiProtocolParam),
            requestBody,
            arrayOf(),
            object :
                AsyncHttpPaginatedResponse.Callback {
                override fun onResponse(response: AsyncHttpPaginatedResponse?) {
                    continuation.resume(response?.items()?.firstOrNull())
                }

                override fun onError(reason: ErrorInfo?) {
                    continuation.resumeWithException(AblyException.fromErrorInfo(reason))
                }
            },
        )
    }

    private suspend fun <T> makeAuthorizedPaginatedRequest(
        url: String,
        method: String,
        params: List<Param> = listOf(),
        transform: (JsonElement) -> T,
    ): PaginatedResult<T> = suspendCoroutine { continuation ->
        realtimeClient.requestAsync(
            method,
            url,
            (params + apiProtocolParam).toTypedArray(),
            null,
            arrayOf(),
            object :
                AsyncHttpPaginatedResponse.Callback {
                override fun onResponse(response: AsyncHttpPaginatedResponse?) {
                    continuation.resume(response.toPaginatedResult(transform))
                }

                override fun onError(reason: ErrorInfo?) {
                    continuation.resumeWithException(AblyException.fromErrorInfo(reason))
                }
            },
        )
    }
}

data class CreateMessageResponse(val timeserial: String, val createdAt: Long)

private fun JsonElement?.toRequestBody(useBinaryProtocol: Boolean = false): HttpCore.RequestBody =
    HttpUtils.requestBodyFromGson(this, useBinaryProtocol)

private fun Map<String, String>.toJson() = JsonObject().apply {
    forEach { (key, value) -> addProperty(key, value) }
}

private fun JsonObject.toMap() = buildMap<String, String> {
    entrySet().filter { (_, value) -> value.isJsonPrimitive }.forEach { (key, value) -> put(key, value.asString) }
}

private fun QueryOptions.toParams() = buildList {
    start?.let { add(Param("start", it)) }
    end?.let { add(Param("end", it)) }
    add(Param("limit", limit))
    add(
        Param(
            "direction",
            when (orderBy) {
                QueryOptions.MessageOrder.NewestFirst -> "backwards"
                QueryOptions.MessageOrder.OldestFirst -> "forwards"
            },
        ),
    )
}
