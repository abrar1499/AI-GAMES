package com.lumo.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

data class LumoUser(val uid: String = "", val username: String = "", val name: String = "") {
    val display: String get() = name.ifBlank { username }
}

data class Chat(
    val id: String,
    val other: String,
    val lastMessage: String,
    val lastSender: String,
    val lastTime: Long,
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

    private fun DocumentSnapshot.toUser() = LumoUser(
        uid = id,
        username = getString("username").orEmpty(),
        name = getString("name").orEmpty(),
    )

    suspend fun myProfile(): LumoUser? {
        val doc = db.collection("users").document(myUid).get().await()
        return if (doc.exists() && !doc.getString("name").isNullOrBlank()) doc.toUser().also { myName = it.name } else null
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

    suspend fun user(uid: String): LumoUser {
        nameCache[uid]?.let { return it }
        val u = db.collection("users").document(uid).get().await().toUser()
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
                    val last = d.getTimestamp("lastTime")?.toDate()?.time ?: System.currentTimeMillis()
                    val cleared = d.getTimestamp("clearedAt.$myUid")?.toDate()?.time ?: 0L
                    if (cleared >= last) return@mapNotNull null // "Delete chat for me", nothing new since
                    Chat(
                        id = d.id,
                        other = members.firstOrNull { it != myUid } ?: myUid,
                        lastMessage = d.getString("lastMessage").orEmpty(),
                        lastSender = d.getString("lastSender").orEmpty(),
                        lastTime = d.getTimestamp("lastTime")?.toDate()?.time ?: System.currentTimeMillis(),
                    )
                }.sortedByDescending { it.lastTime }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    fun messages(chatId: String): Flow<List<Message>> = callbackFlow {
        val chat = db.collection("chats").document(chatId)
        var cleared = 0L
        var latest: List<DocumentSnapshot> = emptyList()
        fun emit() {
            val me = myUid
            trySend(latest.mapNotNull { d ->
                val time = d.getTimestamp("time")?.toDate()?.time ?: System.currentTimeMillis()
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
                )
            })
        }
        val chatReg = chat.addSnapshotListener { d, _ ->
            cleared = d?.getTimestamp("clearedAt.$myUid")?.toDate()?.time ?: 0L
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

    fun send(other: String, text: String) {
        val id = chatId(myUid, other)
        val chat = db.collection("chats").document(id)
        val batch = db.batch()
        batch.set(chat, mapOf(
            "members" to listOf(myUid, other),
            "lastMessage" to text,
            "lastSender" to myUid,
            "lastTime" to FieldValue.serverTimestamp(),
        ), SetOptions.merge())
        batch.set(chat.collection("messages").document(), mapOf(
            "sender" to myUid,
            "to" to other,
            "text" to text,
            "time" to FieldValue.serverTimestamp(),
        ))
        batch.commit()
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
