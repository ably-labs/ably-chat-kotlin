package com.ably.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
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
class ChatApi(private val realtimeClient: RealtimeClient, private val clientId: String) {

    /**
     * Get messages from the Chat Backend
     *
     * @return paginated result with messages
     */
    suspend fun getMessages(roomId: String, params: QueryOptions): PaginatedResult<Message> {
        return makeAuthorizedPaginatedRequest(
            url = "/chat/v1/rooms/$roomId/messages",
            method = "GET",
            params = params.toParams(),
        ) {
            Message(
                timeserial = it.requireString("timeserial"),
                clientId = it.requireString("clientId"),
                roomId = it.requireString("roomId"),
                text = it.requireString("text"),
                createdAt = it.requireLong("createdAt"),
                metadata = it.asJsonObject.get("metadata")?.toMap() ?: mapOf(),
                headers = it.asJsonObject.get("headers")?.toMap() ?: mapOf(),
            )
        }
    }

    /**
     * Send message to the Chat Backend
     *
     * @return sent message instance
     */
    suspend fun sendMessage(roomId: String, params: SendMessageParams): Message {
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
            Message(
                timeserial = it.requireString("timeserial"),
                clientId = clientId,
                roomId = roomId,
                text = params.text,
                createdAt = it.requireLong("createdAt"),
                metadata = params.metadata ?: mapOf(),
                headers = params.headers ?: mapOf(),
            )
        } ?: throw AblyException.fromErrorInfo(ErrorInfo("Send message endpoint returned empty value", HttpStatusCodes.InternalServerError))
    }

    /**
     * return occupancy for specified room
     */
    suspend fun getOccupancy(roomId: String): OccupancyEvent {
        return this.makeAuthorizedRequest("/chat/v1/rooms/$roomId/occupancy", "GET")?.let {
            OccupancyEvent(
                connections = it.requireInt("connections"),
                presenceMembers = it.requireInt("presenceMembers"),
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
            object : AsyncHttpPaginatedResponse.Callback {
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
            object : AsyncHttpPaginatedResponse.Callback {
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

private fun JsonElement?.toRequestBody(useBinaryProtocol: Boolean = false): HttpCore.RequestBody =
    HttpUtils.requestBodyFromGson(this, useBinaryProtocol)

private fun Map<String, String>.toJson() = JsonObject().apply {
    forEach { (key, value) -> addProperty(key, value) }
}

private fun JsonElement.toMap() = buildMap<String, String> {
    requireJsonObject().entrySet().filter { (_, value) -> value.isJsonPrimitive }.forEach { (key, value) -> put(key, value.asString) }
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

private fun JsonElement.requireJsonObject(): JsonObject {
    if (!isJsonObject) {
        throw AblyException.fromErrorInfo(
            ErrorInfo("Response value expected to be JsonObject, got primitive instead", HttpStatusCodes.InternalServerError),
        )
    }
    return asJsonObject
}

private fun JsonElement.requireString(memberName: String): String {
    val memberElement = requireField(memberName)
    if (!memberElement.isJsonPrimitive) {
        throw AblyException.fromErrorInfo(
            ErrorInfo(
                "Value for \"$memberName\" field expected to be JsonPrimitive, got object instead",
                HttpStatusCodes.InternalServerError,
            ),
        )
    }
    return memberElement.asString
}

private fun JsonElement.requireLong(memberName: String): Long {
    val memberElement = requireJsonPrimitive(memberName)
    try {
        return memberElement.asLong
    } catch (formatException: NumberFormatException) {
        throw AblyException.fromErrorInfo(
            formatException,
            ErrorInfo("Required numeric field \"$memberName\" is not a valid long", HttpStatusCodes.InternalServerError),
        )
    }
}

private fun JsonElement.requireInt(memberName: String): Int {
    val memberElement = requireJsonPrimitive(memberName)
    try {
        return memberElement.asInt
    } catch (formatException: NumberFormatException) {
        throw AblyException.fromErrorInfo(
            formatException,
            ErrorInfo("Required numeric field \"$memberName\" is not a valid int", HttpStatusCodes.InternalServerError),
        )
    }
}

private fun JsonElement.requireJsonPrimitive(memberName: String): JsonPrimitive {
    val memberElement = requireField(memberName)
    if (!memberElement.isJsonPrimitive) {
        throw AblyException.fromErrorInfo(
            ErrorInfo(
                "Value for \"$memberName\" field expected to be JsonPrimitive, got object instead",
                HttpStatusCodes.InternalServerError,
            ),
        )
    }
    return memberElement.asJsonPrimitive
}

private fun JsonElement.requireField(memberName: String): JsonElement = requireJsonObject().get(memberName)
    ?: throw AblyException.fromErrorInfo(
        ErrorInfo("Required field \"$memberName\" is missing", HttpStatusCodes.InternalServerError),
    )
