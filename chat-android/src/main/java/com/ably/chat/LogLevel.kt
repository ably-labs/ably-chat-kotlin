package com.ably.chat

/**
 * Represents the different levels of logging that can be used.
 */
enum class LogLevel(val logLevelValue: Int) {
    /**
     * Something routine and expected has occurred. This level will provide logs for the vast majority of operations
     * and function calls.
     */
    Trace(0),

    /**
     * Development information, messages that are useful when trying to debug library behavior,
     * but superfluous to normal operation.
     */
    Debug(1),

    /**
     * Informational messages. Operationally significant to the library but not out of the ordinary.
     */
    Info(2),

    /**
     * Anything that is not immediately an error, but could cause unexpected behavior in the future. For example,
     * passing an invalid value to an option. Indicates that some action should be taken to prevent future errors.
     */
    Warn(3),

    /**
     * A given operation has failed and cannot be automatically recovered. The error may threaten the continuity
     * of operation.
     */
    Error(4),

    /**
     * No logging will be performed.
     */
    Silent(5),
}
