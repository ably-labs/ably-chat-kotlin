package com.ably.chat

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.realtime.Presence.GET_CLIENTID
import io.ably.lib.realtime.Presence.GET_CONNECTIONID
import io.ably.lib.realtime.Presence.GET_WAITFORSYNC
import io.ably.lib.types.AblyException
import io.ably.lib.types.ChannelOptions
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.Param
import io.ably.lib.types.PresenceMessage
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import io.ably.lib.realtime.Presence as PubSubPresence

const val AGENT_PARAMETER_NAME = "agent"

suspend fun Channel.attachCoroutine() = suspendCancellableCoroutine { continuation ->
    attach(object : CompletionListener {
        override fun onSuccess() {
            continuation.resume(Unit)
        }

        override fun onError(reason: ErrorInfo?) {
            continuation.resumeWithException(AblyException.fromErrorInfo(reason))
        }
    })
}

suspend fun Channel.detachCoroutine() = suspendCancellableCoroutine { continuation ->
    detach(object : CompletionListener {
        override fun onSuccess() {
            continuation.resume(Unit)
        }

        override fun onError(reason: ErrorInfo?) {
            continuation.resumeWithException(AblyException.fromErrorInfo(reason))
        }
    })
}

suspend fun Channel.publishCoroutine(message: PubSubMessage) = suspendCancellableCoroutine { continuation ->
    publish(
        message,
        object : CompletionListener {
            override fun onSuccess() {
                continuation.resume(Unit)
            }

            override fun onError(reason: ErrorInfo?) {
                continuation.resumeWithException(AblyException.fromErrorInfo(reason))
            }
        },
    )
}

@Suppress("SpreadOperator")
suspend fun PubSubPresence.getCoroutine(
    waitForSync: Boolean = true,
    clientId: String? = null,
    connectionId: String? = null,
): List<PresenceMessage> = withContext(Dispatchers.IO) {
    val params = buildList {
        if (waitForSync) add(Param(GET_WAITFORSYNC, true))
        clientId?.let { add(Param(GET_CLIENTID, it)) }
        connectionId?.let { add(Param(GET_CONNECTIONID, it)) }
    }
    get(*params.toTypedArray()).asList()
}

suspend fun PubSubPresence.enterClientCoroutine(clientId: String, data: JsonElement? = JsonNull.INSTANCE) =
    suspendCancellableCoroutine { continuation ->
        enterClient(
            clientId,
            data,
            object : CompletionListener {
                override fun onSuccess() {
                    continuation.resume(Unit)
                }

                override fun onError(reason: ErrorInfo?) {
                    continuation.resumeWithException(AblyException.fromErrorInfo(reason))
                }
            },
        )
    }

suspend fun PubSubPresence.updateClientCoroutine(clientId: String, data: JsonElement? = JsonNull.INSTANCE) =
    suspendCancellableCoroutine { continuation ->
        updateClient(
            clientId,
            data,
            object : CompletionListener {
                override fun onSuccess() {
                    continuation.resume(Unit)
                }

                override fun onError(reason: ErrorInfo?) {
                    continuation.resumeWithException(AblyException.fromErrorInfo(reason))
                }
            },
        )
    }

suspend fun PubSubPresence.leaveClientCoroutine(clientId: String, data: JsonElement? = JsonNull.INSTANCE) =
    suspendCancellableCoroutine { continuation ->
        leaveClient(
            clientId,
            data,
            object : CompletionListener {
                override fun onSuccess() {
                    continuation.resume(Unit)
                }

                override fun onError(reason: ErrorInfo?) {
                    continuation.resumeWithException(AblyException.fromErrorInfo(reason))
                }
            },
        )
    }

val Channel.errorMessage: String
    get() = if (reason == null) {
        ""
    } else {
        ", ${reason.message}"
    }

@Suppress("FunctionName")
fun ChatChannelOptions(init: (ChannelOptions.() -> Unit)? = null): ChannelOptions {
    val options = ChannelOptions()
    init?.let { options.it() }
    options.params = (options.params ?: mapOf()) + mapOf(
        AGENT_PARAMETER_NAME to "chat-kotlin/${BuildConfig.APP_VERSION}",
    )
    // (CHA-M4a)
    options.attachOnSubscribe = false
    return options
}

fun generateUUID() = UUID.randomUUID().toString()

fun lifeCycleErrorInfo(
    errorMessage: String,
    errorCode: ErrorCode,
) = createErrorInfo(errorMessage, errorCode, HttpStatusCode.InternalServerError)

fun lifeCycleException(
    errorMessage: String,
    errorCode: ErrorCode,
    cause: Throwable? = null,
): AblyException = createAblyException(lifeCycleErrorInfo(errorMessage, errorCode), cause)

fun lifeCycleException(
    errorInfo: ErrorInfo,
    cause: Throwable? = null,
): AblyException = createAblyException(errorInfo, cause)

fun roomInvalidStateException(roomId: String, roomStatus: RoomStatus, statusCode: Int) =
    ablyException(
        "Can't perform operation; the room '$roomId' is in an invalid state: $roomStatus",
        ErrorCode.RoomInInvalidState,
        statusCode,
    )

fun ablyException(
    errorMessage: String,
    errorCode: ErrorCode,
    statusCode: Int = HttpStatusCode.BadRequest,
    cause: Throwable? = null,
): AblyException {
    val errorInfo = createErrorInfo(errorMessage, errorCode, statusCode)
    return createAblyException(errorInfo, cause)
}

fun ablyException(
    errorInfo: ErrorInfo,
    cause: Throwable? = null,
): AblyException = createAblyException(errorInfo, cause)

private fun createErrorInfo(
    errorMessage: String,
    errorCode: ErrorCode,
    statusCode: Int,
) = ErrorInfo(errorMessage, statusCode, errorCode.code)

private fun createAblyException(
    errorInfo: ErrorInfo,
    cause: Throwable?,
) = cause?.let { AblyException.fromErrorInfo(it, errorInfo) }
    ?: AblyException.fromErrorInfo(errorInfo)

fun clientError(errorMessage: String) = ablyException(errorMessage, ErrorCode.BadRequest, HttpStatusCode.BadRequest)

fun serverError(errorMessage: String) = ablyException(errorMessage, ErrorCode.InternalError, HttpStatusCode.InternalServerError)
