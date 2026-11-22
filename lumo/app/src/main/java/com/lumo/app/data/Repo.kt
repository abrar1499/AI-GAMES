package com.lumo.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class LumoUser(val uid: String = "", val username: String = "", val name: String = "") {
    val display: String get() = name.ifBlank { username }
}

/** WhatsApp-style ticks for my own messages. */
enum class Tick { Pending, Sent, Delivered, Read }

data class Chat(
    val id: String,
    val other: String,
    val lastMessage: String,
    val lastSender: String,
    val lastTime: Long,
    val lastTick: Tick = Tick.Sent,
)

data class Message(
    val id: String,
    val sender: String,
    val text: String,
    val time: Long,
    val pending: Boolean,
    val edited: Boolean = false,
    /** Unsent with "Delete for everyone": shown as "This message was deleted". */
    val deleted: Boolean = false,
    val tick: Tick = Tick.Sent,
    /** "text", "image", "video", "file" or "call". */
    val type: String = "text",
    val attachment: Attachment? = null,
    val call: CallInfo? = null,
)

data class Attachment(
    val fileId: String, val name: String, val size: Long, val mime: String,
    val pieces: Int, val width: Int = 0, val height: Int = 0,
    /** Length of a voice message. */
    val durationMs: Long = 0,
)

/** A finished call shown in the chat. outcome: completed, missed, declined, busy or failed. */
data class CallInfo(val video: Boolean, val outcome: String, val seconds: Long, val caller: String)

/** One row of the call log, from the point of view of this phone's user. */
data class CallEntry(
    val id: String, val peer: String, val peerName: String, val outgoing: Boolean,
    val video: Boolean, val missed: Boolean, val time: Long, val seconds: Long,
)

/** Everything the app stores lives in Firestore; this is the one place that talks to it. */
object Repo {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    val myUid: String get() = auth.currentUser?.uid.orEmpty()
    val myUsername: String get() = auth.currentUser?.email.orEmpty().substringBefore("@")

    /** Usernames ride on Firebase email accounts: "ali" signs in as ali@lumo.app. */
    fun emailFor(username: String) = "${username.lowercase()}@lumo.app"

    var myName: String = ""
        private set

    private val nameCache = mutableMapOf<String, LumoUser>()

    fun chatId(a: String, b: String) = if (a < b) "${a}_$b" else "${b}_$a"

    /** Server times, using the phone's estimate while a write is still on its way. */
    private fun DocumentSnapshot.millis(field: String): Long? =
        getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time

    private fun tickFor(time: Long, pending: Boolean, deliveredTo: Long, readTo: Long) = when {
        pending -> Tick.Pending
        time <= readTo -> Tick.Read
        time <= deliveredTo -> Tick.Delivered
        else -> Tick.Sent
    }

    private fun DocumentSnapshot.toUser() = LumoUser(
        uid = id,
        username = getString("username").orEmpty(),
        name = getString("name").orEmpty(),
    )

    suspend fun myProfile(): LumoUser? {
        val doc = db.collection("users").document(myUid).get().await()
        return if (doc.exists() && !doc.getString("name").isNullOrBlank()) doc.toUser().also { myName = it.name } else null
    }

    /**
     * Makes sure this account has a profile: the phone's copy answers instantly, the server only
     * when there is none on the phone. A brand-new account gets its username as its name.
     */
    suspend fun ensureProfile() {
        val ref = db.collection("users").document(myUid)
        val cached = runCatching { ref.get(Source.CACHE).await() }.getOrNull()
        if (cached != null && !cached.getString("name").isNullOrBlank()) {
            myName = cached.getString("name").orEmpty()
            refreshPushToken()
            return
        }
        if (myProfile() == null) saveProfile(myUsername) else refreshPushToken()
    }

    suspend fun saveProfile(name: String) {
        myName = name.trim()
        db.collection("users").document(myUid).set(
            mapOf("username" to myUsername, "name" to name.trim(), "updatedAt" to FieldValue.serverTimestamp()),
            SetOptions.merge(),
        ).await()
        refreshPushToken()
    }

    fun refreshPushToken() {
        if (myUid.isEmpty()) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { savePushToken(it) }
    }

    fun savePushToken(token: String) {
        if (myUid.isEmpty()) return
        db.collection("users").document(myUid).set(mapOf("fcm" to token), SetOptions.merge())
    }

    /**
     * Names come from the phone's own copy first (instant, works offline); the network is only
     * asked when this person has never been seen on this phone. A weak signal never blocks the list.
     */
    suspend fun user(uid: String): LumoUser {
        nameCache[uid]?.let { return it }
        val ref = db.collection("users").document(uid)
        val cached = runCatching { ref.get(Source.CACHE).await() }.getOrNull()?.takeIf { it.exists() }?.toUser()
        if (cached != null && cached.username.isNotEmpty()) {
            nameCache[uid] = cached
            ref.get(Source.SERVER).addOnSuccessListener { nameCache[uid] = it.toUser() } // refresh quietly
            return cached
        }
        val u = ref.get().await().toUser()
        nameCache[uid] = u
        return u
    }

    /** People whose username starts with what was typed. */
    suspend fun search(prefix: String): List<LumoUser> {
        val p = prefix.lowercase().trim().removePrefix("@")
        if (p.isEmpty()) return emptyList()
        return db.collection("users")
            .whereGreaterThanOrEqualTo("username", p)
            .whereLessThan("username", p + "\uf8ff")
            .limit(25).get().await().documents
            .map { it.toUser() }
            .filter { it.uid != myUid }
            .onEach { nameCache[it.uid] = it }
    }

    fun chats(): Flow<List<Chat>> = callbackFlow {
        val reg = db.collection("chats")
            .whereArrayContains("members", myUid)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val list = snap.documents.mapNotNull { d ->
                    val members = (d.get("members") as? List<*>)?.filterIsInstance<String>() ?: return@mapNotNull null
                    val last = d.millis("lastTime") ?: System.currentTimeMillis()
                    val cleared = d.millis("clearedAt.$myUid") ?: 0L
                    if (cleared >= last) return@mapNotNull null // "Delete chat for me", nothing new since
                    val other = members.firstOrNull { it != myUid } ?: myUid
                    Chat(
                        id = d.id,
                        other = other,
                        lastMessage = d.getString("lastMessage").orEmpty(),
                        lastSender = d.getString("lastSender").orEmpty(),
                        lastTime = last,
                        lastTick = tickFor(last, d.metadata.hasPendingWrites(),
                            d.millis("deliveredUpTo.$other") ?: 0L, d.millis("readUpTo.$other") ?: 0L),
                    )
                }.sortedByDescending { it.lastTime }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    fun messages(chatId: String): Flow<List<Message>> = callbackFlow {
        val chat = db.collection("chats").document(chatId)
        var cleared = 0L
        var deliveredTo = 0L
        var readTo = 0L
        var latest: List<DocumentSnapshot> = emptyList()
        val other = chatId.split("_").firstOrNull { it != myUid } ?: myUid
        fun emit() {
            val me = myUid
            trySend(latest.mapNotNull { d ->
                val time = d.millis("time") ?: System.currentTimeMillis()
                val hidden = (d.get("hiddenFor") as? List<*>)?.contains(me) == true
                if (hidden || time <= cleared) return@mapNotNull null
                Message(
                    id = d.id,
                    sender = d.getString("sender").orEmpty(),
                    text = d.getString("text").orEmpty(),
                    time = time,
                    pending = d.metadata.hasPendingWrites(),
                    edited = d.getBoolean("edited") == true,
                    deleted = d.getBoolean("deleted") == true,
                    tick = tickFor(time, d.metadata.hasPendingWrites(), deliveredTo, readTo),
                    type = d.getString("type") ?: "text",
                    attachment = d.getString("fileId")?.let { fid ->
                        Attachment(
                            fileId = fid, name = d.getString("fileName").orEmpty(), size = d.getLong("fileSize") ?: 0,
                            mime = d.getString("mime").orEmpty(), pieces = (d.getLong("pieces") ?: 1).toInt(),
                            width = (d.getLong("w") ?: 0).toInt(), height = (d.getLong("h") ?: 0).toInt(),
                            durationMs = d.getLong("durationMs") ?: 0,
                        )
                    },
                    call = if (d.getString("type") == "call") CallInfo(
                        video = d.getBoolean("video") == true, outcome = d.getString("outcome").orEmpty(),
                        seconds = d.getLong("seconds") ?: 0, caller = d.getString("sender").orEmpty(),
                    ) else null,
                )
            })
        }
        val chatReg = chat.addSnapshotListener { d, _ ->
            cleared = d?.millis("clearedAt.$myUid") ?: 0L
            deliveredTo = d?.millis("deliveredUpTo.$other") ?: 0L
            readTo = d?.millis("readUpTo.$other") ?: 0L
            emit()
        }
        val reg = chat.collection("messages")
            .orderBy("time", Query.Direction.DESCENDING)
            .limit(300)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                latest = snap.documents
                emit()
            }
        awaitClose { reg.remove(); chatReg.remove() }
    }

    fun send(other: String, text: String) = post(other, text, mapOf("text" to text))

    /** Writes a message and the chat preview together. */
    private fun post(other: String, preview: String, fields: Map<String, Any>) {
        val id = chatId(myUid, other)
        val chat = db.collection("chats").document(id)
        val batch = db.batch()
        batch.set(chat, mapOf(
            "members" to listOf(myUid, other),
            "lastMessage" to preview,
            "lastSender" to myUid,
            "lastTime" to FieldValue.serverTimestamp(),
        ), SetOptions.merge())
        batch.set(chat.collection("messages").document(), mapOf(
            "sender" to myUid,
            "to" to other,
            "time" to FieldValue.serverTimestamp(),
        ) + fields)
        batch.commit()
    }

    /** Uploads a photo, video or document, then sends it. */
    suspend fun sendFile(
        context: android.content.Context, other: String, picked: Files.Picked, type: String, caption: String,
        durationMs: Long = 0, progress: (Float) -> Unit,
    ) {
        val (fileId, pieces) = Files.upload(context, chatId(myUid, other), picked.bytes, progress)
        val preview = when (type) {
            "image" -> "\uD83D\uDCF7 " + caption.ifBlank { "Photo" }
            "video" -> "\uD83C\uDFA5 " + caption.ifBlank { "Video" }
            "audio" -> "\uD83C\uDFA4 Voice message"
            else -> "\uD83D\uDCC4 " + picked.name
        }
        post(other, preview, mapOf(
            "type" to type, "text" to caption, "fileId" to fileId, "fileName" to picked.name,
            "fileSize" to picked.bytes.size.toLong(), "mime" to picked.mime, "pieces" to pieces,
            "w" to picked.width, "h" to picked.height, "durationMs" to durationMs,
        ))
    }

    /** "Missed voice call" / "Video call · 4 min" line in the chat, written by the caller. */
    fun sendCallRecord(other: String, video: Boolean, outcome: String, seconds: Long) {
        val kind = if (video) "Video call" else "Voice call"
        val preview = (if (video) "\uD83C\uDFA5 " else "\uD83D\uDCDE ") + when (outcome) {
            "completed" -> kind
            "missed" -> "Missed ${kind.lowercase()}"
            else -> kind
        }
        post(other, preview, mapOf("type" to "call", "text" to "", "video" to video, "outcome" to outcome, "seconds" to seconds))
    }

    /** Every call I made or received, newest first. */
    fun callLog(): Flow<List<CallEntry>> = callbackFlow {
        val me = myUid
        var made = emptyList<DocumentSnapshot>()
        var got = emptyList<DocumentSnapshot>()
        suspend fun nameOf(uid: String) = runCatching { user(uid).display }.getOrDefault("")
        fun emit() {
            val rows = (made + got).distinctBy { it.id }.mapNotNull { d ->
                val caller = d.getString("caller") ?: return@mapNotNull null
                val outgoing = caller == me
                val peer = if (outgoing) d.getString("callee").orEmpty() else caller
                val status = d.getString("status").orEmpty()
                // Calls from before v1.4 have no recorded length: use start-to-end (includes ringing).
                val seconds = d.getLong("seconds") ?: run {
                    val start = d.millis("createdAt"); val end = d.millis("endedAt")
                    if (status == "ended" && d.contains("answer") && start != null && end != null) (end - start) / 1000 else 0L
                }
                CallEntry(
                    id = d.id, peer = peer,
                    peerName = if (outgoing) nameCache[peer]?.display.orEmpty()
                        else d.getString("callerName").orEmpty().ifBlank { d.getString("callerUsername").orEmpty() },
                    outgoing = outgoing, video = d.getBoolean("video") == true,
                    missed = !outgoing && seconds == 0L && status in setOf("missed", "declined", "busy"),
                    time = d.millis("createdAt") ?: 0L, seconds = seconds,
                )
            }.sortedByDescending { it.time }
            trySend(rows)
        }
        val calls = db.collection("calls")
        val r1 = calls.whereEqualTo("caller", me).orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { s1, _ ->
                made = s1?.documents ?: made
                // Fill in names of people I called, then show the list again.
                launch {
                    made.mapNotNull { it.getString("callee") }.distinct().forEach { nameOf(it) }
                    emit()
                }
            }
        val r2 = calls.whereEqualTo("callee", me).orderBy("createdAt", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener { s2, _ -> got = s2?.documents ?: got; emit() }
        awaitClose { r1.remove(); r2.remove() }
    }

    // ------------------------------------------------------------ delivered and read

    /**
     * "Everything up to now reached my phone" (grey double tick) / "...and I've seen it" (blue).
     * One small write per chat rather than one per message.
     */
    fun markDelivered(chatId: String) {
        db.collection("chats").document(chatId)
            .update(FieldPath.of("deliveredUpTo", myUid), FieldValue.serverTimestamp())
    }

    fun markRead(chatId: String) {
        db.collection("chats").document(chatId).update(
            FieldPath.of("deliveredUpTo", myUid), FieldValue.serverTimestamp(),
            FieldPath.of("readUpTo", myUid), FieldValue.serverTimestamp(),
        )
    }

    /** Keeps the delivered ticks going for the whole time the app is alive, not just on one screen. */
    fun watchDeliveries(): com.google.firebase.firestore.ListenerRegistration =
        db.collection("chats").whereArrayContains("members", myUid).addSnapshotListener { snap, _ ->
            snap ?: return@addSnapshotListener
            val me = myUid
            snap.documents.forEach { d ->
                if (d.metadata.hasPendingWrites()) return@forEach
                val sender = d.getString("lastSender").orEmpty()
                if (sender.isEmpty() || sender == me) return@forEach
                val last = d.millis("lastTime") ?: return@forEach
                if (last > (d.millis("deliveredUpTo.$me") ?: 0L)) markDelivered(d.id)
            }
        }

    // ------------------------------------------------------------ edit and delete

    private fun msgRef(chatId: String, id: String) =
        db.collection("chats").document(chatId).collection("messages").document(id)

    /** Keeps the chat list preview in step when the newest message changes. */
    private fun updatePreview(chatId: String, text: String) {
        db.collection("chats").document(chatId).update("lastMessage", text)
    }

    fun editMessage(chatId: String, id: String, text: String, isLatest: Boolean) {
        msgRef(chatId, id).update(mapOf("text" to text, "edited" to true))
        if (isLatest) updatePreview(chatId, text)
    }

    fun deleteForMe(chatId: String, id: String) {
        msgRef(chatId, id).update("hiddenFor", FieldValue.arrayUnion(myUid))
    }

    fun deleteForEveryone(chatId: String, id: String, isLatest: Boolean) {
        msgRef(chatId, id).update(mapOf("text" to "", "deleted" to true, "edited" to false))
        if (isLatest) updatePreview(chatId, DELETED_TEXT)
    }

    /** Hides everything up to now for me only; the chat comes back if a new message arrives. */
    fun deleteChatForMe(chatId: String) {
        db.collection("chats").document(chatId)
            .update(FieldPath.of("clearedAt", myUid), FieldValue.serverTimestamp())
    }

    /** Removes the whole conversation for both people. */
    suspend fun deleteChatForEveryone(chatId: String) {
        val chat = db.collection("chats").document(chatId)
        while (true) {
            val page = chat.collection("messages").limit(400).get().await()
            if (page.isEmpty) break
            val batch = db.batch()
            page.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        chat.delete().await()
    }

    const val DELETED_TEXT = "This message was deleted"

    // ------------------------------------------------------------ presence

    /** Called when the app comes to the screen (and every 30s while it stays there) or leaves it. */
    fun setPresence(online: Boolean) {
        if (myUid.isEmpty()) return
        db.collection("users").document(myUid).set(
            mapOf("online" to online, "lastSeen" to FieldValue.serverTimestamp()), SetOptions.merge(),
        )
    }

    /** online is null when the user has never opened the app since presence existed. */
    data class Presence(val online: Boolean, val lastSeen: Long?)

    fun presence(uid: String): Flow<Presence> = callbackFlow {
        val reg = db.collection("users").document(uid).addSnapshotListener { d, _ ->
            if (d == null) return@addSnapshotListener
            trySend(Presence(d.getBoolean("online") == true, d.getTimestamp("lastSeen")?.toDate()?.time))
        }
        awaitClose { reg.remove() }
    }

    /** Logging out also stops pushes and calls reaching this phone. */
    suspend fun signOut() {
        val uid = myUid
        if (uid.isNotEmpty()) withTimeoutOrNull(4000) {
            runCatching {
                db.collection("users").document(uid).update(mapOf(
                    "fcm" to FieldValue.delete(),
                    "online" to false,
                    "lastSeen" to FieldValue.serverTimestamp(),
                )).await()
            }
        }
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
        nameCache.clear()
        myName = ""
        auth.signOut()
    }
}
