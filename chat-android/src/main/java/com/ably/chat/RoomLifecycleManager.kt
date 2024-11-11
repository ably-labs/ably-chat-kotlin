package com.ably.chat

import io.ably.lib.realtime.ChannelState
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import io.ably.lib.util.Log.LogHandler
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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
    val attachmentErrorCode: ErrorCodes

    /**
     * Gets the ErrorInfo code that should be used when the feature fails to detach.
     * @returns The error that should be used when the feature fails to detach.
     */
    val detachmentErrorCode: ErrorCodes
}

abstract class ContributesToRoomLifecycleImpl : ContributesToRoomLifecycle {

    private val discontinuityEmitter = DiscontinuityEmitter()

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
    internal var statusField: RoomStatus = RoomStatus.Attached
    override val status: RoomStatus
        get() = statusField

    internal var failedFeatureField: ResolvedContributor? = null
    override val failedFeature: ResolvedContributor?
        get() = failedFeatureField

    internal var errorField: ErrorInfo? = null
    override val error: ErrorInfo?
        get() = errorField

    internal var throwable: Throwable? = null

    override val exception: AblyException
        get() {
            val errorInfo = errorField
                ?: ErrorInfo("unknown error in attach", HttpStatusCodes.InternalServerError, ErrorCodes.RoomLifecycleError.errorCode)
            throwable?.let {
                return AblyException.fromErrorInfo(throwable, errorInfo)
            }
            return AblyException.fromErrorInfo(errorInfo)
        }
}

/**
 * An implementation of the `Status` interface.
 * @internal
 */
class RoomLifecycleManager(
    private val roomScope: CoroutineScope,
    lifecycle: DefaultRoomLifecycle,
    contributors: List<ResolvedContributor>,
    logger: LogHandler? = null,
) {

    /**
     * The status of the room.
     */
    private var _statusLifecycle: DefaultRoomLifecycle = lifecycle

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
    private val atomicCoroutineScope = AtomicCoroutineScope(roomScope)

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
    private val _pendingDiscontinuityEvents: DiscontinuityEventMap = mutableMapOf()

    /**
     * A map of contributors to whether their first attach has completed.
     *
     * Used to control whether we should trigger discontinuity events.
     */
    private val _firstAttachesCompleted = mutableMapOf<ResolvedContributor, Boolean>()

    /**
     * Retry duration in milliseconds, used by internal doRetry and runDownChannelsOnFailedAttach methods
     */
    private val _retryDurationInMs: Long = 250

    init {
        if (_statusLifecycle.status != RoomStatus.Attached) {
            _operationInProgress = true
        }
        // TODO - [CHA-RL4] set up room monitoring here
    }

    /**
     * Clears all transient detach timeouts - used when some event supersedes the transient detach such
     * as a failed channel or suspension.
     */
    private fun clearAllTransientDetachTimeouts() {
        // This will be implemented as a part of channel detach
    }

    /**
     * Given some contributor that has entered a suspended state:
     *
     * - Wind down any other channels
     * - Wait for our contributor to recover
     * - Attach everything else
     *
     * Repeat until either of the following happens:
     *
     * - Our contributor reattaches and we can attach everything else (repeat with the next contributor to break if necessary)
     * - The room enters a failed state
     *
     * @param contributor The contributor that has entered a suspended state.
     * @returns Returns when the room is attached, or the room enters a failed state.
     */
    @SuppressWarnings("CognitiveComplexMethod")
    private suspend fun doRetry(contributor: ResolvedContributor) {
        // Handle the channel wind-down for other channels
        var result = kotlin.runCatching { doChannelWindDown(contributor) }
        while (result.isFailure) {
            // If in doing the wind down, we've entered failed state, then it's game over anyway
            if (this._statusLifecycle.status === RoomStatus.Failed) {
                error("room is in a failed state")
            }
            delay(_retryDurationInMs)
            result = kotlin.runCatching { doChannelWindDown(contributor) }
        }

        // A helper that allows us to retry the attach operation
        val doAttachWithRetry: suspend () -> Unit = {
            coroutineScope {
                _statusLifecycle.setStatus(RoomStatus.Attaching)
                val attachmentResult = doAttach()

                // If we're in failed, then we should wind down all the channels, eventually - but we're done here
                if (attachmentResult.status === RoomStatus.Failed) {
                    atomicCoroutineScope.async(LifecycleOperationPrecedence.Internal.priority) {
                        runDownChannelsOnFailedAttach()
                    }
                    return@coroutineScope
                }

                // If we're in suspended, then we should wait for the channel to reattach, but wait for it to do so
                if (attachmentResult.status === RoomStatus.Suspended) {
                    val failedFeature = attachmentResult.failedFeature
                    if (failedFeature == null) {
                        AblyException.fromErrorInfo(
                            ErrorInfo(
                                "no failed feature in doRetry",
                                HttpStatusCodes.InternalServerError,
                                ErrorCodes.RoomLifecycleError.errorCode,
                            ),
                        )
                    }
                    // No need to catch errors, rather they should propagate to caller method
                    return@coroutineScope doRetry(failedFeature as ResolvedContributor)
                }
                // We attached, huzzah!
            }
        }

        // If given suspended contributor channel has reattached, then we can retry the attach
        if (contributor.channel.state == ChannelState.attached) {
            return doAttachWithRetry()
        }

        // Otherwise, wait for our suspended contributor channel to re-attach and try again
        try {
            listenToChannelAttachOrFailure(contributor)
            // Attach successful
            return doAttachWithRetry()
        } catch (ex: AblyException) {
            // Channel attach failed
            _statusLifecycle.setStatus(RoomStatus.Failed, ex.errorInfo)
            throw ex
        }
    }

    private suspend fun listenToChannelAttachOrFailure(contributor: ResolvedContributor) = suspendCancellableCoroutine { continuation ->
        contributor.channel.once(ChannelState.attached) {
            continuation.resume(Unit)
        }
        contributor.channel.once(ChannelState.failed) {
            val exception = AblyException.fromErrorInfo(
                it.reason
                    ?: ErrorInfo("unknown error in _doRetry", HttpStatusCodes.InternalServerError, ErrorCodes.RoomLifecycleError.errorCode),
            )
            continuation.resumeWithException(exception)
        }
    }

    /**
     * Try to attach all the channels in a room.
     *
     * If the operation succeeds, the room enters the attached state and this promise resolves.
     * If a channel enters the suspended state, then we reject, but we will retry after a short delay as is the case
     * in the core SDK.
     * If a channel enters the failed state, we reject and then begin to wind down the other channels.
     * Spec: CHA-RL1
     */
    @SuppressWarnings("ThrowsCount")
    internal suspend fun attach() {
        val deferredAttach = atomicCoroutineScope.async(LifecycleOperationPrecedence.AttachOrDetach.priority) { // CHA-RL1d
            when (_statusLifecycle.status) {
                RoomStatus.Attached -> return@async // CHA-RL1a
                RoomStatus.Releasing -> // CHA-RL1b
                    throw AblyException.fromErrorInfo(
                        ErrorInfo(
                            "unable to attach room; room is releasing",
                            HttpStatusCodes.InternalServerError,
                            ErrorCodes.RoomIsReleasing.errorCode,
                        ),
                    )
                RoomStatus.Released -> // CHA-RL1c
                    throw AblyException.fromErrorInfo(
                        ErrorInfo(
                            "unable to attach room; room is released",
                            HttpStatusCodes.InternalServerError,
                            ErrorCodes.RoomIsReleased.errorCode,
                        ),
                    )
                else -> {}
            }

            // At this point, we force the room status to be attaching
            clearAllTransientDetachTimeouts()
            _operationInProgress = true
            _statusLifecycle.setStatus(RoomStatus.Attaching) // CHA-RL1e

            val attachResult = doAttach()

            // CHA-RL1h4 - If we're in a failed state, then we should wind down all the channels, eventually
            if (attachResult.status === RoomStatus.Failed) {
                // CHA-RL1h5 - detach all remaining channels
                atomicCoroutineScope.async(LifecycleOperationPrecedence.Internal.priority) {
                    runDownChannelsOnFailedAttach()
                }
                throw attachResult.exception // CHA-RL1h1
            }

            // CHA-RL1h1, CHA-RL1h2 - If we're in suspended, then this attach should fail, but we'll retry after a short delay async
            if (attachResult.status === RoomStatus.Suspended) {
                if (attachResult.failedFeature == null) {
                    AblyException.fromErrorInfo(
                        ErrorInfo(
                            "no failed feature in attach",
                            HttpStatusCodes.InternalServerError,
                            ErrorCodes.RoomLifecycleError.errorCode,
                        ),
                    )
                }
                attachResult.failedFeature?.let {
                    // CHA-RL1h3 - Enter recovery for failed room feature/contributor
                    atomicCoroutineScope.async(LifecycleOperationPrecedence.Internal.priority) {
                        doRetry(it)
                    }
                }
                throw attachResult.exception // CHA-RL1h1
            }

            // We attached, finally!
        }

        deferredAttach.await()
    }

    /**
     *
     * Attaches each feature channel with rollback on channel attach failure.
     * This method is re-usable and can be called as a part of internal room operations.
     * Spec: CHA-RL1f, CHA-RL1g, CHA-RL1h
     */
    private suspend fun doAttach(): RoomAttachmentResult {
        val attachResult = DefaultRoomAttachmentResult()
        for (feature in _contributors) { // CHA-RL1f - attach each feature sequentially
            try {
                feature.channel.attachCoroutine()
                _firstAttachesCompleted[feature] = true
            } catch (ex: Throwable) { // CHA-RL1h - handle channel attach failure
                attachResult.throwable = ex
                attachResult.failedFeatureField = feature
                attachResult.errorField = ErrorInfo(
                    "failed to attach ${feature.contributor.featureName} feature${feature.channel.errorMessage}",
                    HttpStatusCodes.InternalServerError,
                    feature.contributor.attachmentErrorCode.errorCode,
                )

                // The current feature should be in one of two states, it will be either suspended or failed
                // If it's in suspended, we wind down the other channels and wait for the reattach
                // If it's failed, we can fail the entire room
                when (feature.channel.state) {
                    ChannelState.suspended -> attachResult.statusField = RoomStatus.Suspended
                    ChannelState.failed -> attachResult.statusField = RoomStatus.Failed
                    else -> {
                        attachResult.statusField = RoomStatus.Failed
                        attachResult.errorField = ErrorInfo(
                            "unexpected channel state in doAttach ${feature.channel.state}${feature.channel.errorMessage}",
                            HttpStatusCodes.InternalServerError,
                            ErrorCodes.RoomLifecycleError.errorCode,
                        )
                    }
                }

                // Regardless of whether we're suspended or failed, run-down the other channels
                // The wind-down procedure will take Precedence over any user-driven actions
                _statusLifecycle.setStatus(attachResult)
                return attachResult
            }
        }

        // CHA-RL1g, We successfully attached all the channels - set our status to attached, start listening changes in channel status
        this._statusLifecycle.setStatus(attachResult)
        this._operationInProgress = false

        // Iterate the pending discontinuity events and trigger them
        for ((contributor, error) in _pendingDiscontinuityEvents) {
            contributor.contributor.discontinuityDetected(error)
        }
        _pendingDiscontinuityEvents.clear()
        return attachResult
    }

    /**
     * If we've failed to attach, then we're in the failed state and all that is left to do is to detach all the channels.
     * Spec: CHA-RL1h5, CHA-RL1h6
     * @returns Returns only when all channels are detached. Doesn't throw exception.
     */
    private suspend fun runDownChannelsOnFailedAttach() {
        // At this point, we have control over the channel lifecycle, so we can hold onto it until things are resolved
        // Keep trying to detach the channels until they're all detached.
        var channelWindDown = kotlin.runCatching { doChannelWindDown() }
        while (channelWindDown.isFailure) { // CHA-RL1h6 - repeat until all channels are detached
            // Something went wrong during the wind down. After a short delay, to give others a turn, we should run down
            // again until we reach a suitable conclusion.
            delay(_retryDurationInMs)
            channelWindDown = kotlin.runCatching { doChannelWindDown() }
        }
    }

    /**
     * Detach all features except the one exception provided.
     * If the room is in a failed state, then all channels should either reach the failed state or be detached.
     * Spec: CHA-RL1h5
     * @param except The contributor to exclude from the detachment.
     * @returns Success/Failure when all channels are detached or at least one of them fails.
     *
     */
    @SuppressWarnings("CognitiveComplexMethod", "ComplexCondition")
    private suspend fun doChannelWindDown(except: ResolvedContributor? = null) = coroutineScope {
        _contributors.map { contributor: ResolvedContributor ->
            async {
                // If its the contributor we want to wait for a conclusion on, then we should not detach it
                // Unless we're in a failed state, in which case we should detach it
                if (contributor === except && _statusLifecycle.status !== RoomStatus.Failed) {
                    return@async
                }
                // If the room's already in the failed state, or it's releasing, we should not detach a failed channel
                if ((
                        _statusLifecycle.status === RoomStatus.Failed ||
                            _statusLifecycle.status === RoomStatus.Releasing ||
                            _statusLifecycle.status === RoomStatus.Released
                        ) &&
                    contributor.channel.state === ChannelState.failed
                ) {
                    return@async
                }

                try {
                    contributor.channel.detachCoroutine()
                } catch (throwable: Throwable) {
                    // If the contributor is in a failed state and we're not ignoring failed states, we should fail the room
                    if (
                        contributor.channel.state === ChannelState.failed &&
                        _statusLifecycle.status !== RoomStatus.Failed &&
                        _statusLifecycle.status !== RoomStatus.Releasing &&
                        _statusLifecycle.status !== RoomStatus.Released
                    ) {
                        val contributorError = ErrorInfo(
                            "failed to detach feature",
                            HttpStatusCodes.InternalServerError,
                            contributor.contributor.detachmentErrorCode.errorCode,
                        )
                        _statusLifecycle.setStatus(RoomStatus.Failed, contributorError)
                        throw AblyException.fromErrorInfo(throwable, contributorError)
                    }

                    // We throw an error so that the promise rejects
                    throw AblyException.fromErrorInfo(throwable, ErrorInfo("detach failure, retry", -1, -1))
                }
            }
        }.awaitAll()
    }

    /**
     * Detaches the room. If the room is already detached, this is a no-op.
     * If one of the channels fails to detach, the room status will be set to failed.
     * If the room is in the process of detaching, this will wait for the detachment to complete.
     * @return when the room is detached.
     */
    @Suppress("ThrowsCount")
    internal suspend fun detach() {
        val deferredDetach = atomicCoroutineScope.async(LifecycleOperationPrecedence.AttachOrDetach.priority) {
            // If we're already detached, this is a no-op
            if (_statusLifecycle.status === RoomStatus.Detached) {
                return@async
            }
            // If the room is released, we can't detach
            if (_statusLifecycle.status === RoomStatus.Released) {
                throw AblyException.fromErrorInfo(
                    ErrorInfo(
                        "unable to detach room; room is released",
                        HttpStatusCodes.InternalServerError,
                        ErrorCodes.RoomIsReleased.errorCode,
                    ),
                )
            }

            // If the room is releasing, we can't detach
            if (_statusLifecycle.status === RoomStatus.Releasing) {
                throw AblyException.fromErrorInfo(
                    ErrorInfo(
                        "unable to detach room; room is releasing",
                        HttpStatusCodes.InternalServerError,
                        ErrorCodes.RoomIsReleasing.errorCode,
                    ),
                )
            }

            // If we're in failed, we should not attempt to detach
            if (_statusLifecycle.status === RoomStatus.Failed) {
                throw AblyException.fromErrorInfo(
                    ErrorInfo(
                        "unable to detach room; room has failed",
                        HttpStatusCodes.InternalServerError,
                        ErrorCodes.RoomInFailedState.errorCode,
                    ),
                )
            }

            // We force the room status to be detaching
            _operationInProgress = true
            clearAllTransientDetachTimeouts()
            _statusLifecycle.setStatus(RoomStatus.Detaching)

            // We now perform an all-channel wind down.
            // We keep trying until we reach a suitable conclusion.
            return@async doDetach()
        }
        return deferredDetach.await()
    }

    /**
     * Perform a detach.
     *
     * If detaching a channel fails, we should retry until every channel is either in the detached state, or in the failed state.
     */
    private suspend fun doDetach() {
        var channelWindDown = kotlin.runCatching { doChannelWindDown() }
        var failedError: AblyException? = null
        while (channelWindDown.isFailure) {
            val err = channelWindDown.exceptionOrNull()
            if (err is AblyException && err.errorInfo?.code != -1 && failedError == null) {
                failedError = err
            }
            delay(_retryDurationInMs)
            channelWindDown = kotlin.runCatching { doChannelWindDown() }
        }

        // If we aren't in the failed state, then we're detached
        if (_statusLifecycle.status !== RoomStatus.Failed) {
            _statusLifecycle.setStatus(RoomStatus.Detached)
            return
        }

        // If we're in the failed state, then we need to throw the error
        throw failedError
            ?: AblyException.fromErrorInfo(
                ErrorInfo(
                    "unknown error in _doDetach",
                    HttpStatusCodes.InternalServerError,
                    ErrorCodes.RoomLifecycleError.errorCode,
                ),
            )
    }

    /**
     * Releases the room. If the room is already released, this is a no-op.
     * Any channel that detaches into the failed state is ok. But any channel that fails to detach
     * will cause the room status to be set to failed.
     *
     * @returns Returns when the room is released. If a channel detaches into a non-terminated
     * state (e.g. attached), release will throw exception.
     */
    internal suspend fun release() {
        // TODO("Need to impl. room release")
    }
}
