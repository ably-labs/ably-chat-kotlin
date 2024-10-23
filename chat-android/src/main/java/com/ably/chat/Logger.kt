package com.ably.chat

import android.util.Log
import java.time.LocalDateTime

fun interface LogHandler {
    fun log(message: String, level: LogLevel, throwable: Throwable?, context: LogContext)
}

data class LogContext(
    val tag: String,
    val clientId: String,
    val instanceId: String,
    val contextMap: Map<String, String> = mapOf(),
)

internal interface Logger {
    val context: LogContext
    fun withContext(tag: String? = null, contextMap: Map<String, String> = mapOf()): Logger
    fun log(
        message: String,
        level: LogLevel,
        throwable: Throwable? = null,
        newTag: String? = null,
        newContextMap: Map<String, String> = mapOf(),
    )
}

internal fun Logger.trace(message: String, throwable: Throwable? = null, tag: String? = null, contextMap: Map<String, String> = mapOf()) {
    log(message, LogLevel.Trace, throwable, tag, contextMap)
}

internal fun Logger.debug(message: String, throwable: Throwable? = null, tag: String? = null, contextMap: Map<String, String> = mapOf()) {
    log(message, LogLevel.Debug, throwable, tag, contextMap)
}

internal fun Logger.info(message: String, throwable: Throwable? = null, tag: String? = null, contextMap: Map<String, String> = mapOf()) {
    log(message, LogLevel.Info, throwable, tag, contextMap)
}

internal fun Logger.warn(message: String, throwable: Throwable? = null, tag: String? = null, contextMap: Map<String, String> = mapOf()) {
    log(message, LogLevel.Warn, throwable, tag, contextMap)
}

internal fun Logger.error(message: String, throwable: Throwable? = null, tag: String? = null, contextMap: Map<String, String> = mapOf()) {
    log(message, LogLevel.Error, throwable, tag, contextMap)
}

internal fun LogContext.mergeWith(tag: String? = null, contextMap: Map<String, String> = mapOf()): LogContext {
    return LogContext(
        tag = tag ?: this.tag,
        instanceId = instanceId,
        clientId = clientId,
        contextMap = this.contextMap + contextMap,
    )
}

internal class AndroidLogger(
    private val minimalVisibleLogLevel: LogLevel,
    override val context: LogContext,
) : Logger {

    override fun withContext(newTag: String?, newContextMap: Map<String, String>): Logger {
        return AndroidLogger(
            minimalVisibleLogLevel = minimalVisibleLogLevel,
            context = context.mergeWith(newTag, newContextMap),
        )
    }

    override fun log(message: String, level: LogLevel, throwable: Throwable?, newTag: String?, newContextMap: Map<String, String>) {
        if (level.logLevelValue <= minimalVisibleLogLevel.logLevelValue) return
        val finalContext = context.mergeWith(newTag, newContextMap)
        val tag = finalContext.tag
        val contextMap = finalContext.contextMap + ("instanceId" to finalContext.instanceId)

        val contextString = ", context: $contextMap"
        val formattedMessage = "[${LocalDateTime.now()}] ${level.name} ably-chat: ${message}$contextString"
        when (level) {
            // We use Logcat's info level for Trace and Debug
            LogLevel.Trace -> Log.i(tag, formattedMessage, throwable)
            LogLevel.Debug -> Log.i(tag, formattedMessage, throwable)
            LogLevel.Info -> Log.i(tag, formattedMessage, throwable)
            LogLevel.Warn -> Log.w(tag, formattedMessage, throwable)
            LogLevel.Error -> Log.e(tag, formattedMessage, throwable)
            LogLevel.Silent -> {}
        }
    }
}

internal class CustomLogger(
    private val logHandler: LogHandler,
    private val minimalVisibleLogLevel: LogLevel,
    override val context: LogContext,
) : Logger {

    override fun withContext(tag: String?, contextMap: Map<String, String>): Logger {
        return CustomLogger(
            logHandler = logHandler,
            minimalVisibleLogLevel = minimalVisibleLogLevel,
            context = context.mergeWith(tag, contextMap),
        )
    }

    override fun log(message: String, level: LogLevel, throwable: Throwable?, newTag: String?, newContextMap: Map<String, String>) {
        if (level.logLevelValue <= minimalVisibleLogLevel.logLevelValue) return
        val finalContext = context.mergeWith(newTag, newContextMap)
        logHandler.log(
            message = message,
            level = level,
            throwable = throwable,
            context = finalContext,
        )
    }
}

internal class EmptyLogger(override val context: LogContext) : Logger {
    override fun withContext(newTag: String?, newContextMap: Map<String, String>): Logger = this
    override fun log(message: String, level: LogLevel, throwable: Throwable?, newTag: String?, newContextMap: Map<String, String>) = Unit
}
