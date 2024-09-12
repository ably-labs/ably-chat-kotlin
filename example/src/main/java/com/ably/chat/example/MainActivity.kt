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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ably.chat.ChatApi
import com.ably.chat.Message
import com.ably.chat.QueryOptions
import com.ably.chat.QueryOptions.MessageOrder.OldestFirst
import com.ably.chat.RealtimeClient
import com.ably.chat.SendMessageParams
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
        val chatApi = ChatApi(realtimeClient, randomClientId)
        enableEdgeToEdge()
        setContent {
            AblyChatExampleTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Chat(
                        chatApi,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun Chat(chatApi: ChatApi, modifier: Modifier = Modifier) {
    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var sending by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    val coroutineScope = rememberCoroutineScope()

    val roomId = "my-room"

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Button(modifier = modifier.align(Alignment.CenterHorizontally), onClick = {
            coroutineScope.launch {
                messages = chatApi.getMessages(roomId, QueryOptions(orderBy = OldestFirst)).items
            }
        }) {
            Text("Load")
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(16.dp),
            userScrollEnabled = true,
        ) {
            items(messages.size) { index ->
                MessageBubble(messages[index])
            }
        }

        ChatInputField(
            sending = sending,
            messageInput = messageText,
            onMessageChange = { messageText = it },
        ) {
            sending = true
            coroutineScope.launch {
                chatApi.sendMessage(
                    roomId,
                    SendMessageParams(
                        text = messageText.text,
                    ),
                )
                messageText = TextFieldValue("")
                sending = false
            }
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
            readOnly = sending,
            modifier = Modifier
                .weight(1f)
                .background(Color.White),
            placeholder = { Text("Type a message...") },
        )
        Button(enabled = !sending, onClick = onSendClick) {
            Text("Send")
        }
    }
}
