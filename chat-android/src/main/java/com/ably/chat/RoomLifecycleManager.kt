package com.ably.chat

import io.ably.lib.realtime.Channel as AblyRealtimeChannel
import io.ably.lib.types.ErrorInfo
import io.ably.lib.util.Log.LogHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * An interface for features that contribute to the room status.
 */
interface ContributesToRoomLifecycle : EmitsDiscontinuities {
    /**
     * Gets the channel on which the feature operates. This promise is never
     * rejected except in the case where room initialization is canceled.
     */
    val channel: CompletableDeferred<AblyRealtimeChannel>

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
enum class LifecycleOperationPrecedence(val operationPriority: Int) {
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
    (status: DefaultStatus, contributors: List<ResolvedContributor>, logger: LogHandler) {

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
    private val _logger: LogHandler = logger

    /**
     * sequentialCoroutineScope is to ensure the integrity and atomicity of operations that affect the room status, such as
     * attaching, detaching, and releasing the room. It makes sure that we don't have multiple operations happening
     * at once which could leave us in an inconsistent state.
     * It is used as a CoroutineContext for with [kotlinx.coroutines.selects.select] statement.
     * See [Kotlin Dispatchers](https://kt.academy/article/cc-dispatchers) for more information.
     */
    private val sequentialCoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    /**
     * This flag indicates whether some sort of controlled operation is in progress (e.g. attaching, detaching, releasing).
     *
     * It is used to prevent the room status from being changed by individual channel state changes and ignore
     * underlying channel events until we reach a consistent state.
     */
    private var _operationInProgress = false;

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
}
