package com.ably.chat

/**
 * Error codes for the Chat SDK.
 */
object ErrorCodes {
    /**
     * The messages feature failed to attach.
     */
    const val MessagesAttachmentFailed = 102_001

    /**
     * The presence feature failed to attach.
     */
    const val PresenceAttachmentFailed = 102_002

    /**
     * The reactions feature failed to attach.
     */
    const val ReactionsAttachmentFailed = 102_003

    /**
     * The occupancy feature failed to attach.
     */
    const val OccupancyAttachmentFailed = 102_004

    /**
     * The typing feature failed to attach.
     */
    const val TypingAttachmentFailed = 102_005
    // 102006 - 102049 reserved for future use for attachment errors

    /**
     * The messages feature failed to detach.
     */
    const val MessagesDetachmentFailed = 102_050

    /**
     * The presence feature failed to detach.
     */
    const val PresenceDetachmentFailed = 102_051

    /**
     * The reactions feature failed to detach.
     */
    const val ReactionsDetachmentFailed = 102_052

    /**
     * The occupancy feature failed to detach.
     */
    const val OccupancyDetachmentFailed = 102_053

    /**
     * The typing feature failed to detach.
     */
    const val TypingDetachmentFailed = 102_054
    // 102055 - 102099 reserved for future use for detachment errors

    /**
     * The room has experienced a discontinuity.
     */
    const val RoomDiscontinuity = 102_100

    // Unable to perform operation;

    /**
     * Cannot perform operation because the room is in a failed state.
     */
    const val RoomInFailedState = 102_101

    /**
     * Cannot perform operation because the room is in a releasing state.
     */
    const val RoomIsReleasing = 102_102

    /**
     * Cannot perform operation because the room is in a released state.
     */
    const val RoomIsReleased = 102_103

    /**
     * Cannot perform operation because the previous operation failed.
     */
    const val PreviousOperationFailed = 102_104

    /**
     * An unknown error has happened in the room lifecycle.
     */
    const val RoomLifecycleError = 102_105

    /**
     * The request cannot be understood
     */
    const val BadRequest = 40_000
}

/**
 * Http Status Codes
 */
object HttpStatusCodes {

    const val BadRequest = 400

    const val Unauthorized = 401

    const val InternalServerError = 500

    const val NotImplemented = 501

    const val ServiceUnavailable = 502

    const val GatewayTimeout = 503

    const val Timeout = 504
}
