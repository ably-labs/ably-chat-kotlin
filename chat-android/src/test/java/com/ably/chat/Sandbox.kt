package com.ably.chat

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.ConnectionEvent
import io.ably.lib.realtime.ConnectionState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred

val client = HttpClient(CIO) {
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 4)
        exponentialDelay()
    }
}

class Sandbox private constructor(val appId: String, val apiKey: String) {
    companion object {
        suspend fun createInstance(): Sandbox {
            val response: HttpResponse = client.post("https://sandbox-rest.ably.io/apps") {
                contentType(ContentType.Application.Json)
                setBody(loadAppCreationRequestBody().toString())
            }
            val body = JsonParser.parseString(response.bodyAsText())

            return Sandbox(
                appId = body.asJsonObject["appId"].asString,
                // From JS chat repo at 7985ab7 — "The key we need to use is the one at index 5, which gives enough permissions to interact with Chat and Channels"
                apiKey = body.asJsonObject["keys"].asJsonArray[5].asJsonObject["keyStr"].asString,
            )
        }
    }
}

internal fun Sandbox.createSandboxChatClient(chatClientId: String = "sandbox-client"): DefaultChatClient {
    val realtime = createSandboxRealtime(chatClientId)
    return DefaultChatClient(realtime, ClientOptions())
}

internal fun Sandbox.createSandboxRealtime(chatClientId: String): AblyRealtime =
    AblyRealtime(
        io.ably.lib.types.ClientOptions().apply {
            key = apiKey
            environment = "sandbox"
            clientId = chatClientId
        },
    )

internal suspend fun Sandbox.getConnectedChatClient(): DefaultChatClient {
    val realtime = createSandboxRealtime(apiKey)
    realtime.ensureConnected()
    return DefaultChatClient(realtime, ClientOptions())
}

private suspend fun AblyRealtime.ensureConnected() {
    if (this.connection.state == ConnectionState.connected) {
        return
    }
    val connectedDeferred = CompletableDeferred<Unit>()
    this.connection.on {
        if (it.event == ConnectionEvent.connected) {
            connectedDeferred.complete(Unit)
        } else if (it.event != ConnectionEvent.connecting) {
            connectedDeferred.completeExceptionally(serverError("ably connection failed"))
            this.connection.off()
            this.close()
        }
    }
    connectedDeferred.await()
}

private suspend fun loadAppCreationRequestBody(): JsonElement =
    JsonParser.parseString(
        client.get("https://raw.githubusercontent.com/ably/ably-common/refs/heads/main/test-resources/test-app-setup.json") {
            contentType(ContentType.Application.Json)
        }.bodyAsText(),
    ).asJsonObject.get("post_apps")
