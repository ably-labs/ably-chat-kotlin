package com.ably.utils

import com.ably.chat.AtomicCoroutineScope
import com.ably.chat.ChatApi
import com.ably.chat.DefaultMessages
import com.ably.chat.DefaultOccupancy
import com.ably.chat.DefaultPresence
import com.ably.chat.DefaultRoomReactions
import com.ably.chat.DefaultTyping
import com.ably.chat.ResolvedContributor
import com.ably.chat.RoomLifecycleManager
import com.ably.chat.getPrivateField
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.realtime.ChannelState
import io.ably.lib.types.ClientOptions
import io.ably.lib.types.ErrorInfo
import io.mockk.mockk
import io.mockk.spyk
import io.ably.lib.realtime.Channel as AblyRealtimeChannel

fun RoomLifecycleManager.atomicCoroutineScope(): AtomicCoroutineScope = getPrivateField("atomicCoroutineScope")

fun AblyRealtimeChannel.setState(state: ChannelState, errorInfo: ErrorInfo? = null) {
    this.state = state
    this.reason = errorInfo
}

fun createRoomFeatureMocks(roomId: String = "1234"): List<ResolvedContributor> {
    val realtimeClient = spyk(AblyRealtime(ClientOptions("id:key").apply { autoConnect = false }))
    val chatApi = mockk<ChatApi>(relaxed = true)

    val messagesContributor = spyk(DefaultMessages(roomId, realtimeClient.channels, chatApi), recordPrivateCalls = true)
    val presenceContributor = spyk(DefaultPresence(messagesContributor), recordPrivateCalls = true)
    val occupancyContributor = spyk(DefaultOccupancy(messagesContributor), recordPrivateCalls = true)
    val typingContributor = spyk(DefaultTyping(roomId, realtimeClient), recordPrivateCalls = true)
    val reactionsContributor = spyk(DefaultRoomReactions(roomId, "client1", realtimeClient.channels), recordPrivateCalls = true)
    return listOf(messagesContributor, presenceContributor, occupancyContributor, typingContributor, reactionsContributor)
}
