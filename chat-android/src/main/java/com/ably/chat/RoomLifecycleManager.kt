package com.ably.chat

import io.ably.lib.realtime.ChannelBase

/**
 * An interface for features that contribute to the room status.
 */
interface ContributesToRoomLifecycle: Subscription {
    /**
     * Gets the channel on which the feature operates. This promise is never
     * rejected except in the case where room initialization is canceled.
     */
    val channel: ChannelBase

    /**
     * Gets the ErrorInfo code that should be used when the feature fails to attach.
     * @returns The error that should be used when the feature fails to attach.
     */
     val attachmentErrorCode: ErrorCodes;

    /**
     * Gets the ErrorInfo code that should be used when the feature fails to detach.
     * @returns The error that should be used when the feature fails to detach.
     */
    val detachmentErrorCode: ErrorCodes;
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

class RoomLifecycleManager {

}
