package com.ably.chat.room

import com.ably.chat.AndroidLogger
import com.ably.chat.DefaultRoomLifecycle
import com.ably.chat.Logger
import com.ably.chat.Room
import com.ably.chat.Rooms
import com.ably.chat.getPrivateField
import io.ably.lib.realtime.AblyRealtime
import io.ably.lib.types.ClientOptions
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred

fun createMockRealtimeClient(): AblyRealtime = spyk(AblyRealtime(ClientOptions("id:key").apply { autoConnect = false }))
internal fun createMockLogger(): Logger = mockk<AndroidLogger>(relaxed = true)

// Rooms mocks
val Rooms.RoomIdToRoom get() = getPrivateField<MutableMap<String, Room>>("roomIdToRoom")
val Rooms.RoomGetDeferred get() = getPrivateField<MutableMap<String, CompletableDeferred<Unit>>>("roomGetDeferred")
val Rooms.RoomReleaseDeferred get() = getPrivateField<MutableMap<String, CompletableDeferred<Unit>>>("roomReleaseDeferred")

// Room mocks
internal val Room.StatusLifecycle get() = getPrivateField<DefaultRoomLifecycle>("statusLifecycle")
