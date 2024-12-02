@file:Suppress("StringLiteralDuplication", "NotImplementedDeclaration")

package com.ably.chat

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.ably.lib.realtime.Channel
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * This interface is used to interact with occupancy in a chat room: subscribing to occupancy updates and
 * fetching the current room occupancy metrics.
 *
 * Get an instance via [Room.occupancy].
 */
interface Occupancy : EmitsDiscontinuities {
    /**
     * Get underlying Ably channel for occupancy events.
     *
     * @returns The underlying Ably channel for occupancy events.
     */
    val channel: Channel

    /**
     * Subscribe a given listener to occupancy updates of the chat room.
     *
     * @param listener A listener to be called when the occupancy of the room changes.
     */
    fun subscribe(listener: Listener): Subscription

    /**
     * Get the current occupancy of the chat room.
     *
     * @returns the current occupancy of the chat room.
     */
    suspend fun get(): OccupancyEvent

    /**
     * An interface for listening to new occupancy event
     */
    fun interface Listener {
        /**
         * A function that can be called when the new occupancy event happens.
         * @param event The event that happened.
         */
        fun onEvent(event: OccupancyEvent)
    }
}

/**
 * Represents the occupancy of a chat room.
 *
 * (CHA-O2)
 */
data class OccupancyEvent(
    /**
     * The number of connections to the chat room.
     */
    val connections: Int,

    /**
     * The number of presence members in the chat room - members who have entered presence.
     */
    val presenceMembers: Int,
)

internal class DefaultOccupancy(
    private val room: DefaultRoom,
) : Occupancy, ContributesToRoomLifecycleImpl(room.roomLogger) {

    override val featureName: String = "occupancy"

    override val attachmentErrorCode: ErrorCode = ErrorCode.OccupancyAttachmentFailed

    override val detachmentErrorCode: ErrorCode = ErrorCode.OccupancyDetachmentFailed

    private val logger = room.roomLogger.withContext(tag = "Occupancy")

    override val channel: Channel = room.messages.channel

    private val listeners: MutableList<Occupancy.Listener> = CopyOnWriteArrayList()

    private val eventBus = MutableSharedFlow<OccupancyEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val occupancyScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())
    private val occupancySubscription: Subscription

    init {
        occupancyScope.launch {
            eventBus.collect { occupancyEvent ->
                listeners.forEach {
                    it.onEvent(occupancyEvent)
                }
            }
        }

        val occupancyListener = PubSubMessageListener {
            internalChannelListener(it)
        }

        channel.subscribe(occupancyListener)

        occupancySubscription = Subscription {
            channel.unsubscribe(occupancyListener)
        }
    }

    // (CHA-O4)
    override fun subscribe(listener: Occupancy.Listener): Subscription {
        logger.trace("Occupancy.subscribe()")
        listeners.add(listener)

        return Subscription {
            logger.trace("Occupancy.unsubscribe()")
            // (CHA-04b)
            listeners.remove(listener)
        }
    }

    // (CHA-O3)
    override suspend fun get(): OccupancyEvent {
        logger.trace("Occupancy.get()")
        return room.chatApi.getOccupancy(room.roomId)
    }

    override fun release() {
        occupancySubscription.unsubscribe()
        occupancyScope.cancel()
    }

    /**
     * An internal listener that listens for occupancy events from the underlying channel and translates them into
     * occupancy events for the public API.
     */
    @Suppress("ReturnCount")
    private fun internalChannelListener(message: PubSubMessage) {
        val data = message.data as? JsonObject

        if (data == null) {
            logger.error(
                "invalid occupancy event received; data is not an object",
                staticContext = mapOf(
                    "message" to message.toString(),
                ),
            )
            // (CHA-04d)
            return
        }

        val metrics = data.get("metrics") as? JsonObject

        if (metrics == null) {
            logger.error(
                "invalid occupancy event received; metrics is missing",
                staticContext = mapOf(
                    "data" to data.toString(),
                ),
            )
            // (CHA-04d)
            return
        }

        val connections = metrics.get("connections") as? JsonPrimitive

        if (connections == null) {
            logger.error(
                "invalid occupancy event received; connections is missing",
                staticContext = mapOf(
                    "data" to data.toString(),
                ),
            )
            // (CHA-04d)
            return
        }

        val presenceMembers = metrics.get("presenceMembers") as? JsonPrimitive

        if (presenceMembers == null) {
            logger.error(
                "invalid occupancy event received; presenceMembers is missing",
                staticContext = mapOf(
                    "data" to data.toString(),
                ),
            )
            // (CHA-04d)
            return
        }

        eventBus.tryEmit(
            // (CHA-04c)
            OccupancyEvent(
                connections = connections.asInt,
                presenceMembers = presenceMembers.asInt,
            ),
        )
    }
}
