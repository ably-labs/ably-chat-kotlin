package com.ably.chat

import com.google.gson.JsonElement
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.types.AblyException
import io.ably.lib.types.ChannelOptions
import io.ably.lib.types.ErrorInfo
import io.ably.lib.types.Param
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import io.ably.lib.realtime.Presence as PubSubPresence

const val AGENT_PARAMETER_NAME = "agent"

suspend fun Channel.attachCoroutine() = suspendCoroutine { continuation ->
    attach(object : CompletionListener {
        override fun onSuccess() {
            continuation.resume(Unit)
        }

        override fun onError(reason: ErrorInfo?) {
            continuation.resumeWithException(AblyException.fromErrorInfo(reason))
        }
    })
}

suspend fun Channel.detachCoroutine() = suspendCoroutine { continuation ->
    detach(object : CompletionListener {
        override fun onSuccess() {
            continuation.resume(Unit)
        }

        override fun onError(reason: ErrorInfo?) {
            continuation.resumeWithException(AblyException.fromErrorInfo(reason))
        }
    })
}

suspend fun Channel.publishCoroutine(message: PubSubMessage) = suspendCoroutine { continuation ->
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

suspend fun PubSubPresence.getCoroutine(param: Param) = withContext(Dispatchers.IO) {
    get(param)
}

@Suppress("SpreadOperator")
suspend fun PubSubPresence.getCoroutine(params: List<Param>) = withContext(Dispatchers.IO) {
    get(*params.toTypedArray())
}

suspend fun PubSubPresence.enterClientCoroutine(clientId: String, data: JsonElement?) = suspendCancellableCoroutine { continuation ->
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

suspend fun PubSubPresence.updateClientCoroutine(clientId: String, data: JsonElement?) = suspendCancellableCoroutine { continuation ->
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

suspend fun PubSubPresence.leaveClientCoroutine(clientId: String, data: JsonElement?) = suspendCancellableCoroutine { continuation ->
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

/**
 * A value that can be evaluated at a later time, similar to `kotlinx.coroutines.Deferred` or a JavaScript Promise.
 *
 * This class provides a thread-safe, simple blocking implementation. It is not designed for use in scenarios with
 * heavy concurrency.
 *
 * @param T the type of the value that will be evaluated.
 */
internal class DeferredValue<T> {

    private var value: T? = null

    private val lock = Any()

    private var observers: Set<(T) -> Unit> = setOf()

    private var _completed = false

    /**
     * `true` if value has been set, `false` otherwise
     */
    val completed get() = _completed

    /**
     * Set value and mark DeferredValue completed, should be invoked only once
     *
     * @throws IllegalStateException if it's already `completed`
     */
    fun completeWith(completionValue: T) {
        synchronized(lock) {
            check(!_completed) { "DeferredValue has already been completed" }
            value = completionValue
            _completed = true
            observers.forEach { it(completionValue) }
            observers = setOf()
        }
    }

    /**
     * Wait until value is completed
     *
     * @return completed value
     */
    suspend fun await(): T {
        val result = suspendCoroutine { continuation ->
            synchronized(lock) {
                if (_completed) continuation.resume(value!!)
                val observer: (T) -> Unit = {
                    continuation.resume(it)
                }
                observers += observer
            }
        }
        return result
    }
}
