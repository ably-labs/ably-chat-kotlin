package com.ably.chat

import io.ably.lib.realtime.ChannelBase
import io.ably.lib.types.ErrorInfo
import kotlinx.coroutines.Deferred

/**
 * An interface for features that contribute to the room status.
 */
interface ContributesToRoomLifecycle : EmitsDiscontinuities {
    /**
     * Gets the channel on which the feature operates. This promise is never
     * rejected except in the case where room initialization is canceled.
     */
    val channel: Deferred<ChannelBase>

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
    val channel: ChannelBase
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
