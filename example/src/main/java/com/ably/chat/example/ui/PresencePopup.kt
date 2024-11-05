package com.ably.chat.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.ably.chat.ChatClient
import com.ably.chat.PresenceMember
import com.ably.chat.Subscription
import com.ably.chat.example.Settings
import com.google.gson.JsonObject
import kotlinx.coroutines.launch

@SuppressWarnings("LongMethod")
@Composable
fun PresencePopup(chatClient: ChatClient, onDismiss: () -> Unit) {
    var members by remember { mutableStateOf(listOf<PresenceMember>()) }
    val coroutineScope = rememberCoroutineScope()
    val presence = chatClient.rooms.get(Settings.ROOM_ID).presence

    DisposableEffect(Unit) {
        var subscription: Subscription? = null

        coroutineScope.launch {
            members = presence.get()
            subscription = presence.subscribe {
                coroutineScope.launch {
                    members = presence.get()
                }
            }
        }

        onDispose {
            subscription?.unsubscribe()
        }
    }

    Popup(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.padding(16.dp),
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .wrapContentWidth(),
            ) {
                Text("Chat Members", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(8.dp))
                members.forEach { member ->
                    BasicText("${member.clientId} - (${(member.data as? JsonObject)?.get("status")?.asString})")
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = {
                    coroutineScope.launch {
                        presence.enter(
                            JsonObject().apply {
                                addProperty("status", "online")
                            },
                        )
                    }
                }) {
                    Text("Join")
                }
                Button(onClick = {
                    coroutineScope.launch {
                        presence.enter(
                            JsonObject().apply {
                                addProperty("status", "away")
                            },
                        )
                    }
                }) {
                    Text("Appear away")
                }
                Button(onClick = {
                    coroutineScope.launch {
                        presence.leave()
                    }
                }) {
                    Text("Leave")
                }
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}
