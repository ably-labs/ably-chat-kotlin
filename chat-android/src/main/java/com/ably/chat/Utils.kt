package com.ably.chat

import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.CompletionListener
import io.ably.lib.types.AblyException
import io.ably.lib.types.ChannelOptions
import io.ably.lib.types.ErrorInfo
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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

@Suppress("FunctionName")
fun ChatChannelOptions(init: (ChannelOptions.() -> Unit)? = null): ChannelOptions {
    val options = ChannelOptions()
    init?.let { options.it() }
    options.params = options.params + mapOf(
        AGENT_PARAMETER_NAME to "chat-kotlin/${BuildConfig.APP_VERSION}",
    )
    return options
}
