package com.ably.chat

import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import io.ably.lib.util.Log.LogHandler
import io.ably.lib.realtime.Channel as AblyRealtimeChannel

/**
 * An interface for features that contribute to the room status.
 */
interface ContributesToRoomLifecycle : EmitsDiscontinuities {
    /**
     * Gets the channel on which the feature operates. This promise is never
     * rejected except in the case where room initialization is canceled.
     */
    val channel: AblyRealtimeChannel

    /**
     * Gets the ErrorInfo code that should be used when the feature fails to attach.
     * @returns The error that should be used when the feature fails to attach.
     */
    val attachmentErrorCode: ErrorCodes

    /**
     * Gets the ErrorInfo code that should be used when the feature fails to detach.
     * @returns The error that should be used when the feature fails to detach.
     */
    val detachmentErrorCode: ErrorCodes
}

/**
 * This interface represents a feature that contributes to the room lifecycle and
 * exposes its channel directly. Objects of this type are created by awaiting the
 * channel promises of all the {@link ContributesToRoomLifecycle} objects.
 *
 * @internal
 */
interface ResolvedContributor {
    val channel: AblyRealtimeChannel
    val contributor: ContributesToRoomLifecycle
}

/**
 * The order of precedence for lifecycle operations, passed to PriorityQueueExecutor which allows
 * us to ensure that internal operations take precedence over user-driven operations.
 */
enum class LifecycleOperationPrecedence(val priority: Int) {
    Internal(1),
    Release(2),
    AttachOrDetach(3),
}

/**
 * A map of contributors to pending discontinuity events.
 */
typealias DiscontinuityEventMap = Map<ResolvedContributor, ErrorInfo?>

/**
 * An internal interface that represents the result of a room attachment operation.
 */
interface RoomAttachmentResult : NewRoomStatus {
    val failedFeature: ResolvedContributor?
}

/**
 * An implementation of the `Status` interface.
 * @internal
 */
class RoomLifecycleManager
(status: DefaultStatus, contributors: List<ResolvedContributor>, logger: LogHandler?) {

    /**
     * The status of the room.
     */
    private var _status: DefaultStatus = status

    /**
     * The features that contribute to the room status.
     */
    private var _contributors: List<ResolvedContributor> = contributors

    /**
     * Logger for RoomLifeCycleManager
     */
    private val _logger: LogHandler? = logger

    /**
     * AtomicCoroutineScope makes sure all operations are atomic and run with given priority.
     * See [Kotlin Dispatchers](https://kt.academy/article/cc-dispatchers) for more information.
     * Spec: CHA-RL7
     */
    private val atomicCoroutineScope = AtomicCoroutineScope()

    /**
     * This flag indicates whether some sort of controlled operation is in progress (e.g. attaching, detaching, releasing).
     *
     * It is used to prevent the room status from being changed by individual channel state changes and ignore
     * underlying channel events until we reach a consistent state.
     */
    private var _operationInProgress = false

    /**
     * A map of contributors to whether their first attach has completed.
     *
     * Used to control whether we should trigger discontinuity events.
     */
    private val _firstAttachesCompleted = mutableMapOf<ResolvedContributor, Boolean>()

    init {
        if (_status.current != RoomLifecycle.Attached) {
            _operationInProgress = true
        }
        // TODO - [CHA-RL4] set up room monitoring here
    }

    internal suspend fun attach() {
        val deferredAttach = atomicCoroutineScope.async(LifecycleOperationPrecedence.AttachOrDetach.priority) {
            when (_status.current) {
                RoomLifecycle.Attached -> return@async
                RoomLifecycle.Releasing ->
                    throw AblyException.fromErrorInfo(
                        ErrorInfo(
                            "Can't ATTACH since room is in RELEASING state",
                            ErrorCodes.RoomIsReleasing.errorCode,
                        ),
                    )
                RoomLifecycle.Released ->
                    throw AblyException.fromErrorInfo(
                        ErrorInfo(
                            "Can't ATTACH since room is in RELEASED state",
                            ErrorCodes.RoomIsReleased.errorCode,
                        ),
                    )
                else -> {}
            }
            doAttach()
        }
        deferredAttach.await()
    }

    /**
     *
     * Attaches each feature channel with rollback on channel attach failure.
     * This method is re-usable and can be called as a part of internal room operations.
     *
     */
    private suspend fun doAttach() {
        for (feature in _contributors) {
            feature.channel.attachCoroutine()
        }
    }
}
