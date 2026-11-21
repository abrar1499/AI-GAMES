package com.lumo.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lumo.app.data.Chat
import com.lumo.app.data.LumoUser
import com.lumo.app.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Names from the phone's address book, keyed by E.164 number. */
object ContactNames {
    var byPhone: Map<String, String> = emptyMap()
        private set

    fun myCountryCode(): String =
        countries.map { it.code }.filter { Repo.myPhone.startsWith("+$it") }.maxByOrNull { it.length } ?: defaultCountry().code

    fun normalize(raw: String, cc: String): String? {
        val plus = raw.trim().startsWith("+")
        val digits = raw.filter(Char::isDigit)
        if (digits.length < 6) return null
        return when {
            plus -> "+$digits"
            digits.startsWith("00") -> "+" + digits.drop(2)
            digits.startsWith("0") -> "+$cc" + digits.drop(1)
            digits.length <= 10 -> "+$cc$digits"
            else -> "+$digits"
        }
    }

    fun load(c: Context): Map<String, String> {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return byPhone
        val cc = myCountryCode()
        val out = HashMap<String, String>()
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val number = normalize(cur.getString(0) ?: continue, cc) ?: continue
                out.putIfAbsent(number, cur.getString(1) ?: number)
            }
        }
        byPhone = out
        return out
    }

    fun display(u: LumoUser) = byPhone[u.phone] ?: u.name.ifBlank { u.phone }
}

@Composable
fun HomeScreen(onOpenChat: (String) -> Unit, onNewChat: () -> Unit, onSignOut: () -> Unit) {
    val chats by remember { Repo.chats() }.collectAsState(initial = null)
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ContactNames.load(context) } }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Chats", style = MaterialTheme.typography.displaySmall, color = C.Text, modifier = Modifier.weight(1f))
                Box {
                    Box(Modifier.clip(CircleShape).clickable { menu = true }.padding(8.dp)) {
                        Avatar(Repo.myName.ifBlank { "Me" }, Repo.myUid, 38.dp)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = C.Surface2) {
                        DropdownMenuItem(
                            text = { Column { Text(Repo.myName, color = C.Text); Text(Repo.myPhone, color = C.Sub, fontSize = 13.sp) } },
                            onClick = {}, enabled = false,
                        )
                        HorizontalDivider(color = C.Stroke)
                        DropdownMenuItem(
                            text = { Text("Sign out") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
                            onClick = { menu = false; onSignOut() },
                        )
                    }
                }
            }
            when {
                chats == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp)
                }
                chats!!.isEmpty() -> EmptyChats(onNewChat)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                    items(chats!!, key = { it.id }) { chat -> ChatRow(chat) { onOpenChat(chat.other) } }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(24.dp)
                .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = C.Accent, spotColor = C.Accent)
                .size(64.dp).clip(RoundedCornerShape(22.dp)).background(C.AccentBrush).clickable(onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Edit, "New chat", tint = Color.White) }
    }
}

@Composable
private fun EmptyChats(onNewChat: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(C.Surface2), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ChatBubbleOutline, null, tint = C.Accent, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("No chats yet", color = C.Text, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text("Start a conversation with someone you know.", color = C.Sub)
        Spacer(Modifier.height(24.dp))
        Text("Start chatting", color = C.Accent, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onNewChat).padding(12.dp))
    }
}

@Composable
private fun ChatRow(chat: Chat, onClick: () -> Unit) {
    var user by remember(chat.other) { mutableStateOf<LumoUser?>(null) }
    LaunchedEffect(chat.other) { user = runCatching { Repo.user(chat.other) }.getOrNull() }
    val name = user?.let { ContactNames.display(it) } ?: ""
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name.ifBlank { " " }, chat.other, 54.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = C.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(shortTime(chat.lastTime), color = C.Sub, fontSize = 13.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                (if (chat.lastSender == Repo.myUid) "You: " else "") + chat.lastMessage,
                color = C.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
            )
        }
    }
}

fun shortTime(t: Long): String = when {
    DateUtils.isToday(t) -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(t)
    DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
    System.currentTimeMillis() - t < 6 * DateUtils.DAY_IN_MILLIS -> android.text.format.DateFormat.format("EEE", t).toString()
    else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(t)
}

@Composable
fun NewChatScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var people by remember { mutableStateOf<List<LumoUser>?>(null) }
    var looking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        val map = withContext(Dispatchers.IO) { ContactNames.load(context) }
        people = runCatching { Repo.findByPhones(map.keys.toList()) }.getOrDefault(emptyList())
            .sortedBy { ContactNames.display(it).lowercase() }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scope.launch { refresh() }
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) refresh()
        else permission.launch(Manifest.permission.READ_CONTACTS)
    }

    val digits = query.count(Char::isDigit)
    val shown = people.orEmpty().filter {
        query.isBlank() || ContactNames.display(it).contains(query, true) || it.phone.contains(query.filter(Char::isDigit))
    }

    Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = C.Text) }
            Text("New chat", style = MaterialTheme.typography.titleLarge, color = C.Text)
        }
        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
                .background(C.Surface).border(1.dp, C.Stroke, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = C.Sub)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query, onValueChange = { query = it; message = null }, singleLine = true,
                textStyle = TextStyle(color = C.Text, fontSize = 17.sp), cursorBrush = SolidColor(C.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Name or phone number", color = C.Sub.copy(alpha = 0.6f), fontSize = 17.sp)
                    inner()
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        if (digits >= 7) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !looking) {
                    val phone = ContactNames.normalize(query, ContactNames.myCountryCode()) ?: return@clickable
                    looking = true
                    scope.launch {
                        val u = runCatching { Repo.findByPhone(phone) }.getOrNull()
                        looking = false
                        if (u != null && u.uid != Repo.myUid) onOpen(u.uid) else message = "$phone isn't on Lumo yet."
                    }
                }.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(50.dp).clip(CircleShape).background(C.AccentBrush), contentAlignment = Alignment.Center) {
                    if (looking) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Search, null, tint = Color.White)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Message ${query.trim()}", color = C.Text, style = MaterialTheme.typography.titleMedium)
                    Text(message ?: "Find this number on Lumo", color = if (message != null) C.Red else C.Sub, fontSize = 14.sp)
                }
            }
        }

        Text("CONTACTS ON LUMO", color = C.Sub, fontSize = 12.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        when {
            people == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp)
            }
            shown.isEmpty() -> Text(
                if (people!!.isEmpty()) "None of your contacts are on Lumo yet. Type a number above to find someone."
                else "No matches.",
                color = C.Sub, modifier = Modifier.padding(horizontal = 24.dp),
            )
            else -> LazyColumn {
                items(shown, key = { it.uid }) { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(u.uid) }.padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(ContactNames.display(u), u.uid, 50.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(ContactNames.display(u), color = C.Text, style = MaterialTheme.typography.titleMedium)
                            Text(u.phone, color = C.Sub, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
