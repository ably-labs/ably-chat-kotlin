package com.ably.chat

import io.ably.lib.types.AblyException
import io.ably.lib.types.ErrorInfo
import io.ably.lib.util.Log.LogHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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
        subscribeToJobEvents()
        // TODO - [CHA-RL4] set up room monitoring here
    }

    enum class OperationType {
        Internal,
        Attach,
        Detach,
        Release,
    }

    class RoomOperation<T>(
        val operationType: OperationType,
        val coroutineBlock: (suspend CoroutineScope.() -> T),
        val deferredResult: CompletableDeferred<T>,
    )

    private val internalEventChannel = Channel<RoomOperation<Any>>(Channel.UNLIMITED)
    private val releaseEventChannel = Channel<RoomOperation<Any>>(Channel.UNLIMITED)
    private val attachDetachEventChannel = Channel<RoomOperation<Any>>(Channel.UNLIMITED)

    private suspend fun <T : Any>dispatchRoomOperation(operationType: OperationType, coroutineBlock: suspend (CoroutineScope) -> T):
        CompletableDeferred<T> {
        val deferred = CompletableDeferred<Any>()
        val roomOp = RoomOperation(operationType, coroutineBlock, deferred)
        when (operationType) {
            OperationType.Attach -> attachDetachEventChannel.send(roomOp)
            OperationType.Detach -> TODO()
            OperationType.Release -> TODO()
            OperationType.Internal -> TODO()
        }
        @Suppress("UNCHECKED_CAST")
        return deferred as CompletableDeferred<T>
    }

    internal suspend fun attach() {
        return dispatchRoomOperation(OperationType.Attach) {
            when (_status.current) {
                RoomLifecycle.Attached -> return@dispatchRoomOperation
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
        }.await()
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

    /**
     * Clears all transient detach timeouts - used when some event supersedes the transient detach such
     * as a failed channel or suspension.
     */
    private fun _clearAllTransientDetachTimeouts() {
        TODO("need to clear all transient detach timeouts")
    }

    /**
     * Starts a while loop that runs indefinitely, even after room release.
     * This is to make sure attach/detach throws exceptions even when room is released.
     * Check CHA-RL1b, CHA-RL1c, CHA-RL2b, CHA-RL2c, CHA-RL2d.
     * This also means all available channels can't be closed since we are waiting for processing events.
     * To avoid this, we have to add extra locking mechanism which should be ideally avoided.
     */
    private fun subscribeToJobEvents() {
        /**
         * Can't use withContext(coroutineScope), since this is not a suspending function.
         */
        sequentialCoroutineScope.launch {
            while (true) {
                /**
                 * The order of precedence for lifecycle operations using select clause allows
                 * us to ensure that internal operations take precedence over user-driven operations.
                 * This is as per [LifecycleOperationPrecedence], although that enum won't be used.
                 * CHA-RL7
                 */
                select {
                    internalEventChannel.onReceive {
                    }
                    releaseEventChannel.onReceive {
                    }
                    attachDetachEventChannel.onReceive {
                        val result = async(block = it.coroutineBlock).await()
                        it.deferredResult.complete(result)
                    }
                }
            }
        }
    }
}
