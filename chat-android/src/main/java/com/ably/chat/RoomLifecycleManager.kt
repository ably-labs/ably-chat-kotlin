package com.ably.chat

import io.ably.lib.types.ErrorInfo
import kotlinx.coroutines.CoroutineScope
import io.ably.lib.realtime.Channel as AblyRealtimeChannel

/**
 * An interface for features that contribute to the room status.
 */
interface ContributesToRoomLifecycle : EmitsDiscontinuities, HandlesDiscontinuity {

    /**
     * Name of the feature
     */
    val featureName: String

    /**
     * Gets the channel on which the feature operates. This promise is never
     * rejected except in the case where room initialization is canceled.
     */
    override val channel: AblyRealtimeChannel

    /**
     * Gets the ErrorInfo code that should be used when the feature fails to attach.
     * @returns The error that should be used when the feature fails to attach.
     */
    val attachmentErrorCode: ErrorCode

    /**
     * Gets the ErrorInfo code that should be used when the feature fails to detach.
     * @returns The error that should be used when the feature fails to detach.
     */
    val detachmentErrorCode: ErrorCode

    /**
     * Underlying Realtime feature channel is removed from the core SDK to prevent leakage.
     * Spec: CHA-RL3h
     */
    fun release()
}

internal abstract class ContributesToRoomLifecycleImpl(logger: Logger) : ContributesToRoomLifecycle {

    private val discontinuityEmitter = DiscontinuityEmitter(logger)

    override fun onDiscontinuity(listener: EmitsDiscontinuities.Listener): Subscription {
        discontinuityEmitter.on(listener)
        return Subscription {
            discontinuityEmitter.off(listener)
        }
    }

    override fun discontinuityDetected(reason: ErrorInfo?) {
        discontinuityEmitter.emit("discontinuity", reason)
    }
}

/**
 * An implementation of the `Status` interface.
 * @internal
 */
@Suppress("UnusedPrivateProperty")
internal class RoomLifecycleManager(
    private val roomScope: CoroutineScope,
    private val statusLifecycle: DefaultRoomLifecycle,
    private val contributors: List<ContributesToRoomLifecycle>,
    private val logger: Logger,
) {

    /**
     * Try to attach all the channels in a room.
     *
     * If the operation succeeds, the room enters the attached state and this promise resolves.
     * If a channel enters the suspended state, then we reject, but we will retry after a short delay as is the case
     * in the core SDK.
     * If a channel enters the failed state, we reject and then begin to wind down the other channels.
     * Spec: CHA-RL1
     */
    @Suppress("ThrowsCount")
    internal suspend fun attach() {
        // TODO - Need to implement proper attach with fallback
        for (contributor in contributors) {
            contributor.channel.attachCoroutine()
        }
    }

    /**
     * Detaches the room. If the room is already detached, this is a no-op.
     * If one of the channels fails to detach, the room status will be set to failed.
     * If the room is in the process of detaching, this will wait for the detachment to complete.
     * Spec: CHA-RL2
     */
    @Suppress("ThrowsCount")
    internal suspend fun detach() {
        // TODO - Need to implement proper detach with fallback
        for (contributor in contributors) {
            contributor.channel.detachCoroutine()
        }
    }

    /**
     * Releases the room. If the room is already released, this is a no-op.
     * Any channel that detaches into the failed state is ok. But any channel that fails to detach
     * will cause the room status to be set to failed.
     *
     * @returns Returns when the room is released. If a channel detaches into a non-terminated
     * state (e.g. attached), release will throw exception.
     * Spec: CHA-RL3
     */
    internal suspend fun release() {
        // TODO - Need to implement proper release with fallback
        for (contributor in contributors) {
            contributor.release()
        }
    }
}
