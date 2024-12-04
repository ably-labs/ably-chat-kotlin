package com.ably.chat

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun interface LogHandler {
    fun log(message: String, level: LogLevel, throwable: Throwable?, context: LogContext)
}

data class LogContext(
    val tag: String,
    val staticContext: Map<String, String> = mapOf(),
    val dynamicContext: Map<String, () -> String> = mapOf(),
)

internal interface Logger {
    val context: LogContext
    fun withContext(
        tag: String? = null,
        staticContext: Map<String, String> = mapOf(),
        dynamicContext: Map<String, () -> String> = mapOf(),
    ): Logger

    fun log(
        message: String,
        level: LogLevel,
        throwable: Throwable? = null,
        newTag: String? = null,
        newStaticContext: Map<String, String> = mapOf(),
    )
}

internal fun Logger.trace(
    message: String,
    throwable: Throwable? = null,
    tag: String? = null,
    staticContext: Map<String, String> = mapOf(),
) {
    log(message, LogLevel.Trace, throwable, tag, staticContext)
}

internal fun Logger.debug(
    message: String,
    throwable: Throwable? = null,
    tag: String? = null,
    staticContext: Map<String, String> = mapOf(),
) {
    log(message, LogLevel.Debug, throwable, tag, staticContext)
}

internal fun Logger.info(message: String, throwable: Throwable? = null, tag: String? = null, staticContext: Map<String, String> = mapOf()) {
    log(message, LogLevel.Info, throwable, tag, staticContext)
}

internal fun Logger.warn(message: String, throwable: Throwable? = null, tag: String? = null, staticContext: Map<String, String> = mapOf()) {
    log(message, LogLevel.Warn, throwable, tag, staticContext)
}

internal fun Logger.error(
    message: String,
    throwable: Throwable? = null,
    tag: String? = null,
    staticContext: Map<String, String> = mapOf(),
) {
    log(message, LogLevel.Error, throwable, tag, staticContext)
}

internal fun LogContext.mergeWith(
    tag: String? = null,
    staticContext: Map<String, String> = mapOf(),
    dynamicContext: Map<String, () -> String> = mapOf(),
): LogContext {
    return LogContext(
        tag = tag ?: this.tag,
        staticContext = this.staticContext + staticContext,
        dynamicContext = this.dynamicContext + dynamicContext,
    )
}

internal class AndroidLogger(
    private val minimalVisibleLogLevel: LogLevel,
    override val context: LogContext,
) : Logger {

    override fun withContext(tag: String?, staticContext: Map<String, String>, dynamicContext: Map<String, () -> String>): Logger {
        return AndroidLogger(
            minimalVisibleLogLevel = minimalVisibleLogLevel,
            context = context.mergeWith(tag, staticContext, dynamicContext),
        )
    }

    override fun log(message: String, level: LogLevel, throwable: Throwable?, newTag: String?, newStaticContext: Map<String, String>) {
        if (level.logLevelValue < minimalVisibleLogLevel.logLevelValue) return
        val finalContext = context.mergeWith(newTag, newStaticContext)
        val tag = finalContext.tag
        val completeContext = finalContext.staticContext + finalContext.dynamicContext.mapValues { it.value() }

        val contextString = ", context: $completeContext"
        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S", Locale.US).format(Date())
        val formattedMessage = "$currentTime [$tag] (${level.name.uppercase()}) ably-chat: ${message}$contextString"
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

    override fun withContext(tag: String?, staticContext: Map<String, String>, dynamicContext: Map<String, () -> String>): Logger {
        return CustomLogger(
            logHandler = logHandler,
            minimalVisibleLogLevel = minimalVisibleLogLevel,
            context = context.mergeWith(tag, staticContext, dynamicContext),
        )
    }

    override fun log(message: String, level: LogLevel, throwable: Throwable?, newTag: String?, newStaticContext: Map<String, String>) {
        if (level.logLevelValue < minimalVisibleLogLevel.logLevelValue) return
        val finalContext = context.mergeWith(newTag, newStaticContext)
        logHandler.log(
            message = message,
            level = level,
            throwable = throwable,
            context = finalContext,
        )
    }
}
