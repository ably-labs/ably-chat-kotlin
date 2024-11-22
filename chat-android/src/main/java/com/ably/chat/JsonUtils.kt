package com.ably.chat

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.ably.lib.http.HttpCore
import io.ably.lib.http.HttpUtils
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo

internal fun JsonElement?.toRequestBody(useBinaryProtocol: Boolean = false): HttpCore.RequestBody =
    HttpUtils.requestBodyFromGson(this, useBinaryProtocol)

internal fun Map<String, String>.toJson() = JsonObject().apply {
    forEach { (key, value) -> addProperty(key, value) }
}

internal fun JsonElement.toMap() = buildMap<String, String> {
    requireJsonObject().entrySet().filter { (_, value) -> value.isJsonPrimitive }.forEach { (key, value) -> put(key, value.asString) }
}

internal fun JsonElement.requireJsonObject(): JsonObject {
    if (!isJsonObject) {
        throw AblyException.fromErrorInfo(
            ErrorInfo("Response value expected to be JsonObject, got primitive instead", HttpStatusCode.InternalServerError),
        )
    }
    return asJsonObject
}

internal fun JsonElement.requireString(memberName: String): String {
    val memberElement = requireField(memberName)
    if (!memberElement.isJsonPrimitive) {
        throw AblyException.fromErrorInfo(
            ErrorInfo(
                "Value for \"$memberName\" field expected to be JsonPrimitive, got object instead",
                HttpStatusCode.InternalServerError,
            ),
        )
    }
    return memberElement.asString
}

internal fun JsonElement.requireLong(memberName: String): Long {
    val memberElement = requireJsonPrimitive(memberName)
    try {
        return memberElement.asLong
    } catch (formatException: NumberFormatException) {
        throw AblyException.fromErrorInfo(
            formatException,
            ErrorInfo("Required numeric field \"$memberName\" is not a valid long", HttpStatusCode.InternalServerError),
        )
    }
}

internal fun JsonElement.requireInt(memberName: String): Int {
    val memberElement = requireJsonPrimitive(memberName)
    try {
        return memberElement.asInt
    } catch (formatException: NumberFormatException) {
        throw AblyException.fromErrorInfo(
            formatException,
            ErrorInfo("Required numeric field \"$memberName\" is not a valid int", HttpStatusCode.InternalServerError),
        )
    }
}

internal fun JsonElement.requireJsonPrimitive(memberName: String): JsonPrimitive {
    val memberElement = requireField(memberName)
    if (!memberElement.isJsonPrimitive) {
        throw AblyException.fromErrorInfo(
            ErrorInfo(
                "Value for \"$memberName\" field expected to be JsonPrimitive, got object instead",
                HttpStatusCode.InternalServerError,
            ),
        )
    }
    return memberElement.asJsonPrimitive
}

internal fun JsonElement.requireField(memberName: String): JsonElement = requireJsonObject().get(memberName)
    ?: throw AblyException.fromErrorInfo(
        ErrorInfo("Required field \"$memberName\" is missing", HttpStatusCode.InternalServerError),
    )
