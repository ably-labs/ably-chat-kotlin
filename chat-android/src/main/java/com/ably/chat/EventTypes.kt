package com.ably.chat

/**
 * All chat message events.
 */
enum class MessageEventType(val eventName: String) {
    /** Fires when a new chat message is received. */
    Created("message.created"),
}

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
