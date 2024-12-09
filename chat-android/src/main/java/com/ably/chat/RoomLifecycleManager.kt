package com.ably.chat

import io.ably.lib.realtime.ChannelState
import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
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
     * The channel on which the feature operates
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
typealias DiscontinuityEventMap = MutableMap<ContributesToRoomLifecycle, ErrorInfo?>

/**
 * An internal interface that represents the result of a room attachment operation.
 */
interface RoomAttachmentResult : NewRoomStatus {
    val failedFeature: ContributesToRoomLifecycle?
    val exception: AblyException
}

class DefaultRoomAttachmentResult : RoomAttachmentResult {
    internal var statusField: RoomStatus = RoomStatus.Attached
    override val status: RoomStatus
        get() = statusField

    internal var failedFeatureField: ContributesToRoomLifecycle? = null
    override val failedFeature: ContributesToRoomLifecycle?
        get() = failedFeatureField

    internal var errorField: ErrorInfo? = null
    override val error: ErrorInfo?
        get() = errorField

    internal var throwable: Throwable? = null

    override val exception: AblyException
        get() {
            val errorInfo = errorField
                ?: lifeCycleErrorInfo("unknown error in attach", ErrorCode.RoomLifecycleError)
            return lifeCycleException(errorInfo, throwable)
        }
}

/**
 * An implementation of the `Status` interface.
 * @internal
 */
internal class RoomLifecycleManager(
    private val roomScope: CoroutineScope,
    private val statusLifecycle: DefaultRoomLifecycle,
    private val contributors: List<ContributesToRoomLifecycle>,
    roomLogger: Logger,
) {
    private val logger = roomLogger.withContext(
        "RoomLifecycleManager",
        dynamicContext = mapOf("scope" to { Thread.currentThread().name }),
    )

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
    private var operationInProgress = false

    /**
     * A map of pending discontinuity events.
     *
     * When a discontinuity happens due to a failed resume, we don't want to surface that until the room is consistently
     * attached again. This map allows us to queue up discontinuity events until we're ready to process them.
     */
    private val pendingDiscontinuityEvents: DiscontinuityEventMap = mutableMapOf()

    /**
     * A map of contributors to whether their first attach has completed.
     *
     * Used to control whether we should trigger discontinuity events.
     */
    private val firstAttachesCompleted = mutableMapOf<ContributesToRoomLifecycle, Boolean>()

    /**
     * Retry duration in milliseconds, used by internal doRetry and runDownChannelsOnFailedAttach methods
     */
    private val retryDurationInMs: Long = 250

    init {
        // TODO - [CHA-RL4] set up room monitoring here
    }

    /**
     * Clears all transient detach timeouts - used when some event supersedes the transient detach such
     * as a failed channel or suspension.
     */
    private fun clearAllTransientDetachTimeouts() {
        // This will be implemented as a part of room lifecycle monitoring
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
     * Spec: CHA-RL5
     */
    @Suppress("CognitiveComplexMethod", "ThrowsCount", "LongMethod")
    private suspend fun doRetry(contributor: ContributesToRoomLifecycle) {
        logger.trace("doRetry();")
        // Handle the channel wind-down.
        logger.debug("doRetry(); winding down channels except for feature: ${contributor.featureName}")
        // CHA-RL5a - Handle the channel wind-down for other channels
        var result = kotlin.runCatching { doChannelWindDown(contributor) }
        while (result.isFailure) {
            // CHA-RL5c - If in doing the wind down, we've entered failed state, then it's game over anyway
            if (this.statusLifecycle.status === RoomStatus.Failed) {
                logger.error("doRetry(); channels wind down failed, RoomStatus is FAILED", result.exceptionOrNull())
                throw result.exceptionOrNull() ?: IllegalStateException("room is in a failed state")
            }
            logger.warn("doRetry(); channels wind down failed, retrying in $retryDurationInMs ms", result.exceptionOrNull())
            delay(retryDurationInMs)
            result = kotlin.runCatching { doChannelWindDown(contributor) }
        }
        logger.debug("doRetry(); channels wind down successfully completed")

        // A helper that allows us to retry the attach operation
        val doAttachWithRetry: suspend () -> Unit = {
            logger.trace("doRetry.doAttachWithRetry();")
            coroutineScope {
                logger.debug("doRetry.doAttachWithRetry(); attempting to attach all feature channels")
                statusLifecycle.setStatus(RoomStatus.Attaching)
                val attachmentResult = doAttach()

                // CHA-RL5c - If we're in failed, then we should wind down all the channels, eventually - but we're done here
                if (attachmentResult.status === RoomStatus.Failed) {
                    logger.error("doRetry.doAttachWithRetry(); failed to attach channels, RoomStatus is Failed", attachmentResult.exception)
                    atomicCoroutineScope.async(LifecycleOperationPrecedence.Internal.priority) {
                        runDownChannelsOnFailedAttach()
                    }
                    logger.warn("doRetry.doAttachWithRetry(); started winding down all channels internally")
                    return@coroutineScope
                }

                // If we're in suspended, then we should wait for the channel to reattach, but wait for it to do so
                if (attachmentResult.status === RoomStatus.Suspended) {
                    logger.warn(
                        "doRetry.doAttachWithRetry(); failed to attach channels, RoomStatus is Suspended",
                        attachmentResult.exception,
                    )
                    val failedFeature = attachmentResult.failedFeature ?: throw lifeCycleException(
                        "no failed feature in doRetry",
                        ErrorCode.RoomLifecycleError,
                    )
                    logger.warn("doRetry.doAttachWithRetry(); Retrying attach due to suspended feature: ${failedFeature.featureName}")
                    // No need to catch errors, rather they should propagate to caller method
                    return@coroutineScope doRetry(failedFeature)
                }

                // We attached, huzzah!
                logger.debug("doRetry.doAttachWithRetry(); Room ATTACH success, RoomStatus: ${statusLifecycle.status}")
            }
        }

        // If given suspended contributor channel has reattached, then we can retry the attach
        if (contributor.channel.state == ChannelState.attached) {
            logger.debug("doRetry(); feature: ${contributor.featureName} reattached, retrying attach for others")
            return doAttachWithRetry()
        }

        // CHA-RL5d - Otherwise, wait for our suspended contributor channel to re-attach and try again
        try {
            logger.debug("doRetry(); waiting to channel ATTACHED for feature: ${contributor.featureName}")
            listenToChannelAttachOrFailure(contributor)
            logger.debug("doRetry(); waiting success, feature: ${contributor.featureName} ATTACHED, retrying attach for others")
            delay(retryDurationInMs) // Let other channels get into ATTACHING state
        } catch (ex: Throwable) {
            // CHA-RL5c - Channel attach failed
            logger.error("doRetry(); waiting failed for channel attach, feature: ${contributor.featureName}", ex)
            // No need for channels wind down, since it's taken care at the start of doRetry()
            statusLifecycle.setStatus(RoomStatus.Failed, (ex as? AblyException)?.errorInfo)
            throw ex
        }
        // Attach successful, try attach for other channels
        return doAttachWithRetry()
    }

    /**
     * CHA-RL5f, CHA-RL5e
     */
    private suspend fun listenToChannelAttachOrFailure(
        contributor: ContributesToRoomLifecycle,
    ) = suspendCancellableCoroutine { continuation ->
        // CHA-RL5f
        val resumeIfAttached = {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }
        contributor.channel.once(ChannelState.attached) {
            resumeIfAttached()
        }
        if (contributor.channel.state == ChannelState.attached) { // Just being on the safer side, check if channel got into ATTACHED state
            resumeIfAttached()
        }

        // CHA-RL5e
        val resumeWithExceptionIfFailed = { reason: ErrorInfo? ->
            if (continuation.isActive) {
                val exception = lifeCycleException(
                    reason ?: lifeCycleErrorInfo(
                        "unknown error in doRetry",
                        ErrorCode.RoomLifecycleError,
                    ),
                )
                continuation.resumeWithException(exception)
            }
        }
        contributor.channel.once(ChannelState.failed) {
            resumeWithExceptionIfFailed(it.reason)
        }
        if (contributor.channel.state == ChannelState.failed) { // Just being on the safer side, check if channel got into FAILED state
            resumeWithExceptionIfFailed(contributor.channel.reason)
        }
    }

    /**
     * Try to attach all the channels in a room.
     *
     * If the operation succeeds, the room enters the attached state.
     * If a channel enters the suspended state, then we throw exception, but we will retry after a short delay as is the case
     * in the core SDK.
     * If a channel enters the failed state, we throw an exception and then begin to wind down the other channels.
     * Spec: CHA-RL1
     */
    @Suppress("ThrowsCount")
    internal suspend fun attach() {
        logger.trace("attach();")
        val deferredAttach = atomicCoroutineScope.async(LifecycleOperationPrecedence.AttachOrDetach.priority) { // CHA-RL1d
            if (statusLifecycle.status == RoomStatus.Attached) { // CHA-RL1a
                logger.debug("attach(); room is already attached")
                return@async
            }

            if (statusLifecycle.status == RoomStatus.Releasing) { // CHA-RL1b
                logger.error("attach(); attach failed, room is in releasing state")
                throw lifeCycleException("unable to attach room; room is releasing", ErrorCode.RoomIsReleasing)
            }

            if (statusLifecycle.status == RoomStatus.Released) { // CHA-RL1c
                logger.error("attach(); attach failed, room is in released state")
                throw lifeCycleException("unable to attach room; room is released", ErrorCode.RoomIsReleased)
            }

            // At this point, we force the room status to be attaching
            clearAllTransientDetachTimeouts()
            operationInProgress = true
            statusLifecycle.setStatus(RoomStatus.Attaching) // CHA-RL1e
            logger.debug("attach(); transitioned room to ATTACHING state")

            val attachResult = doAttach()

            // CHA-RL1h4 - If we're in a failed state, then we should wind down all the channels, eventually
            if (attachResult.status === RoomStatus.Failed) {
                logger.error("attach(); failed to attach channels, RoomStatus is Failed", attachResult.exception)
                // CHA-RL1h5 - detach all remaining channels
                atomicCoroutineScope.async(LifecycleOperationPrecedence.Internal.priority) {
                    runDownChannelsOnFailedAttach()
                }
                logger.warn("attach(); started winding down all channels internally")
                throw attachResult.exception // CHA-RL1h1
            }

            // CHA-RL1h1, CHA-RL1h2 - If we're in suspended, then this attach should fail, but we'll retry after a short delay async
            if (attachResult.status === RoomStatus.Suspended) {
                logger.warn("attach(); failed to attach channels, RoomStatus is Suspended", attachResult.exception)
                if (attachResult.failedFeature == null) {
                    throw lifeCycleException("no failed feature in attach", ErrorCode.RoomLifecycleError)
                }
                attachResult.failedFeature?.let {
                    // CHA-RL1h3 - Enter recovery for failed room feature/contributor
                    atomicCoroutineScope.async(LifecycleOperationPrecedence.Internal.priority) {
                        doRetry(it)
                    }
                    logger.warn("attach(); retrying attach due to suspended feature: ${it.featureName}")
                }
                throw attachResult.exception // CHA-RL1h1
            }

            // We attached, finally!
            logger.debug("attach(): room attached successfully")
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
        logger.trace("doAttach();")
        val attachResult = DefaultRoomAttachmentResult()
        logger.debug("doAttach(); trying to attach all features: ${contributors.map { it.featureName }.joinWithBrackets}")
        for (feature in contributors) { // CHA-RL1f - attach each feature sequentially
            try {
                logger.debug("doAttach(); attaching feature: ${feature.featureName}")
                feature.channel.attachCoroutine()
                firstAttachesCompleted[feature] = true
                logger.debug("doAttach(); attached feature: ${feature.featureName}")
            } catch (ex: Throwable) { // CHA-RL1h - handle channel attach failure
                logger.error("doAttach(); attach failed for feature: ${feature.featureName}", ex)
                attachResult.throwable = ex
                attachResult.failedFeatureField = feature
                attachResult.errorField = lifeCycleErrorInfo(
                    "failed to attach ${feature.featureName} feature${feature.channel.errorMessage}",
                    feature.attachmentErrorCode,
                )

                // The current feature should be in one of two states, it will be either suspended or failed
                // If it's in suspended, we wind down the other channels and wait for the reattach
                // If it's failed, we can fail the entire room
                when (feature.channel.state) {
                    ChannelState.suspended -> attachResult.statusField = RoomStatus.Suspended
                    ChannelState.failed -> attachResult.statusField = RoomStatus.Failed
                    else -> {
                        attachResult.statusField = RoomStatus.Failed
                        attachResult.errorField = lifeCycleErrorInfo(
                            "unexpected channel state in doAttach ${feature.channel.state}${feature.channel.errorMessage}",
                            ErrorCode.RoomLifecycleError,
                        )
                    }
                }

                // Regardless of whether we're suspended or failed, run-down the other channels
                // The wind-down procedure will take Precedence over any user-driven actions
                logger.warn("doAttach(); transitioning room to ${attachResult.status.name} state")
                statusLifecycle.setStatus(attachResult)
                return attachResult
            }
        }

        // CHA-RL1g, We successfully attached all the channels - set our status to attached, start listening changes in channel status
        logger.debug("doAttach(); attach success for all features: ${contributors.map { it.featureName }.joinWithBrackets}")
        this.statusLifecycle.setStatus(attachResult)
        logger.debug("doAttach(); transitioned room to ATTACHED state")
        this.operationInProgress = false

        // Iterate the pending discontinuity events and trigger them
        for ((contributor, error) in pendingDiscontinuityEvents) {
            logger.warn("doAttach(); emitting discontinuity event for feature: ${contributor.featureName}")
            contributor.discontinuityDetected(error)
        }
        pendingDiscontinuityEvents.clear()
        return attachResult
    }

    /**
     * If we've failed to attach, then we're in the failed state and all that is left to do is to detach all the channels.
     * Spec: CHA-RL1h5, CHA-RL1h6
     * @returns Returns only when all channels are either detached or failed. Doesn't throw exception.
     */
    private suspend fun runDownChannelsOnFailedAttach() {
        logger.trace("runDownChannelsOnFailedAttach();")
        logger.debug("runDownChannelsOnFailedAttach(); attempting to detach all channels")
        // At this point, we have control over the channel lifecycle, so we can hold onto it until things are resolved
        // Keep trying to detach the channels until they're all detached.
        var channelWindDown = kotlin.runCatching { doChannelWindDown() }
        while (channelWindDown.isFailure) { // CHA-RL1h6 - repeat until all channels are detached
            // Something went wrong during the wind down. After a short delay, to give others a turn, we should run down
            // again until we reach a suitable conclusion.
            delay(retryDurationInMs)
            channelWindDown = kotlin.runCatching { doChannelWindDown() }
        }
        logger.debug("runDownChannelsOnFailedAttach(); success, all channels are either detached or failed")
    }

    /**
     * Detach all features except the one exception provided.
     * If the room is in a failed state, then all channels should either reach the failed state or be detached.
     * Spec: CHA-RL1h5
     * @param except The contributor to exclude from the detachment.
     * @returns Success/Failure when all channels are detached or at least one of them fails.
     *
     */
    @Suppress("CognitiveComplexMethod", "ComplexCondition")
    private suspend fun doChannelWindDown(except: ContributesToRoomLifecycle? = null) = coroutineScope {
        logger.trace("doChannelWindDown();")
        contributors.map { contributor: ContributesToRoomLifecycle ->
            async {
                // CHA-RL5a1 - If its the contributor we want to wait for a conclusion on, then we should not detach it
                // Unless we're in a failed state, in which case we should detach it
                if (contributor.channel === except?.channel && statusLifecycle.status !== RoomStatus.Failed) {
                    logger.debug("doChannelWindDown(); ignoring feature: ${contributor.featureName} as per except param")
                    return@async
                }
                // If the room's already in the failed state, or it's releasing, we should not detach a failed channel
                if ((
                        statusLifecycle.status === RoomStatus.Failed ||
                            statusLifecycle.status === RoomStatus.Releasing ||
                            statusLifecycle.status === RoomStatus.Released
                        ) &&
                    contributor.channel.state === ChannelState.failed
                ) {
                    logger.debug("doChannelWindDown(); ignoring feature: ${contributor.featureName} since it's already in failed state")
                    return@async
                }

                try {
                    logger.debug("doChannelWindDown(); detaching feature: ${contributor.featureName}")
                    contributor.channel.detachCoroutine()
                } catch (throwable: Throwable) {
                    logger.warn("doChannelWindDown(); failed to detach feature: ${contributor.featureName}", throwable)
                    // CHA-RL2h2 - If the contributor is in a failed state and we're not ignoring failed states, we should fail the room
                    if (
                        contributor.channel.state === ChannelState.failed &&
                        statusLifecycle.status !== RoomStatus.Failed &&
                        statusLifecycle.status !== RoomStatus.Releasing &&
                        statusLifecycle.status !== RoomStatus.Released
                    ) {
                        val contributorError = lifeCycleErrorInfo(
                            "failed to detach ${contributor.featureName} feature${contributor.channel.errorMessage}",
                            contributor.detachmentErrorCode,
                        )
                        statusLifecycle.setStatus(RoomStatus.Failed, contributorError)
                        throw lifeCycleException(contributorError, throwable)
                    }

                    // CHA-RL2h3 - We throw an error so that the promise rejects
                    throw lifeCycleException(ErrorInfo("detach failure, retry", -1, -1), throwable)
                }
            }
        }.awaitAll()
    }

    /**
     * Detaches the room. If the room is already detached, this is a no-op.
     * If one of the channels fails to detach, the room status will be set to failed.
     * If the room is in the process of detaching, this will wait for the detachment to complete.
     * Spec: CHA-RL2
     */
    @Suppress("ThrowsCount")
    internal suspend fun detach() {
        logger.trace("detach();")
        val deferredDetach = atomicCoroutineScope.async(LifecycleOperationPrecedence.AttachOrDetach.priority) { // CHA-RL2i
            // CHA-RL2a - If we're already detached, this is a no-op
            if (statusLifecycle.status === RoomStatus.Detached) {
                logger.debug("detach(); room is already detached")
                return@async
            }
            // CHA-RL2c - If the room is released, we can't detach
            if (statusLifecycle.status === RoomStatus.Released) {
                logger.error("detach(); detach failed, room is in released state")
                throw lifeCycleException("unable to detach room; room is released", ErrorCode.RoomIsReleased)
            }

            // CHA-RL2b - If the room is releasing, we can't detach
            if (statusLifecycle.status === RoomStatus.Releasing) {
                logger.error("detach(); detach failed, room is in releasing state")
                throw lifeCycleException("unable to detach room; room is releasing", ErrorCode.RoomIsReleasing)
            }

            // CHA-RL2d - If we're in failed, we should not attempt to detach
            if (statusLifecycle.status === RoomStatus.Failed) {
                logger.error("detach(); detach failed, room is in failed state")
                throw lifeCycleException("unable to detach room; room has failed", ErrorCode.RoomInFailedState)
            }

            // CHA-RL2e - We force the room status to be detaching
            operationInProgress = true
            clearAllTransientDetachTimeouts()
            statusLifecycle.setStatus(RoomStatus.Detaching)
            logger.debug("detach(); transitioned room to DETACHING state")

            // CHA-RL2f - We now perform an all-channel wind down.
            // We keep trying until we reach a suitable conclusion.
            return@async doDetach()
        }
        return deferredDetach.await()
    }

    /**
     * Performs detach until every channel gets into either detached or failed state.
     * Spec: CHA-RL2f
     */
    private suspend fun doDetach() {
        logger.trace("doDetach();")
        logger.debug("doDetach(); detaching all features: ${contributors.map { it.featureName }.joinWithBrackets}")
        var channelWindDown = kotlin.runCatching { doChannelWindDown() }
        var firstContributorFailedError: AblyException? = null
        while (channelWindDown.isFailure) { // CHA-RL2h
            val err = channelWindDown.exceptionOrNull()
            if (err is AblyException && err.errorInfo?.code != -1 && firstContributorFailedError == null) {
                logger.error("doDetach(); detach failed for one of the feature, detaching others", err)
                firstContributorFailedError = err // CHA-RL2h1- First failed contributor error is captured
            }
            delay(retryDurationInMs)
            channelWindDown = kotlin.runCatching { doChannelWindDown() }
        }

        // CHA-RL2g - If we aren't in the failed state, then we're detached
        if (statusLifecycle.status !== RoomStatus.Failed) {
            logger.debug("doDetach(); successfully detached all features: ${contributors.map { it.featureName }.joinWithBrackets}")
            statusLifecycle.setStatus(RoomStatus.Detached)
            logger.debug("doDetach(); transitioned room to DETACHED state")
            return
        }

        // CHA-RL2h1 - If we're in the failed state, then we need to throw the error
        throw firstContributorFailedError ?: lifeCycleException("unknown error in doDetach", ErrorCode.RoomLifecycleError)
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
        logger.trace("release();")
        val deferredRelease = atomicCoroutineScope.async(LifecycleOperationPrecedence.Release.priority) { // CHA-RL3k
            // CHA-RL3a - If we're already released, this is a no-op
            if (statusLifecycle.status === RoomStatus.Released) {
                logger.debug("release(); room is already released")
                return@async
            }

            // CHA-RL3b, CHA-RL3j - If we're already detached or initialized, then we can transition to released immediately
            if (statusLifecycle.status === RoomStatus.Detached ||
                statusLifecycle.status === RoomStatus.Initialized
            ) {
                logger.debug("release(); room is in ${statusLifecycle.status}, so transitioning to released state")
                statusLifecycle.setStatus(RoomStatus.Released)
                return@async
            }
            // CHA-RL3l - We force the room status to be releasing.
            // Any transient disconnect timeouts shall be cleared.
            clearAllTransientDetachTimeouts()
            operationInProgress = true
            statusLifecycle.setStatus(RoomStatus.Releasing)
            logger.debug("release(); transitioned room to RELEASING state")

            // CHA-RL3f - Do the release until it completes
            return@async releaseChannels()
        }
        deferredRelease.await()
    }

    /**
     *  Releases the room by detaching all channels. If the release operation fails, we wait
     *  a short period and then try again.
     *  Spec: CHA-RL3f, CHA-RL3d
     */
    private suspend fun releaseChannels() {
        logger.trace("releaseChannels();")
        logger.debug("releaseChannels(); releasing all features: ${contributors.map { it.featureName }.joinWithBrackets}")
        var contributorsReleased = kotlin.runCatching { doRelease() }
        while (contributorsReleased.isFailure) {
            // Wait a short period and then try again
            delay(retryDurationInMs)
            contributorsReleased = kotlin.runCatching { doRelease() }
        }
        logger.debug("releaseChannels(); success, released all features: ${contributors.map { it.featureName }.joinWithBrackets}")
    }

    /**
     * Performs the release operation. This will detach all channels in the room that aren't
     * already detached or in the failed state.
     * Spec: CHA-RL3d, CHA-RL3g
     */
    @Suppress("RethrowCaughtException")
    private suspend fun doRelease() = coroutineScope {
        logger.trace("doRelease();")
        contributors.map { contributor: ContributesToRoomLifecycle ->
            async {
                // CHA-RL3e - Failed channels, we can ignore
                if (contributor.channel.state == ChannelState.failed) {
                    logger.debug("doRelease(); ignoring feature: ${contributor.featureName} since it's in failed state")
                    return@async
                }
                // Detached channels, we can ignore
                if (contributor.channel.state == ChannelState.detached) {
                    logger.debug("doRelease(); ignoring feature: ${contributor.featureName} since it's already detached")
                    return@async
                }
                try {
                    logger.debug("doRelease(); detaching feature: ${contributor.featureName}")
                    contributor.channel.detachCoroutine()
                    logger.debug("doRelease(); successfully detached feature: ${contributor.featureName}")
                } catch (ex: Throwable) {
                    logger.warn("doRelease(); failed to detach feature: ${contributor.featureName}", ex)
                    throw ex
                }
            }
        }.awaitAll()

        // CHA-RL3h - underlying Realtime Channels are released from the core SDK prevent leakage
        logger.debug("doRelease(); releasing underlying channels from core SDK to prevent leakage")
        contributors.forEach {
            it.release()
        }
        logger.debug("doRelease(); underlying channels released from core SDK")
        statusLifecycle.setStatus(RoomStatus.Released) // CHA-RL3g
        logger.debug("doRelease(); transitioned room to RELEASED state")
    }
}
