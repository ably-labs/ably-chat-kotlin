@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.Presence.PresenceListener
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * base retry interval, we double it each time
 */
const val PRESENCE_GET_RETRY_INTERVAL_MS: Long = 1500

/**
 * max retry interval
 */
const val PRESENCE_GET_RETRY_MAX_INTERVAL_MS: Long = 30_000

/**
 *  max num of retries
 */
const val PRESENCE_GET_MAX_RETRIES = 5

/**
 * This interface is used to interact with typing in a chat room including subscribing to typing events and
 * fetching the current set of typing clients.
 *
 * Get an instance via [Room.typing].
 */
interface Typing : EmitsDiscontinuities {
    /**
     * Get the name of the realtime channel underpinning typing events.
     * @returns The name of the realtime channel.
     */
    val channel: Channel

    /**
     * Subscribe a given listener to all typing events from users in the chat room.
     *
     * @param listener A listener to be called when the typing state of a user in the room changes.
     */
    fun subscribe(listener: Listener): Subscription

    /**
     * Get the current typers, a set of clientIds.
     * @return set of clientIds that are currently typing.
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
    private val room: DefaultRoom,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Typing, ContributesToRoomLifecycleImpl(room.logger) {
    private val typingIndicatorsChannelName = "${room.roomId}::\$chat::\$typingIndicators"

    override val featureName = "typing"

    override val attachmentErrorCode: ErrorCode = ErrorCode.TypingAttachmentFailed

    override val detachmentErrorCode: ErrorCode = ErrorCode.TypingDetachmentFailed

    private val logger = room.logger.withContext(tag = "Typing")

    private val typingScope = CoroutineScope(dispatcher.limitedParallelism(1) + SupervisorJob())

    private val eventBus = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val channel: Channel = room.realtimeClient.channels.get(typingIndicatorsChannelName, ChatChannelOptions()) // CHA-RC2f

    private var typingJob: Job? = null

    private val listeners: MutableList<Typing.Listener> = CopyOnWriteArrayList()

    private var lastTyping: Set<String> = setOf()

    private var presenceSubscription: Subscription

    init {
        typingScope.launch {
            eventBus.collect {
                processEvent()
            }
        }

        val presenceListener = PresenceListener {
            if (it.clientId == null) {
                logger.error("unable to handle typing event; no clientId", staticContext = mapOf("member" to it.toString()))
            } else {
                eventBus.tryEmit(Unit)
            }
        }

        channel.presence.subscribe(presenceListener)

        presenceSubscription = Subscription {
            channel.presence.unsubscribe(presenceListener)
        }
    }

    override fun subscribe(listener: Typing.Listener): Subscription {
        logger.trace("DefaultTyping.subscribe()")
        listeners.add(listener)
        return Subscription {
            logger.trace("DefaultTyping.unsubscribe()")
            listeners.remove(listener)
        }
    }

    override suspend fun get(): Set<String> {
        logger.trace("DefaultTyping.get()")
        room.ensureAttached(logger) // CHA-T2d, CHA-T2c, CHA-T2g
        return channel.presence.getCoroutine().map { it.clientId }.toSet()
    }

    override suspend fun start() {
        logger.trace("DefaultTyping.start()")

        typingScope.launch {
            // If the user is already typing, reset the timer
            if (typingJob != null) {
                logger.debug("DefaultTyping.start(); already typing, resetting timer")
                typingJob?.cancel()
                startTypingTimer()
            } else {
                startTypingTimer()
                room.ensureAttached(logger) // CHA-T4a1, CHA-T4a3, CHA-T4a4
                channel.presence.enterClientCoroutine(room.clientId)
            }
        }.join()
    }

    override suspend fun stop() {
        logger.trace("DefaultTyping.stop()")
        typingScope.launch {
            typingJob?.cancel()
            room.ensureAttached(logger) // CHA-T5e, CHA-T5c, CHA-T5d
            channel.presence.leaveClientCoroutine(room.clientId)
        }.join()
    }

    override fun release() {
        presenceSubscription.unsubscribe()
        typingScope.cancel()
        room.realtimeClient.channels.release(channel.name)
    }

    private fun startTypingTimer() {
        val timeout = room.options.typing?.timeoutMs ?: throw AblyException.fromErrorInfo(
            ErrorInfo(
                "Typing options hasn't been initialized",
                ErrorCode.BadRequest.code,
            ),
        )
        logger.trace("DefaultTyping.startTypingTimer()")
        typingJob = typingScope.launch {
            delay(timeout)
            logger.debug("DefaultTyping.startTypingTimer(); timeout expired")
            stop()
        }
    }

    private suspend fun processEvent() {
        var numRetries = 0
        while (numRetries <= PRESENCE_GET_MAX_RETRIES) {
            try {
                val currentlyTyping = get()
                emit(currentlyTyping)
                return // Exit if successful
            } catch (e: Exception) {
                numRetries++
                val delayDuration = min(
                    PRESENCE_GET_RETRY_MAX_INTERVAL_MS,
                    PRESENCE_GET_RETRY_INTERVAL_MS * 2.0.pow(numRetries).toLong(),
                )
                logger.debug("Retrying in $delayDuration ms... (Attempt $numRetries of $PRESENCE_GET_MAX_RETRIES)", e)
                delay(delayDuration)
            }
        }
        logger.error("Failed to get members after $PRESENCE_GET_MAX_RETRIES retries")
    }

    private fun emit(currentlyTyping: Set<String>) {
        if (lastTyping == currentlyTyping) return
        lastTyping = currentlyTyping
        listeners.forEach {
            it.onEvent(TypingEvent(currentlyTyping))
        }
    }
}
