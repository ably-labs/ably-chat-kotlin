package com.ably.chat.room

import com.ably.chat.AndroidLogger
import com.ably.chat.AtomicCoroutineScope
import com.ably.chat.ChatApi
import com.ably.chat.ContributesToRoomLifecycle
import com.ably.chat.DefaultMessages
import com.ably.chat.DefaultOccupancy
import com.ably.chat.DefaultPresence
import com.ably.chat.DefaultRoom
import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.DefaultRoomReactions
import com.ably.chat.DefaultTyping
import com.ably.chat.LifecycleOperationPrecedence
import com.ably.chat.Logger
import com.ably.chat.RealtimeClient
import com.ably.chat.Room
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.RoomOptions
import com.ably.chat.RoomStatusEventEmitter
import com.ably.chat.Rooms
import com.ably.chat.getPrivateField
import com.ably.chat.invokePrivateSuspendMethod
import com.ably.chat.setPrivateField
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.Channel
import io.ably.lib.realtime.ChannelState
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.ErrorInfo
import io.ably.lib.util.EventEmitter
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import io.ably.lib.realtime.Channel as AblyRealtimeChannel

const val DEFAULT_ROOM_ID = "1234"
const val DEFAULT_CLIENT_ID = "clientId"
const val DEFAULT_CHANNEL_NAME = "channel"

fun createMockRealtimeClient(): AblyRealtime {
    val realtimeClient = spyk(AblyRealtime(ClientOptions("id:key").apply { autoConnect = false }), recordPrivateCalls = true)
    val mockChannels = spyk(realtimeClient.channels, recordPrivateCalls = true)
    realtimeClient.setPrivateField("channels", mockChannels)
    return realtimeClient
}

fun AblyRealtime.createMockChannel(channelName: String = DEFAULT_CHANNEL_NAME): Channel =
    spyk(channels.get(channelName), recordPrivateCalls = true)

internal fun createMockChatApi(
    realtimeClient: RealtimeClient = createMockRealtimeClient(),
    clientId: String = DEFAULT_CLIENT_ID,
    logger: Logger = createMockLogger(),
) = spyk(ChatApi(realtimeClient, clientId, logger), recordPrivateCalls = true)

internal fun createMockLogger(): Logger = mockk<AndroidLogger>(relaxed = true)

internal fun createMockRoom(
    roomId: String = DEFAULT_ROOM_ID,
    clientId: String = DEFAULT_CLIENT_ID,
    realtimeClient: RealtimeClient = createMockRealtimeClient(),
    chatApi: ChatApi = mockk<ChatApi>(relaxed = true),
    logger: Logger = createMockLogger(),
): DefaultRoom =
    DefaultRoom(roomId, RoomOptions.default, realtimeClient, chatApi, clientId, logger)

// Rooms mocks
val Rooms.RoomIdToRoom get() = getPrivateField<MutableMap<String, Room>>("roomIdToRoom")
val Rooms.RoomGetDeferred get() = getPrivateField<MutableMap<String, CompletableDeferred<Unit>>>("roomGetDeferred")
val Rooms.RoomReleaseDeferred get() = getPrivateField<MutableMap<String, CompletableDeferred<Unit>>>("roomReleaseDeferred")

// Room mocks
internal val Room.StatusLifecycle get() = getPrivateField<DefaultRoomLifecycle>("statusLifecycle")
internal val Room.LifecycleManager get() = getPrivateField<RoomLifecycleManager>("lifecycleManager")

// DefaultRoomLifecycle mocks
internal val DefaultRoomLifecycle.InternalEmitter get() = getPrivateField<RoomStatusEventEmitter>("internalEmitter")

// EventEmitter mocks
internal val EventEmitter<*, *>.Listeners get() = getPrivateField<List<Any>>("listeners")
internal val EventEmitter<*, *>.Filters get() = getPrivateField<Map<Any, Any>>("filters")

// RoomLifeCycleManager Mocks
internal fun RoomLifecycleManager.atomicCoroutineScope(): AtomicCoroutineScope = getPrivateField("atomicCoroutineScope")

internal suspend fun RoomLifecycleManager.retry(exceptContributor: ContributesToRoomLifecycle) =
    invokePrivateSuspendMethod<Unit>("doRetry", exceptContributor)

internal suspend fun RoomLifecycleManager.atomicRetry(exceptContributor: ContributesToRoomLifecycle) {
    atomicCoroutineScope().async(LifecycleOperationPrecedence.Internal.priority) {
        retry(exceptContributor)
    }.await()
}

internal fun createRoomFeatureMocks(
    roomId: String = DEFAULT_ROOM_ID,
    clientId: String = DEFAULT_CLIENT_ID,
): List<ContributesToRoomLifecycle> {
    val realtimeClient = createMockRealtimeClient()
    val chatApi = createMockChatApi()
    val logger = createMockLogger()
    val room = createMockRoom(roomId, clientId, realtimeClient, chatApi, logger)

    val messagesContributor = spyk(DefaultMessages(room), recordPrivateCalls = true)
    val presenceContributor = spyk(DefaultPresence(room), recordPrivateCalls = true)
    val occupancyContributor = spyk(DefaultOccupancy(room), recordPrivateCalls = true)
    val typingContributor = spyk(DefaultTyping(room), recordPrivateCalls = true)
    val reactionsContributor = spyk(DefaultRoomReactions(room), recordPrivateCalls = true)

    // CHA-RC2e - Add contributors/features as per the order of precedence
    return listOf(messagesContributor, presenceContributor, typingContributor, reactionsContributor, occupancyContributor)
}

fun AblyRealtimeChannel.setState(state: ChannelState, errorInfo: ErrorInfo? = null) {
    this.state = state
    this.reason = errorInfo
}
