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
import androidx.compose.material.icons.rounded.Done
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lumo.app.call.CallManager
import com.lumo.app.data.LumoUser
import com.lumo.app.data.Message
import com.lumo.app.data.Repo
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
                Bubble(m, mine = m.sender == Repo.myUid, tail = !groupedWithNewer)
                if (older == null || !sameDay(older.time, m.time)) DayLabel(m.time)
                else if (!groupedWithOlder) Spacer(Modifier.height(8.dp))
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
                        .clickable { Repo.send(otherUid, text.trim()); text = "" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = Color.White, modifier = Modifier.size(22.dp)) }
            }
        }
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

@Composable
private fun Bubble(m: Message, mine: Boolean, tail: Boolean) {
    val big = 22.dp
    val small = 6.dp
    val shape = if (mine) RoundedCornerShape(big, big, if (tail) small else big, big)
    else RoundedCornerShape(big, big, big, if (tail) small else big)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.5.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier.widthIn(max = 300.dp).clip(shape)
                .then(if (mine) Modifier.background(C.AccentBrush) else Modifier.background(C.Surface2))
                .padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 7.dp),
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(m.text, color = if (mine) Color.White else C.Text, fontSize = 16.sp, lineHeight = 21.sp, modifier = Modifier.align(Alignment.Start))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(m.time),
                        color = if (mine) Color.White.copy(alpha = 0.75f) else C.Sub, fontSize = 11.sp)
                    if (mine) {
                        Spacer(Modifier.width(4.dp))
                        Icon(if (m.pending) Icons.Rounded.Schedule else Icons.Rounded.Done, null,
                            tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
    }
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
