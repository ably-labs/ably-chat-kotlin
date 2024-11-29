package com.ably.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.ably.lib.types.AblyException
import io.ably.lib.types.AsyncHttpPaginatedResponse
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.MessageAction
import io.ably.lib.types.Param
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val API_PROTOCOL_VERSION = 3
private const val PROTOCOL_VERSION_PARAM_NAME = "v"
private const val RESERVED_ABLY_CHAT_KEY = "ably-chat"
private val apiProtocolParam = Param(PROTOCOL_VERSION_PARAM_NAME, API_PROTOCOL_VERSION.toString())

internal class ChatApi(
    private val realtimeClient: RealtimeClient,
    private val clientId: String,
    private val logger: Logger,
) {

    /**
     * Get messages from the Chat Backend
     *
     * @return paginated result with messages
     */
    suspend fun getMessages(roomId: String, options: QueryOptions, fromSerial: String? = null): PaginatedResult<Message> {
        val baseParams = options.toParams()
        val params = fromSerial?.let { baseParams + Param("fromSerial", it) } ?: baseParams
        return makeAuthorizedPaginatedRequest(
            url = "/chat/v2/rooms/$roomId/messages",
            method = "GET",
            params = params,
        ) {
            val latestActionName = it.requireJsonObject().get("latestAction")?.asString
            val latestAction = latestActionName?.let { name -> messageActionNameToAction[name] }

            latestAction?.let { action ->
                Message(
                    serial = it.requireString("serial"),
                    clientId = it.requireString("clientId"),
                    roomId = it.requireString("roomId"),
                    text = it.requireString("text"),
                    createdAt = it.requireLong("createdAt"),
                    metadata = it.asJsonObject.get("metadata")?.toMap() ?: mapOf(),
                    headers = it.asJsonObject.get("headers")?.toMap() ?: mapOf(),
                    latestAction = action,
                )
            }
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
            "/chat/v2/rooms/$roomId/messages",
            "POST",
            body,
        )?.let {
            // (CHA-M3a)
            Message(
                serial = it.requireString("serial"),
                clientId = clientId,
                roomId = roomId,
                text = params.text,
                createdAt = it.requireLong("createdAt"),
                metadata = params.metadata ?: mapOf(),
                headers = params.headers ?: mapOf(),
                latestAction = MessageAction.MESSAGE_CREATE,
            )
        } ?: throw AblyException.fromErrorInfo(ErrorInfo("Send message endpoint returned empty value", HttpStatusCode.InternalServerError))
    }

    private fun validateSendMessageParams(params: SendMessageParams) {
        // (CHA-M3c)
        if (params.metadata?.containsKey(RESERVED_ABLY_CHAT_KEY) == true) {
            throw AblyException.fromErrorInfo(
                ErrorInfo(
                    "Metadata contains reserved 'ably-chat' key",
                    HttpStatusCode.BadRequest,
                    ErrorCode.InvalidRequestBody.code,
                ),
            )
        }

        // (CHA-M3d)
        if (params.headers?.keys?.any { it.startsWith(RESERVED_ABLY_CHAT_KEY) } == true) {
            throw AblyException.fromErrorInfo(
                ErrorInfo(
                    "Headers contains reserved key with reserved 'ably-chat' prefix",
                    HttpStatusCode.BadRequest,
                    ErrorCode.InvalidRequestBody.code,
                ),
            )
        }
    }

    /**
     * return occupancy for specified room
     */
    suspend fun getOccupancy(roomId: String): OccupancyEvent {
        return this.makeAuthorizedRequest("/chat/v2/rooms/$roomId/occupancy", "GET")?.let {
            OccupancyEvent(
                connections = it.requireInt("connections"),
                presenceMembers = it.requireInt("presenceMembers"),
            )
        } ?: throw AblyException.fromErrorInfo(ErrorInfo("Occupancy endpoint returned empty value", HttpStatusCode.InternalServerError))
    }

    private suspend fun makeAuthorizedRequest(
        url: String,
        method: String,
        body: JsonElement? = null,
    ): JsonElement? = suspendCancellableCoroutine { continuation ->
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
                    logger.error(
                        "ChatApi.makeAuthorizedRequest(); failed to make request",
                        staticContext = mapOf(
                            "url" to url,
                            "statusCode" to reason?.statusCode.toString(),
                            "errorCode" to reason?.code.toString(),
                            "errorMessage" to reason?.message.toString(),
                        ),
                    )
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
        transform: (JsonElement) -> T?,
    ): PaginatedResult<T> = suspendCancellableCoroutine { continuation ->
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
                    logger.error(
                        "ChatApi.makeAuthorizedPaginatedRequest(); failed to make request",
                        staticContext = mapOf(
                            "url" to url,
                            "statusCode" to reason?.statusCode.toString(),
                            "errorCode" to reason?.code.toString(),
                            "errorMessage" to reason?.message.toString(),
                        ),
                    )
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
