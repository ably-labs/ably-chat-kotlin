package com.ably.chat

import io.ably.lib.types.MessageAction

/**
 * All chat message events.
 */
enum class MessageEventType(val eventName: String) {
    /** Fires when a new chat message is received. */
    Created("message.created"),

    /** Fires when a chat message is updated. */
    Updated("message.updated"),

    /** Fires when a chat message is deleted. */
    Deleted("message.deleted"),
}

/**
 * Realtime chat message names.
 */
object PubSubMessageNames {
    /** Represents a regular chat message. */
    const val ChatMessage = "chat.message"
}

val messageActionNameToAction = mapOf(
    /** Represents a message with no action set. */
    "message.unset" to MessageAction.MESSAGE_UNSET,

    /** Action applied to a new message. */
    "message.create" to MessageAction.MESSAGE_CREATE,

    /** Action applied to an updated message. */
    "message.update" to MessageAction.MESSAGE_UPDATE,

    /** Action applied to a deleted message. */
    "message.delete" to MessageAction.MESSAGE_DELETE,

    /** Action applied to a new annotation. */
    "annotation.create" to MessageAction.ANNOTATION_CREATE,

    /** Action applied to a deleted annotation. */
    "annotation.delete" to MessageAction.ANNOTATION_DELETE,

    /** Action applied to a meta occupancy message. */
    "meta.occupancy" to MessageAction.META_OCCUPANCY,
)

/**
 * Enum representing presence events.
 */
enum class PresenceEventType(val eventName: String) {
    /**
     * Event triggered when a user enters.
     */
    Enter("enter"),

    /**
     * Event triggered when a user leaves.
     */
    Leave("leave"),

    /**
     * Event triggered when a user updates their presence data.
     */
    Update("update"),

    /**
     * Event triggered when a user initially subscribes to presence.
     */
    Present("present"),
}

enum class TypingEventType(val eventName: String) {
    /** The set of currently typing users has changed. */
    Changed("typing.changed"),
}

/**
 * Room reaction events. This is used for the realtime system since room reactions
 * have only one event: "roomReaction".
 */
enum class RoomReactionEventType(val eventName: String) {
    /**
     * Event triggered when a room reaction was received.
     */
    Reaction("roomReaction"),
}
