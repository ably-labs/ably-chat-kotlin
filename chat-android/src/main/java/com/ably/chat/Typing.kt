@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import io.ably.lib.types.ErrorInfo
import io.ably.lib.realtime.Channel as AblyRealtimeChannel

/**
 * base retry interval, we double it each time
 */
const val PRESENCE_GET_RETRY_INTERVAL_MS = 1500

/**
 * max retry interval
 */
const val PRESENCE_GET_RETRY_MAX_INTERVAL_MS = 30_000

/**
 *  max num of retries
 */
const val PRESENCE_GET_MAX_RETRIES = 5

/**
 * This interface is used to interact with typing in a chat room including subscribing to typing events and
 * fetching the current set of typing clients.
 *
 * Get an instance via {@link Room.typing}.
 */
interface Typing : EmitsDiscontinuities {
    /**
     * Get the name of the realtime channel underpinning typing events.
     * @returns The name of the realtime channel.
     */
    val channel: AblyRealtimeChannel

    /**
     * Subscribe a given listener to all typing events from users in the chat room.
     *
     * @param listener A listener to be called when the typing state of a user in the room changes.
     */
    fun subscribe(listener: Listener): Subscription

    /**
     * Get the current typers, a set of clientIds.
     * @returns A Promise of a set of clientIds that are currently typing.
     */
    suspend fun get(): Set<String>

    /**
     * Start indicates that the current user is typing. This will emit a typingStarted event to inform listening clients and begin a timer,
     * once the timer expires, a typingStopped event will be emitted. The timeout is configurable through the typingTimeoutMs parameter.
     * If the current user is already typing, it will reset the timer and being counting down again without emitting a new event.
     */
    suspend fun start()

    /**
     * Stop indicates that the current user has stopped typing. This will emit a typingStopped event to inform listening clients,
     * and immediately clear the typing timeout timer.
     */
    suspend fun stop()

    /**
     * An interface for listening to changes for Typing
     */
    fun interface Listener {
        /**
         * A function that can be called when the new typing event happens.
         * @param event The event that happened.
         */
        fun onEvent(event: TypingEvent)
    }
}

/**
 * Represents a typing event.
 */
data class TypingEvent(val currentlyTyping: Set<String>)

internal class DefaultTyping(
    roomId: String,
    private val realtimeClient: RealtimeClient,
) : Typing, ContributesToRoomLifecycle, ResolvedContributor {

    private val typingIndicatorsChannelName = "$roomId::\$chat::\$typingIndicators"

    override val channel = realtimeClient.channels.get(typingIndicatorsChannelName, ChatChannelOptions())

    override val contributor: ContributesToRoomLifecycle = this

    override val attachmentErrorCode: ErrorCodes = ErrorCodes.TypingAttachmentFailed

    override val detachmentErrorCode: ErrorCodes = ErrorCodes.TypingDetachmentFailed

    override fun subscribe(listener: Typing.Listener): Subscription {
        TODO("Not yet implemented")
    }

    override suspend fun get(): Set<String> {
        TODO("Not yet implemented")
    }

    override suspend fun start() {
        TODO("Not yet implemented")
    }

    override suspend fun stop() {
        TODO("Not yet implemented")
    }

    override fun onDiscontinuity(listener: EmitsDiscontinuities.Listener): Subscription {
        TODO("Not yet implemented")
    }

    override fun discontinuityDetected(reason: ErrorInfo?) {
        TODO("Not yet implemented")
    }
}
