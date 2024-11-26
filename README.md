# Ably Chat SDK for Android

<p style="text-align: left">
    <img src="https://img.shields.io/badge/development_status-Private_Beta-ab7df8" alt="Development status"   />
    <img src="https://badgen.net/github/license/3scale/saas-operator" alt="License" />
</p>

Ably Chat is a set of purpose-built APIs for a host of chat features enabling you to create 1:1, 1:Many, Many:1 and Many:Many chat rooms for
any scale. It is designed to meet a wide range of chat use cases, such as livestreams, in-game communication, customer support, or social
interactions in SaaS products. Built on [Ably's](https://ably.com/) core service, it abstracts complex details to enable efficient chat
architectures.

> [!IMPORTANT]
> This SDK is currently under development. If you are interested in being an early adopter and providing feedback then you
> can [sign up to the private beta](https://forms.gle/vB2kXhCXrTQpzHLu5) and are welcome
> to [provide us with feedback](https://forms.gle/mBw9M53NYuCBLFpMA). Coming soon: chat moderation, editing and deleting messages.

Get started using the [📚 documentation](https://ably.com/docs/products/chat).

![Ably Chat Header](/images/ably-chat-github-header.png)

## Supported Platforms

This SDK works on Android 7.0+ (API level 24+) and Java 8+.

## Supported chat features

This project is under development so we will be incrementally adding new features. At this stage, you'll find APIs for the following chat
features:

- Chat rooms for 1:1, 1:many, many:1 and many:many participation.
- Sending and receiving chat messages.
- Online status aka presence of chat participants.
- Chat room occupancy, i.e total number of connections and presence members.
- Typing indicators
- Room-level reactions (ephemeral at this stage)

If there are other features you'd like us to prioritize, please [let us know](https://forms.gle/mBw9M53NYuCBLFpMA).

## Usage

You will need the following prerequisites:

- An Ably account
    - You can [sign up](https://ably.com/signup) to the generous free tier.
- An Ably API key
    - Use the default or create a new API key in an app within
      your [Ably account dashboard](https://ably.com/dashboard).
    - Make sure your API key has the
      following [capabilities](https://ably.com/docs/auth/capabilities): `publish`, `subscribe`, `presence`, `history` and
      `channel-metadata`.

[//]: # (## Installation)

[//]: # ()
[//]: # (The Ably Chat SDK is available on the Maven Central Repository. To include the dependency in your project, add the following to your `build.gradle` file:)

[//]: # ()
[//]: # (For Groovy:)

[//]: # ()
[//]: # (```groovy)

[//]: # (implementation 'com.ably.chat:android')

[//]: # (```)

[//]: # ()
[//]: # (For Kotlin Script &#40;`build.gradle.kts`&#41;:)

[//]: # ()
[//]: # (```kotlin)

[//]: # (implementation&#40;"com.ably.chat:android"&#41;)

[//]: # (```)

[//]: # ()
[//]: # (### Dependency on ably-android)

[//]: # ()
[//]: # (Certain functionalities are powered by the [ably-android]&#40;https://github.com/ably/ably-java&#41; library. )

[//]: # (The `ably-android` library is included as an api dependency within the Chat SDK, so there is no need to manually add it to your project.)

## Versioning

The Ably client library follows [Semantic Versioning](http://semver.org/). See https://github.com/ably/ably-chat-kotlin/tags for a list of
tagged releases.

## Instantiation and authentication

To instantiate the Chat SDK, create an [Ably client](https://ably.com/docs/getting-started/setup) and pass it into the
Chat constructor:

```kotlin
import com.ably.chat.ChatClient
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.types.ClientOptions

val realtimeClient = AblyRealtime(
    ClientOptions().apply {
        key = "<api-key>"
        clientId = "<client-id>"
    },
)

val chatClient = ChatClient(realtimeClient)
```

You can use [basic authentication](https://ably.com/docs/auth/basic) i.e. the API Key directly for testing purposes,
however it is strongly recommended that you use [token authentication](https://ably.com/docs/auth/token) in production
environments.

To use Chat you must also set a [`clientId`](https://ably.com/docs/auth/identified-clients) so that clients are
identifiable. If you are prototyping, you can use `java.util.UUID` to generate an ID.

## Connections

The Chat SDK uses a single connection to Ably, which is exposed via the `ChatClient#connection` property. You can use this
property to observe the connection state and take action accordingly.

### Current connection status

You can view the current connection status at any time:

```kotlin
val connectionStatus = chat.connection.status
val connectionError = chat.connection.error
```

### Subscribing to connection status changes

You can subscribe to connection status changes by registering a listener, like so:

```kotlin
val subscription = chat.connection.onStatusChange { statusChange: ConnectionStatusChange ->
    println(statusChange.toString())
}
```

To stop listening to changes, `unsubscribe` method on returned subscription instance:

```kotlin
subscription.unsubscribe()
```

## Chat rooms

### Creating or retrieving a chat room

You can create or retrieve a chat room with name `"basketball-stream"` this way:

```kotlin
val room = chat.rooms.get("basketball-stream", RoomOptions(reactions = RoomReactionsOptions()))
```

The second argument to `rooms.get` is a `RoomOptions` argument, which tells the Chat SDK what features you would like your room to use and
how they should be configured.

For example, you can set the timeout between keystrokes for typing events as part of the room options. Sensible defaults for each of the
features are provided for your convenience:

- A typing timeout (time of inactivity before typing stops) of 5 seconds.
- Entry into, and subscription to, presence.

The defaults options for each feature may be
viewed [here](https://github.com/ably/ably-chat-js/blob/main/chat-android/src/main/java/com/ably/chat/RoomOptions.kt).

In order to use the same room but with different options, you must first `release` the room before requesting an instance with the changed
options (see below for more information on releasing rooms).

Note that:

- If a `release` call is currently in progress for the room (see below), then a call to `get` will wait for that to resolve before resolving
  itself.
- If a `get` call is currently in progress for the room and `release` is called, the `get` call will reject.

### Attaching to a room

To start receiving events on a room, it must first be attached. This can be done using the `attach` method.

```kotlin
// Add a listener so it's ready at attach time (see below for more information on listeners)
room.messages.subscribe { message: Message ->
    println(message.toString())
}

room.attach()
```

### Detaching from a room

To stop receiving events on a room, it must be detached, which can be achieved by using the `detach` method.

```kotlin
room.detach()
```

Note: This does not remove any event listeners you have registered and they will begin to receive events again in the
event that the room is re-attached.

### Releasing a room

Depending on your application, you may have multiple rooms that come and go over time (e.g. if you are running 1:1 support chat). When you
are completely finished with a room, you may `release` it which allows the underlying resources to be collected.

```kotlin
rooms.release("basketball-stream")
```

Once `release` is called, the room will become unusable and you will need to get a new instance using `rooms.get` should you wish to
re-start the room.

Note that releasing a room may be optional for many applications.

### Monitoring room status

Monitoring the status of the room is key to a number of common chat features. For example, you might want to display a warning when the room
has become detached.

### Current status of a room

To get the current status, you can use the `status` property:

```kotlin
val roomStatus = room.status
val roomError = room.error
```

### Listening to room status updates

You can also subscribe to changes in the room status and be notified whenever they happen by registering a listener:

```kotlin
val subscription = room.onStatusChange { statusChange: RoomStatusChange ->
    println(statusChange.toString())
}
```

To stop listening to changes, `unsubscribe` method on returned subscription instance:

```kotlin
subscription.unsubscribe()
```

## Handling discontinuity

There may be instances where the connection to Ably is lost for a period of time, for example, when the user enters a tunnel. In many
circumstances, the connection will recover and operation
will continue with no discontinuity of messages. However, during extended periods of disconnection, continuity cannot be guaranteed and
you'll need to take steps to recover
messages you might have missed.

Each feature of the Chat SDK provides an `onDiscontinuity` handler. Here you can register a listener that will be notified whenever a
discontinuity in that feature has been observed.

Taking messages as an example, you can listen for discontinuities like so:

```kotlin
val subscription = room.messages.onDiscontinuity { reason: ErrorInfo? ->
    // Recover from the discontinuity
}
```

To stop listening to discontinuities, `unsubscribe` method on returned subscription instance.

## Chat messages

### Sending messages

To send a message, simply call `send` on the `room.messages` property, with the message you want to send.

```kotlin
val message = room.messages.send(SendMessageParams(text = "text"))
```

### Unsubscribing from incoming messages

When you're done with the listener, call `unsubscribe` to remove that listeners subscription and prevent it from receiving
any more events.

```kotlin
val subscription = room.messages.subscribe { message: Message ->
    println(message.toString())
}
// Time passes...
subscription.unsubscribe()
```

### Retrieving message history

The messages object also exposes the `get` method which can be used to request historical messages in the chat room according
to the given criteria. It returns a paginated response that can be used to request more messages.

```kotlin
var historicalMessages = room.messages.get(QueryParams(orderBy = NewestFirst, limit = 50))
println(historicalMessages.items.toString())

while (historicalMessages.hasNext()) {
    historicalMessages = historicalMessages.next()
    println(historicalMessages.items.toString())
}

println("End of messages")
```

### Retrieving message history for a subscribed listener

In addition to being able to unsubscribe from messages, the return value from `messages.subscribe` also includes the `getPreviousMessages`
method. It can be used to request
historical messages in the chat room that were sent up to the point a that particular listener was subscribed. It returns a
paginated response that can be used to request for more messages.

```kotlin
val subscription = room.messages.subscribe {
    println("New message received")
}

var historicalMessages = subscription.getPreviousMessages(limit = 50)
println(historicalMessages.items.toString())

while (historicalMessages.hasNext()) {
    historicalMessages = historicalMessages.next()
    println(historicalMessages.items.toString())
}

println("End of messages")
```

## Online status

### Retrieving online members

You can get the complete list of currently online or present members, their state and data, by calling the `presence#get` method.

```kotlin
// Retrieve the entire list of present members
val presentMembers = room.presence.get()

// You can supply a clientId to retrieve the presence of a specific member with the given clientId
val presentMember = room.presence.get(clientId = "client-id")

// You can call this to get a simple boolean value of whether a member is present or not
val isPresent = room.presence.isUserPresent("client-id")
```

Calls to `presence#get()` will return a list of the presence messages, where each message contains the most recent
data for a member.

### Entering the presence set

To appear online for other users, you can enter the presence set of a chat room. While entering presence, you can provide optional data that
will be associated with the presence message.

```kotlin
room.presence.enter(
    JsonObject().apply {
        addProperty("status", "online")
    },
)
```

### Updating the presence data

Updates allow you to make changes to the custom data associated with a present user. Common use-cases include updating the users'
status or profile picture.

```kotlin
room.presence.update(
    JsonObject().apply {
        addProperty("status", "busy")
    },
)
```

### Leaving the presence set

Ably automatically triggers a presence leave if a client goes offline. But you can also manually leave the presence set as a result of a UI
action. While leaving presence, you can provide optional data that will be associated with the presence message.

```kotlin
room.presence.leave(
    JsonObject().apply {
        addProperty("status", "Be back later!")
    },
)
```

### Subscribing to presence updates

You can provide a single listener, if so, the listener will be subscribed to receive all presence event types.

```kotlin
val subscription = room.presence.subscribe { event: PresenceEvent ->
    when (event.action) {
        Action.enter -> println("${event.clientId} entered with data: ${event.data}")
        Action.leave -> println("${event.clientId} left")
        Action.update -> println("${event.clientId} updated with data: ${event.data}")
    }
}
```

### Unsubscribing from presence updates

To unsubscribe a specific listener from presence events, you can call the `unsubscribe` method provided in the subscription object returned
by the `subscribe` call.

```kotlin
val subscription = room.presence.subscribe { event: PresenceEvent ->
    // Handle events
}

// Unsubscribe
subscription.unsubscribe()
```

## Typing indicators

Typing events allow you to inform others that a client is typing and also subscribe to others' typing status.

### Retrieving the set of current typers

You can get the complete set of the current typing `clientId`s, by calling the `typing.get` method.

```kotlin
// Retrieve the entire list of currently typing clients
val currentlyTypingClientIds = room.typing.get()
```

### Start typing

To inform other users that you are typing, you can call the start method. This will begin a timer that will automatically stop typing after
a set amount of time.

```kotlin
room.typing.start()
```

Repeated calls to start will reset the timer, so the clients typing status will remain active.

```kotlin
room.typing.start()
// Some short delay - still typing
room.typing.start()
// Some short delay - still typing
room.typing.start()
// Some long delay - timer expires, stopped typing event emitted and listeners are notified
```

### Stop typing

You can immediately stop typing without waiting for the timer to expire.

```kotlin
room.typing.start()
// Some short delay - timer not yet expired
room.typing.stop()
// Timer cleared and stopped typing event emitted and listeners are notified
```

### Subscribing to typing updates

To subscribe to typing events, provide a listener to the `subscribe` method.

```kotlin
val subscription = room.typing.subscribe { event: TypingEvent ->
    println("currently typing: ${event.currentlyTyping}")
}
```

### Unsubscribing from typing updates

To unsubscribe the listener, you can call the `unsubscribe` method on the subscription object returned by the `subscribe` call:

```kotlin
val subscription = room.typing.subscribe { event: TypingEvent ->
    println("currently typing: ${event.currentlyTyping}")
}

// Time passes
subscription.unsubscribe()
```

## Occupancy of a chat room

Occupancy tells you how many users are connected to the chat room.

### Subscribing to occupancy updates

To subscribe to occupancy updates, subscribe a listener to the chat rooms `occupancy` member:

```kotlin
val subscription = room.occupancy.subscribe { event: OccupancyEvent ->
    println(event.toString())
}
```

### Unsubscribing from occupancy updates

To unsubscribe the listener, you can call the `unsubscribe` method on the subscription object returned by the `subscribe` call:

```kotlin
val subscription = room.occupancy.subscribe { event: OccupancyEvent ->
    println(event.toString())
}

// Time passes...
subscription.unsubscribe()
```

Occupancy updates are delivered in near-real-time, with updates in quick succession batched together for performance.

### Retrieving the occupancy of a chat room

You can request the current occupancy of a chat room using the `occupancy.get` method:

```kotlin
val occupancy = room.occupancy.get()
```

## Room-level reactions

You can subscribe to and send ephemeral room-level reactions by using the `room.reactions` objects.

To send room-level reactions, you must be [attached](#attaching-to-a-room) to the room.

### Sending a reaction

To send a reaction such as `"like"`:

```kotlin
room.reactions.send(SendReactionParams(type = "like"))
```

You can also add any metadata and headers to reactions:

```kotlin
room.reactions.send(SendReactionParams(
    type ="like",
    metadata = mapOf("effect" to "fireworks"),
    headers = mapOf("streamId" to "basketball-stream"),
))
```

### Subscribing to room reactions

Subscribe to receive room-level reactions:

```kotlin
val subscription = room.reactions.subscribe { reaction: ReactionEvent ->
    println("received a ${reaction.type} with metadata ${reaction.metadata}")
}
```

### Unsubscribing from room reactions

To unsubscribe the listener, you can call the `unsubscribe` method on the subscription object returned by the `subscribe` call:

```kotlin
val subscription = room.reactions.subscribe { reaction: ReactionEvent ->
    println("received a ${reaction.type} with metadata ${reaction.metadata}")
}

// Time passes...
subscription.unsubscribe()
```

## In-depth

### Channels Behind Chat Features

It might be useful to know that each feature is backed by an underlying Pub/Sub channel. You can use this information to enable
interoperability with other platforms by subscribing to the channels directly using
the [Ably Pub/Sub SDKs](https://ably.com/docs/products/channels) for those platforms.

The channel for each feature can be obtained via the `channel` property
on that feature.

```kotlin
val messagesChannel = room.messages.channel
```

**Warning**: You should not attempt to change the state of a channel directly. Doing so may cause unintended side-effects in the Chat SDK.

### Channels Used

For a given chat room, the channels used for features are as follows:

| Feature   | Channel                              |
|-----------|--------------------------------------|
| Messages  | `<roomId>::$chat::$chatMessages`     |
| Presence  | `<roomId>::$chat::$chatMessages`     |
| Occupancy | `<roomId>::$chat::$chatMessages`     |
| Reactions | `<roomId>::$chat::$reactions`        |
| Typing    | `<roomId>::$chat::$typingIndicators` |

---

## Contributing

For guidance on how to contribute to this project, see the [contributing guidelines](CONTRIBUTING.md).

## Support, feedback and troubleshooting

Please visit http://support.ably.com/ for access to our knowledge base and to ask for any assistance. You can also view
the [community reported Github issues](https://github.com/ably/ably-chat-kotlin/issues) or raise one yourself.

To see what has changed in recent versions, see the [changelog](CHANGELOG.md).

## Further reading

- See a [simple chat example](/demo/) in this repo.
- [Sign up](https://forms.gle/gRZa51erqNp1mSxVA) to the private beta and get started.
- [Share feedback or request](https://forms.gle/mBw9M53NYuCBLFpMA) a new feature.
