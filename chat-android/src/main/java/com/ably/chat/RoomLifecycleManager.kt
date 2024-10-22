package com.ably.chat

import io.ably.lib.realtime.ChannelState
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import io.ably.lib.util.Log.LogHandler
import io.ably.lib.realtime.Channel as AblyRealtimeChannel

/**
 * An interface for features that contribute to the room status.
 */
interface ContributesToRoomLifecycle : EmitsDiscontinuities, HandlesDiscontinuity {
    /**
     * Gets the channel on which the feature operates. This promise is never
     * rejected except in the case where room initialization is canceled.
     */
    override val channel: AblyRealtimeChannel

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
typealias DiscontinuityEventMap = MutableMap<ResolvedContributor, ErrorInfo?>

/**
 * An internal interface that represents the result of a room attachment operation.
 */
interface RoomAttachmentResult : NewRoomStatus {
    val failedFeature: ResolvedContributor?
    val exception: AblyException
}

class DefaultRoomAttachmentResult : RoomAttachmentResult {
    internal var _failedFeature: ResolvedContributor? = null
    internal var _status: RoomLifecycle = RoomLifecycle.Attached
    internal var _error: ErrorInfo? = null

    override val failedFeature: ResolvedContributor? = _failedFeature
    override val exception: AblyException = AblyException.fromErrorInfo(_error?:
            ErrorInfo("unknown error in attach", ErrorCodes.RoomLifecycleError.errorCode, 500))

    override val status: RoomLifecycle = _status
    override val error: ErrorInfo? = _error
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
     * A map of pending discontinuity events.
     *
     * When a discontinuity happens due to a failed resume, we don't want to surface that until the room is consistently
     * attached again. This map allows us to queue up discontinuity events until we're ready to process them.
     */
    private val _pendingDiscontinuityEvents: DiscontinuityEventMap = mutableMapOf();

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

    /**
     * Clears all transient detach timeouts - used when some event supersedes the transient detach such
     * as a failed channel or suspension.
     */
    private fun clearAllTransientDetachTimeouts() {
        TODO("Need to implement")
    }

    private suspend fun doRetry(contributor: ResolvedContributor) {
        TODO("Need to implement")
    }

    internal suspend fun attach() {
        val deferredAttach = atomicCoroutineScope.async(LifecycleOperationPrecedence.AttachOrDetach.priority)
        {
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

            // At this point, we force the room status to be attaching
            clearAllTransientDetachTimeouts()
            _operationInProgress = true
            _status.setStatus(RoomLifecycle.Attaching)

            val attachResult = doAttach()

            // If we're in a failed state, then we should wind down all the channels, eventually
            if (attachResult.status === RoomLifecycle.Failed) {
                atomicCoroutineScope.async(LifecycleOperationPrecedence.Internal.priority) {
                    runDownChannelsOnFailedAttach()
                }
                throw attachResult.exception
            }

            // If we're in suspended, then this attach should fail, but we'll retry after a short delay async
            if (attachResult.status === RoomLifecycle.Suspended) {
                if (attachResult.failedFeature == null) {
                    AblyException.fromErrorInfo(
                        ErrorInfo("no failed feature in attach", ErrorCodes.RoomLifecycleError.errorCode, 500))
                }
                attachResult.failedFeature?.let {
                    atomicCoroutineScope.async(LifecycleOperationPrecedence.Internal.priority) {
                        doRetry(it)
                    }
                }
                throw attachResult.exception
            }

            // We attached, finally!
        }

        deferredAttach.await()
    }

    /**
     *
     * Attaches each feature channel with rollback on channel attach failure.
     * This method is re-usable and can be called as a part of internal room operations.
     *
     */
    private suspend fun doAttach(): RoomAttachmentResult {
        val attachResult = DefaultRoomAttachmentResult()
        for (feature in _contributors) {
            try {
                feature.channel.attachCoroutine()
                _firstAttachesCompleted[feature] = true
            }
            catch (ex: Throwable) {
                attachResult._failedFeature = feature
                attachResult._error = ErrorInfo(
                    "failed to attach feature",
                    feature.contributor.attachmentErrorCode.errorCode,
                    500,
                )

                // The current feature should be in one of two states, it will be either suspended or failed
                // If it's in suspended, we wind down the other channels and wait for the reattach
                // If it's failed, we can fail the entire room
                when (feature.channel.state) {
                    ChannelState.suspended -> attachResult._status = RoomLifecycle.Suspended
                    ChannelState.failed -> attachResult._status = RoomLifecycle.Failed
                    else -> {
                        attachResult._status = RoomLifecycle.Failed
                        attachResult._error = ErrorInfo(
                            "unexpected channel state in doAttach ${feature.channel.state}",
                            ErrorCodes.RoomLifecycleError.errorCode,
                            500,
                        )
                    }
                }

                // Regardless of whether we're suspended or failed, run-down the other channels
                // The wind-down procedure will take Precedence over any user-driven actions
                _status.setStatus(attachResult)
                return attachResult
            }
        }

        // We successfully attached all the channels - set our status to attached, start listening changes in channel status
        this._status.setStatus(attachResult);
        this._operationInProgress = false;

        // Iterate the pending discontinuity events and trigger them
        for ((contributor, error) in _pendingDiscontinuityEvents) {
            contributor.contributor.discontinuityDetected(error)
        }
        _pendingDiscontinuityEvents.clear()
        return attachResult;
    }

    /**
     * If we've failed to attach, then we're in the failed state and all that is left to do is to detach all the channels.
     *
     * @returns A promise that resolves when all channels are detached. We do not throw.
     */
    private suspend fun runDownChannelsOnFailedAttach() {
        TODO("Need to implement this")
    }
}
