package com.ably.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.ably.lib.types.AblyException
import io.ably.lib.types.AsyncHttpPaginatedResponse
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.Param
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private const val API_PROTOCOL_VERSION = 3
private const val PROTOCOL_VERSION_PARAM_NAME = "v"
private const val RESERVED_ABLY_CHAT_KEY = "ably-chat"
private val apiProtocolParam = Param(PROTOCOL_VERSION_PARAM_NAME, API_PROTOCOL_VERSION.toString())

internal class ChatApi(private val realtimeClient: RealtimeClient, private val clientId: String) {

    /**
     * Get messages from the Chat Backend
     *
     * @return paginated result with messages
     */
    suspend fun getMessages(roomId: String, options: QueryOptions, fromSerial: String? = null): PaginatedResult<Message> {
        val baseParams = options.toParams()
        val params = fromSerial?.let { baseParams + Param("fromSerial", it) } ?: baseParams
        return makeAuthorizedPaginatedRequest(
            url = "/chat/v1/rooms/$roomId/messages",
            method = "GET",
            params = params,
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
        validateSendMessageParams(params)

        val body = JsonObject().apply {
            addProperty("text", params.text)
            // (CHA-M3b)
            params.headers?.let {
                add("headers", it.toJson())
            }
            // (CHA-M3b)
            params.metadata?.let {
                add("metadata", it.toJson())
            }
        }

        return makeAuthorizedRequest(
            "/chat/v1/rooms/$roomId/messages",
            "POST",
            body,
        )?.let {
            // (CHA-M3a)
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

    private fun validateSendMessageParams(params: SendMessageParams) {
        // (CHA-M3c)
        if (params.metadata?.containsKey(RESERVED_ABLY_CHAT_KEY) == true) {
            throw AblyException.fromErrorInfo(
                ErrorInfo(
                    "Metadata contains reserved 'ably-chat' key",
                    HttpStatusCodes.BadRequest,
                    ErrorCodes.InvalidRequestBody.errorCode,
                ),
            )
        }

        // (CHA-M3d)
        if (params.headers?.keys?.any { it.startsWith(RESERVED_ABLY_CHAT_KEY) } == true) {
            throw AblyException.fromErrorInfo(
                ErrorInfo(
                    "Headers contains reserved key with reserved 'ably-chat' prefix",
                    HttpStatusCodes.BadRequest,
                    ErrorCodes.InvalidRequestBody.errorCode,
                ),
            )
        }
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
                    // (CHA-M3e)
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
