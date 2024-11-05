package com.ably.chat.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ably.chat.ChatClient
import com.ably.chat.Message
import com.ably.chat.RealtimeClient
import com.ably.chat.SendMessageParams
import com.ably.chat.SendReactionParams
import com.ably.chat.example.ui.PresencePopup
import com.ably.chat.example.ui.theme.AblyChatExampleTheme
import io.ably.lib.types.ClientOptions
import java.util.UUID
import kotlinx.coroutines.launch

val randomClientId = UUID.randomUUID().toString()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val realtimeClient = RealtimeClient(
            ClientOptions().apply {
                key = BuildConfig.ABLY_KEY
                clientId = randomClientId
                logLevel = 2
            },
        )

        val chatClient = ChatClient(realtimeClient)

        enableEdgeToEdge()
        setContent {
            AblyChatExampleTheme {
                App(chatClient)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(chatClient: ChatClient) {
    var showPopup by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                actions = {
                    IconButton(onClick = { showPopup = true }) {
                        Icon(Icons.Default.Person, contentDescription = "Show members")
                    }
                },
            )
        },
    ) { innerPadding ->
        Chat(
            chatClient,
            modifier = Modifier.padding(innerPadding),
        )
        if (showPopup) {
            PresencePopup(chatClient, onDismiss = { showPopup = false })
        }
    }
}

@SuppressWarnings("LongMethod")
@Composable
fun Chat(chatClient: ChatClient, modifier: Modifier = Modifier) {
    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var sending by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var receivedReactions by remember { mutableStateOf<List<String>>(listOf()) }

    val room = chatClient.rooms.get(Settings.ROOM_ID)

    DisposableEffect(Unit) {
        coroutineScope.launch {
            room.attach()
        }
        onDispose {
            coroutineScope.launch {
                room.detach()
            }
        }
    }

    DisposableEffect(Unit) {
        val subscription = room.messages.subscribe {
            messages += it.message
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        coroutineScope.launch {
            messages = subscription.getPreviousMessages().items.reversed()
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        onDispose {
            subscription.unsubscribe()
        }
    }

    DisposableEffect(Unit) {
        val subscription = room.reactions.subscribe {
            receivedReactions += it.type
        }

        onDispose {
            subscription.unsubscribe()
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(16.dp),
            userScrollEnabled = true,
            state = listState,
        ) {
            items(messages.size) { index ->
                MessageBubble(messages[index])
            }
        }

        ChatInputField(
            sending = sending,
            messageInput = messageText,
            onMessageChange = { messageText = it },
            onSendClick = {
                sending = true
                coroutineScope.launch {
                    room.messages.send(
                        SendMessageParams(
                            text = messageText.text,
                        ),
                    )
                    messageText = TextFieldValue("")
                    sending = false
                }
            },
            onReactionClick = {
                coroutineScope.launch {
                    room.reactions.send(SendReactionParams(type = "\uD83D\uDC4D"))
                }
            },
        )
        if (receivedReactions.isNotEmpty()) {
            Text("Received reactions: ${receivedReactions.joinToString()}", modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = if (message.clientId == randomClientId) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = if (message.clientId != randomClientId) Color.Blue else Color.Gray,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(12.dp),
        ) {
            Text(
                text = message.text,
                color = Color.White,
            )
        }
    }
}

@Composable
fun ChatInputField(
    sending: Boolean = false,
    messageInput: TextFieldValue,
    onMessageChange: (TextFieldValue) -> Unit,
    onSendClick: () -> Unit,
    onReactionClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .imePadding(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = messageInput,
            onValueChange = onMessageChange,
            modifier = Modifier
                .weight(1f)
                .background(Color.White),
            placeholder = { Text("Type a message...") },
        )
        if (messageInput.text.isNotEmpty()) {
            Button(enabled = !sending, onClick = onSendClick) {
                Text("Send")
            }
        } else {
            Button(onClick = onReactionClick) {
                Text("\uD83D\uDC4D")
            }
        }
    }
}

@Preview
@Composable
fun MessageBubblePreview() {
    AblyChatExampleTheme {
        MessageBubble(
            message = Message(
                text = "Hello World!",
                timeserial = "fake",
                roomId = "roomId",
                clientId = "clientId",
                createdAt = System.currentTimeMillis(),
                metadata = mapOf(),
                headers = mapOf(),
            ),
        )
    }
}

@Preview
@Composable
fun ChatInputPreview() {
    AblyChatExampleTheme {
        ChatInputField(
            sending = false,
            messageInput = TextFieldValue(""),
            onMessageChange = {},
            onSendClick = {},
            onReactionClick = {},
        )
    }
}
