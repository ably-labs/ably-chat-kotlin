package com.ably.utils

import com.ably.chat.AtomicCoroutineScope
import com.ably.chat.ChatApi
import com.ably.chat.ContributesToRoomLifecycle
import com.ably.chat.DefaultMessages
import com.ably.chat.DefaultOccupancy
import com.ably.chat.DefaultPresence
import com.ably.chat.DefaultRoomReactions
import com.ably.chat.DefaultTyping
import com.ably.chat.LifecycleOperationPrecedence
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.getPrivateField
import com.ably.chat.invokePrivateSuspendMethod
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.ChannelState
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.ErrorInfo
import io.mockk.mockk
import io.mockk.spyk
import io.ably.lib.realtime.Channel as AblyRealtimeChannel

fun RoomLifecycleManager.atomicCoroutineScope(): AtomicCoroutineScope = getPrivateField("atomicCoroutineScope")

suspend fun RoomLifecycleManager.retry(exceptContributor: ContributesToRoomLifecycle) =
    invokePrivateSuspendMethod<Unit>("doRetry", exceptContributor)

suspend fun RoomLifecycleManager.atomicRetry(exceptContributor: ContributesToRoomLifecycle) {
    atomicCoroutineScope().async(LifecycleOperationPrecedence.Internal.priority) {
        retry(exceptContributor)
    }.await()
}

fun AblyRealtimeChannel.setState(state: ChannelState, errorInfo: ErrorInfo? = null) {
    this.state = state
    this.reason = errorInfo
}

fun createRoomFeatureMocks(roomId: String = "1234"): List<ContributesToRoomLifecycle> {
    val realtimeClient = spyk(AblyRealtime(ClientOptions("id:key").apply { autoConnect = false }))
    val chatApi = mockk<ChatApi>(relaxed = true)

    val messagesContributor = spyk(DefaultMessages(roomId, realtimeClient.channels, chatApi), recordPrivateCalls = true)
    val presenceContributor = spyk(
        DefaultPresence("client1", messagesContributor.channel, messagesContributor.channel.presence),
        recordPrivateCalls = true,
    )
    val occupancyContributor = spyk(DefaultOccupancy(messagesContributor), recordPrivateCalls = true)
    val typingContributor = spyk(DefaultTyping(roomId, realtimeClient), recordPrivateCalls = true)
    val reactionsContributor = spyk(DefaultRoomReactions(roomId, "client1", realtimeClient.channels), recordPrivateCalls = true)
    return listOf(messagesContributor, presenceContributor, occupancyContributor, typingContributor, reactionsContributor)
}
