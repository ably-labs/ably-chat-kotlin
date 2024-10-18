package com.ably.chat

/**
 * Configuration options for the chat client.
 */
data class ClientOptions(
    /**
     * A custom log handler that will be used to log messages from the client.
     * @defaultValue The client will log messages to the console.
     */
    val logHandler: LogHandler? = null,

    /**
     * The minimum log level at which messages will be logged.
     * @defaultValue LogLevel.Error
     */
    val logLevel: LogLevel = LogLevel.Error,
)
