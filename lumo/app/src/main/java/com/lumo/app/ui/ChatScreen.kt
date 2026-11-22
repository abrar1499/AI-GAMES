package com.lumo.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lumo.app.call.CallManager
import com.lumo.app.data.LumoUser
import com.lumo.app.data.Message
import com.lumo.app.data.Repo
import com.lumo.app.data.Tick
import androidx.compose.ui.graphics.Brush
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.DateFormat
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
fun ChatScreen(otherUid: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var user by remember { mutableStateOf<LumoUser?>(null) }
    LaunchedEffect(otherUid) { user = runCatching { Repo.user(otherUid) }.getOrNull() }
    val messages by remember(otherUid) { Repo.messages(Repo.chatId(Repo.myUid, otherUid)) }.collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }
    val chatId = remember(otherUid) { Repo.chatId(Repo.myUid, otherUid) }
    val clipboard = LocalClipboardManager.current
    var menuFor by remember { mutableStateOf<Message?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visible by lifecycle.currentStateFlow.collectAsState()
    val newestIncoming = messages.firstOrNull { it.sender != Repo.myUid }?.id
    LaunchedEffect(newestIncoming, visible) {
        if (newestIncoming != null && visible.isAtLeast(Lifecycle.State.RESUMED)) Repo.markRead(chatId)
    }
    var deleting by remember { mutableStateOf<Message?>(null) }
    var editing by remember { mutableStateOf<Message?>(null) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.firstOrNull()?.id) { if (messages.isNotEmpty()) list.animateScrollToItem(0) }

    var pendingVideo by remember { mutableStateOf<Boolean?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val video = pendingVideo ?: return@rememberLauncherForActivityResult
        if (granted.values.all { it }) user?.let { CallManager.startCall(context, it.copy(name = it.display), video) }
        pendingVideo = null
    }
    fun call(video: Boolean) {
        val u = user ?: return
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) CallManager.startCall(context, u.copy(name = u.display), video)
        else { pendingVideo = video; permissions.launch(needed.toTypedArray()) }
    }

    val name = user?.display ?: ""
    val presence by remember(otherUid) { Repo.presence(otherUid) }.collectAsState(initial = null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().background(C.Bg).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = C.Text) }
            Avatar(name.ifBlank { " " }, otherUid, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = C.Text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val status = presenceText(presence, now)
                Text(
                    status ?: user?.username?.let { "@$it" }.orEmpty(),
                    color = if (status == "online") C.Green else C.Sub, fontSize = 12.sp, maxLines = 1,
                )
            }
            IconButton(onClick = { call(false) }) { Icon(Icons.Rounded.Call, "Voice call", tint = C.Text) }
            IconButton(onClick = { call(true) }) { Icon(Icons.Rounded.Videocam, "Video call", tint = C.Text) }
        }
        HorizontalDivider(color = C.Stroke.copy(alpha = 0.6f))

        LazyColumn(
            state = list, reverseLayout = true, modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            itemsIndexed(messages, key = { _, m -> m.id }) { i, m ->
                val older = messages.getOrNull(i + 1)
                val newer = messages.getOrNull(i - 1)
                val groupedWithOlder = older != null && older.sender == m.sender && m.time - older.time < 120_000
                val groupedWithNewer = newer != null && newer.sender == m.sender && newer.time - m.time < 120_000
                Bubble(m, mine = m.sender == Repo.myUid, tail = !groupedWithNewer, onMenu = { menuFor = m })
                if (older == null || !sameDay(older.time, m.time)) DayLabel(m.time)
                else if (!groupedWithOlder) Spacer(Modifier.height(8.dp))
            }
        }

        editing?.let { e ->
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Edit, null, tint = C.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Edit message", color = C.Accent, fontSize = 13.sp)
                    Text(e.text, color = C.Sub, fontSize = 13.sp, maxLines = 1)
                }
                IconButton(onClick = { editing = null; text = "" }) { Icon(Icons.Rounded.Close, "Cancel", tint = C.Sub) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = text, onValueChange = { text = it },
                textStyle = TextStyle(color = C.Text, fontSize = 17.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(C.Accent), maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f).heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp))
                    .background(C.Surface).border(1.dp, C.Stroke, RoundedCornerShape(25.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Message", color = C.Sub.copy(alpha = 0.6f), fontSize = 17.sp)
                    inner()
                },
            )
            AnimatedVisibility(text.isNotBlank(), enter = scaleIn(), exit = scaleOut()) {
                Box(
                    Modifier.padding(start = 8.dp).size(50.dp).clip(CircleShape).background(C.AccentBrush)
                        .clickable {
                            val e = editing
                            if (e != null) {
                                if (text.trim() != e.text) Repo.editMessage(chatId, e.id, text.trim(), isLatest = messages.firstOrNull()?.id == e.id)
                                editing = null
                            } else Repo.send(otherUid, text.trim())
                            text = ""
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (editing != null) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.Send, "Send",
                        tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }

    menuFor?.let { m ->
        val mine = m.sender == Repo.myUid
        MessageMenu(
            m, mine,
            onCopy = { clipboard.setText(AnnotatedString(m.text)); menuFor = null },
            onEdit = { editing = m; text = m.text; menuFor = null },
            onDelete = { deleting = m; menuFor = null },
            onDismiss = { menuFor = null },
        )
    }
    deleting?.let { m ->
        val mine = m.sender == Repo.myUid
        val isLatest = messages.firstOrNull()?.id == m.id
        DeleteDialog(
            title = "Delete message?",
            canEveryone = mine && !m.deleted && System.currentTimeMillis() - m.time < UNSEND_WINDOW,
            onMe = { Repo.deleteForMe(chatId, m.id); deleting = null },
            onEveryone = { Repo.deleteForEveryone(chatId, m.id, isLatest); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

@Composable
private fun DayLabel(t: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        val label = when (shortTime(t)) {
            "Yesterday" -> "Yesterday"
            else -> if (android.text.format.DateUtils.isToday(t)) "Today" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(t)
        }
        Text(label, color = C.Sub, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Surface).padding(horizontal = 12.dp, vertical = 5.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(m: Message, mine: Boolean, tail: Boolean, onMenu: () -> Unit) {
    val big = 22.dp
    val small = 6.dp
    val shape = if (mine) RoundedCornerShape(big, big, if (tail) small else big, big)
    else RoundedCornerShape(big, big, big, if (tail) small else big)
    val onAccent = mine && !m.deleted
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            // The tap area sits outside the rounded clip, so touches anywhere on the bubble count.
            Modifier.widthIn(max = 300.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = onMenu, onLongClick = onMenu,
                )
                .clip(shape)
                .then(if (onAccent) Modifier.background(MineBrush) else Modifier.background(C.Surface2))
                .padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 7.dp),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                if (m.deleted) Row(Modifier.align(Alignment.Start), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Block, null, tint = C.Sub, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (mine) "You deleted this message" else Repo.DELETED_TEXT,
                        color = C.Sub, fontSize = 15.sp, fontStyle = FontStyle.Italic)
                } else Text(m.text, color = if (mine) Color.White else C.Text, fontSize = 16.sp, lineHeight = 21.sp,
                    modifier = Modifier.align(Alignment.Start))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = if (onAccent) Color.White.copy(alpha = 0.75f) else C.Sub
                    if (m.edited && !m.deleted) Text("edited  ", color = meta, fontSize = 11.sp, fontStyle = FontStyle.Italic)
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(m.time), color = meta, fontSize = 11.sp)
                    if (onAccent) {
                        Spacer(Modifier.width(4.dp))
                        TickIcon(m.tick, Color.White.copy(alpha = 0.8f), size = 15)
                    }
                }
            }
        }
    }
}

/** WhatsApp-style limits: edit for 15 minutes, delete for everyone for 2 days. */
private const val EDIT_WINDOW = 15 * 60 * 1000L
private const val UNSEND_WINDOW = 48 * 60 * 60 * 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageMenu(
    m: Message, mine: Boolean,
    onCopy: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit,
) {
    val age = System.currentTimeMillis() - m.time
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.Surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            if (!m.deleted) {
                Text(m.text, color = C.Sub, maxLines = 2, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                Spacer(Modifier.height(8.dp))
                MenuRow(Icons.Rounded.ContentCopy, "Copy") { onCopy() }
                if (mine && age < EDIT_WINDOW) MenuRow(Icons.Rounded.Edit, "Edit") { onEdit() }
            }
            MenuRow(Icons.Rounded.DeleteOutline, "Delete", C.Red) { onDelete() }
        }
    }
}

@Composable
fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = C.Text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(20.dp))
        Text(label, color = tint, fontSize = 17.sp)
    }
}

/** "Delete for me / Delete for everyone / Cancel", like WhatsApp. */
@Composable
fun DeleteDialog(title: String, canEveryone: Boolean, onMe: () -> Unit, onEveryone: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(title, color = C.Text, fontSize = 18.sp) },
        text = null,
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canEveryone) TextButton(onClick = onEveryone) { Text("Delete for everyone", color = C.Red) }
                TextButton(onClick = onMe) { Text("Delete for me", color = C.Red) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = C.Accent) }
            }
        },
    )
}

/**
 * "online" while the other app is on screen and its heartbeat is fresh (a phone that lost
 * signal stops counting as online after ~90s), otherwise "last seen …".
 */
private fun presenceText(p: Repo.Presence?, now: Long): String? {
    val seen = p?.lastSeen ?: return null
    if (p.online && now - seen < 90_000) return "online"
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(seen)
    return when {
        android.text.format.DateUtils.isToday(seen) -> "last seen today at $time"
        android.text.format.DateUtils.isToday(seen + android.text.format.DateUtils.DAY_IN_MILLIS) -> "last seen yesterday at $time"
        else -> "last seen " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(seen)
    }
}

/** My bubbles fade blue to purple, so the blue "read" ticks in the corner stay visible. */
private val MineBrush = Brush.linearGradient(listOf(C.Accent2, C.Accent))

/** WhatsApp blue for "read". */
val ReadBlue = Color(0xFF53BDEB)

/** ✓ sent · ✓✓ delivered · blue ✓✓ read · clock while still sending. */
@Composable
fun TickIcon(tick: Tick, plain: Color, size: Int = 16) {
    val (icon, tint) = when (tick) {
        Tick.Pending -> Icons.Rounded.Schedule to plain
        Tick.Sent -> Icons.Rounded.Done to plain
        Tick.Delivered -> Icons.Rounded.DoneAll to plain
        Tick.Read -> Icons.Rounded.DoneAll to ReadBlue
    }
    Icon(icon, null, tint = tint, modifier = Modifier.size(size.dp))
}
