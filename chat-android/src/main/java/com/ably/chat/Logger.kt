package com.ably.chat

import android.util.Log
import java.time.LocalDateTime

fun interface LogHandler {
    fun log(message: String, level: LogLevel, throwable: Throwable?, context: LogContext)
}

data class LogContext(val tag: String? = null, val contextMap: Map<String, String> = mapOf())

internal interface Logger {
    val defaultContext: LogContext
    fun withContext(additionalContext: LogContext): Logger
    fun log(message: String, level: LogLevel, throwable: Throwable? = null, context: LogContext? = null)
}

internal fun Logger.trace(message: String, throwable: Throwable? = null, context: LogContext? = null) {
    log(message, LogLevel.Trace, throwable, context)
}

internal fun Logger.debug(message: String, throwable: Throwable? = null, context: LogContext? = null) {
    log(message, LogLevel.Debug, throwable, context)
}

internal fun Logger.info(message: String, throwable: Throwable? = null, context: LogContext? = null) {
    log(message, LogLevel.Info, throwable, context)
}

internal fun Logger.warn(message: String, throwable: Throwable? = null, context: LogContext? = null) {
    log(message, LogLevel.Warn, throwable, context)
}

internal fun Logger.error(message: String, throwable: Throwable? = null, context: LogContext? = null) {
    log(message, LogLevel.Error, throwable, context)
}

internal fun LogContext.mergeWith(other: LogContext): LogContext {
    return LogContext(
        tag = other.tag ?: tag,
        contextMap = contextMap + other.contextMap,
    )
}

internal class AndroidLogger(
    private val minimalVisibleLogLevel: LogLevel,
    override val defaultContext: LogContext = LogContext(),
) : Logger {

    override fun withContext(additionalContext: LogContext): Logger {
        return AndroidLogger(
            minimalVisibleLogLevel = minimalVisibleLogLevel,
            defaultContext = defaultContext.mergeWith(additionalContext),
        )
    }

    override fun log(message: String, level: LogLevel, throwable: Throwable?, context: LogContext?) {
        if (level.logLevelValue <= minimalVisibleLogLevel.logLevelValue) return
        val finalContext = context?.let { defaultContext.mergeWith(it) } ?: this.defaultContext
        val tag = finalContext.tag ?: "AblyChatSDK"

        val contextString = if (this.defaultContext.contextMap.isEmpty()) "" else ", context: $finalContext"
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
    override val defaultContext: LogContext = LogContext(),
) : Logger {

    override fun withContext(additionalContext: LogContext): Logger {
        return CustomLogger(
            logHandler = logHandler,
            minimalVisibleLogLevel = minimalVisibleLogLevel,
            defaultContext = defaultContext.mergeWith(additionalContext),
        )
    }

    override fun log(message: String, level: LogLevel, throwable: Throwable?, context: LogContext?) {
        if (level.logLevelValue <= minimalVisibleLogLevel.logLevelValue) return
        val finalContext = context?.let { defaultContext.mergeWith(it) } ?: this.defaultContext
        logHandler.log(
            message = message,
            level = level,
            throwable = throwable,
            context = finalContext,
        )
    }
}

internal object EmptyLogger : Logger {
    override val defaultContext: LogContext = LogContext()

    override fun withContext(additionalContext: LogContext): Logger = this

    override fun log(message: String, level: LogLevel, throwable: Throwable?, context: LogContext?) = Unit
}
