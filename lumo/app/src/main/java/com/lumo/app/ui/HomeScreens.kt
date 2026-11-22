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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import com.lumo.app.call.CallManager
import com.lumo.app.data.CallEntry
import com.lumo.app.BuildConfig
import com.lumo.app.update.Updater
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
import com.lumo.app.data.Chat
import com.lumo.app.data.LumoUser
import com.lumo.app.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@Composable
fun HomeScreen(onOpenChat: (String) -> Unit, onNewChat: () -> Unit, onSignOut: () -> Unit) {
    val chats by remember { Repo.chats() }.collectAsState(initial = null)
    var menu by remember { mutableStateOf(false) }
    var deletingChat by remember { mutableStateOf<Chat?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 = Chats, 1 = Calls
    val scope = rememberCoroutineScope()
    deletingChat?.let { c ->
        DeleteDialog(
            title = "Delete this chat?",
            canEveryone = true,
            onMe = { Repo.deleteChatForMe(c.id); deletingChat = null },
            onEveryone = { deletingChat = null; scope.launch { runCatching { Repo.deleteChatForEveryone(c.id) } } },
            onDismiss = { deletingChat = null },
        )
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 80.dp).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (tab == 0) "Chats" else "Calls", style = MaterialTheme.typography.displaySmall, color = C.Text, modifier = Modifier.weight(1f))
                Box {
                    Box(Modifier.clip(CircleShape).clickable { menu = true }.padding(8.dp)) {
                        Avatar(Repo.myName.ifBlank { "Me" }, Repo.myUid, 38.dp)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = C.Surface2) {
                        DropdownMenuItem(
                            text = { Column { Text(Repo.myName, color = C.Text); Text("@" + Repo.myUsername, color = C.Sub, fontSize = 13.sp) } },
                            onClick = {}, enabled = false,
                        )
                        DropdownMenuItem(
                            text = { Text("Version ${BuildConfig.VERSION_NAME}", color = C.Sub, fontSize = 13.sp) },
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
            UpdateBanner()
            if (tab == 1) CallsList(onOpenChat)
            else when {
                chats == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp)
                }
                chats!!.isEmpty() -> EmptyChats(onNewChat)
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                    items(chats!!, key = { it.id }) { chat -> ChatRow(chat, onLongClick = { deletingChat = chat }) { onOpenChat(chat.other) } }
                }
            }
        }
        if (tab == 0) Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = 100.dp)
                .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = C.Accent, spotColor = C.Accent)
                .size(64.dp).clip(RoundedCornerShape(22.dp)).background(C.AccentBrush).clickable(onClick = onNewChat),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Edit, "New chat", tint = Color.White) }

        // Bottom tabs, like WhatsApp: Chats and Calls.
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(C.Surface).navigationBarsPadding().height(80.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            TabItem(Icons.Rounded.ChatBubbleOutline, "Chats", tab == 0) { tab = 0 }
            TabItem(Icons.Rounded.Call, "Calls", tab == 1) { tab = 1 }
        }
    }
}

@Composable
private fun TabItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 28.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) C.Accent.copy(alpha = 0.18f) else Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 5.dp),
        ) { Icon(icon, label, tint = if (selected) C.Accent else C.Sub) }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) C.Text else C.Sub, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Call log: who, which direction, when and how long. Tap to open the chat, the icon to call back. */
@Composable
private fun CallsList(onOpenChat: (String) -> Unit) {
    val context = LocalContext.current
    val calls by remember { Repo.callLog() }.collectAsState(initial = null)
    var pending by remember { mutableStateOf<Pair<CallEntry, Boolean>?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val (e, video) = pending ?: return@rememberLauncherForActivityResult
        if (granted.values.all { it }) CallManager.startCall(context, LumoUser(e.peer, name = e.peerName), video)
        pending = null
    }
    fun callBack(e: CallEntry) {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (e.video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) CallManager.startCall(context, LumoUser(e.peer, name = e.peerName), e.video)
        else { pending = e to e.video; permissions.launch(needed.toTypedArray()) }
    }

    val list = calls
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = C.Accent, strokeWidth = 2.5.dp)
        }
        list.isEmpty() -> Column(
            Modifier.fillMaxSize().padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(C.Surface2), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Call, null, tint = C.Accent, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("No calls yet", color = C.Text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("Calls you make and receive show up here.", color = C.Sub)
        }
        else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.id }) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenChat(e.peer) }.padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(e.peerName.ifBlank { " " }, e.peer, 50.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.peerName, color = if (e.missed) C.Red else C.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when { e.missed -> Icons.AutoMirrored.Rounded.CallMissed; e.outgoing -> Icons.AutoMirrored.Rounded.CallMade; else -> Icons.AutoMirrored.Rounded.CallReceived },
                                null, tint = if (e.missed) C.Red else C.Green, modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                shortTime(e.time) + if (e.seconds > 0) " · " + durationText(e.seconds) else "",
                                color = C.Sub, fontSize = 14.sp,
                            )
                        }
                    }
                    IconButton(onClick = { callBack(e) }) {
                        Icon(if (e.video) Icons.Rounded.Videocam else Icons.Rounded.Call, "Call back", tint = C.Accent)
                    }
                }
            }
        }
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
        Text("Tap the pencil and search a username.", color = C.Sub)
        Spacer(Modifier.height(24.dp))
        Text("Start chatting", color = C.Accent, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onNewChat).padding(12.dp))
    }
}

@Composable
private fun ChatRow(chat: Chat, onLongClick: () -> Unit, onClick: () -> Unit) = ChatRowInner(chat, onLongClick, onClick)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRowInner(chat: Chat, onLongClick: () -> Unit, onClick: () -> Unit) {
    var user by remember(chat.other) { mutableStateOf<LumoUser?>(null) }
    LaunchedEffect(chat.other) { user = runCatching { Repo.user(chat.other) }.getOrNull() }
    val name = user?.display ?: ""
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 24.dp, vertical = 12.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.lastSender == Repo.myUid) {
                    TickIcon(chat.lastTick, C.Sub, size = 17)
                    Spacer(Modifier.width(4.dp))
                }
                Text(chat.lastMessage, color = C.Sub, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            }
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
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<LumoUser>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = emptyList(); return@LaunchedEffect }
        delay(250)
        searching = true
        results = runCatching { Repo.search(query) }.getOrDefault(emptyList())
        searching = false
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
            Text("@", color = C.Sub, fontSize = 17.sp)
            BasicTextField(
                value = query, onValueChange = { query = it.lowercase().trim().removePrefix("@").take(20) }, singleLine = true,
                textStyle = TextStyle(color = C.Text, fontSize = 17.sp), cursorBrush = SolidColor(C.Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search username", color = C.Sub.copy(alpha = 0.6f), fontSize = 17.sp)
                    inner()
                },
            )
            if (searching) CircularProgressIndicator(Modifier.size(18.dp), color = C.Accent, strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(12.dp))
        when {
            query.isBlank() -> Text("Type the username of the person you want to message.",
                color = C.Sub, modifier = Modifier.padding(horizontal = 24.dp))
            results.isEmpty() && !searching -> Text("No one found with @$query.",
                color = C.Sub, modifier = Modifier.padding(horizontal = 24.dp))
            else -> LazyColumn {
                items(results, key = { it.uid }) { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(u.uid) }.padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(u.display, u.uid, 50.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(u.display, color = C.Text, style = MaterialTheme.typography.titleMedium)
                            Text("@" + u.username, color = C.Sub, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

/** "Update available" card at the top of the chat list; one tap downloads and installs. */
@Composable
private fun UpdateBanner() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val release by Updater.available.collectAsState()
    val progress by Updater.progress.collectAsState()
    val error by Updater.error.collectAsState()
    LaunchedEffect(Unit) { Updater.check() }
    val r = release ?: return
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(C.Surface2).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(C.AccentBrush), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Update available", color = C.Text, style = MaterialTheme.typography.titleMedium)
                Text("Version ${r.name}", color = C.Sub, fontSize = 13.sp)
            }
            if (progress == null) Text(
                "Update", color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(C.AccentBrush)
                    .clickable { scope.launch { Updater.install(context) } }.padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
        progress?.let {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)),
                color = C.Accent, trackColor = C.Stroke)
        }
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = C.Red, fontSize = 13.sp) }
    }
}
