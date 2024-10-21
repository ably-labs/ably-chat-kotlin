package com.ably.chat

/**
 * Error codes for the Chat SDK.
 */
enum class ErrorCodes(val errorCode: Int) {

    /**
     * The messages feature failed to attach.
     */
    MessagesAttachmentFailed(102_001),

    /**
     * The presence feature failed to attach.
     */
    PresenceAttachmentFailed(102_002),

    /**
     * The reactions feature failed to attach.
     */
    ReactionsAttachmentFailed(102_003),

    /**
     * The occupancy feature failed to attach.
     */
    OccupancyAttachmentFailed(102_004),

    /**
     * The typing feature failed to attach.
     */
    TypingAttachmentFailed(102_005),
    // 102_006 - 102_049 reserved for future use for attachment errors

    /**
     * The messages feature failed to detach.
     */
    MessagesDetachmentFailed(102_050),

    /**
     * The presence feature failed to detach.
     */
    PresenceDetachmentFailed(102_051),

    /**
     * The reactions feature failed to detach.
     */
    ReactionsDetachmentFailed(102_052),

    /**
     * The occupancy feature failed to detach.
     */
    OccupancyDetachmentFailed(102_053),

    /**
     * The typing feature failed to detach.
     */
    TypingDetachmentFailed(102_054),
    // 102_055 - 102_099 reserved for future use for detachment errors

    /**
     * The room has experienced a discontinuity.
     */
    RoomDiscontinuity(102_100),

    // Unable to perform operation;

    /**
     * Cannot perform operation because the room is in a failed state.
     */
    RoomInFailedState(102_101),

    /**
     * Cannot perform operation because the room is in a releasing state.
     */
    RoomIsReleasing(102_102),

    /**
     * Cannot perform operation because the room is in a released state.
     */
    RoomIsReleased(102_103),

    /**
     * Cannot perform operation because the previous operation failed.
     */
    PreviousOperationFailed(102_104),

    /**
     * An unknown error has happened in the room lifecycle.
     */
    RoomLifecycleError(102_105),

    /**
     * The request cannot be understood
     */
    BadRequest(40_000),

    /**
     * Invalid request body
     */
    InvalidRequestBody(40_001),

    /**
     * Internal error
     */
    InternalError(50_000),
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
