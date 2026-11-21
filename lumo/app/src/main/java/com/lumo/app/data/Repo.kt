package com.lumo.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class LumoUser(val uid: String = "", val phone: String = "", val name: String = "")

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
)

/** Everything the app stores lives in Firestore; this is the one place that talks to it. */
object Repo {
    private val db by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    val myUid: String get() = auth.currentUser?.uid.orEmpty()
    val myPhone: String get() = auth.currentUser?.phoneNumber.orEmpty()

    var myName: String = ""
        private set

    private val nameCache = mutableMapOf<String, LumoUser>()

    fun chatId(a: String, b: String) = if (a < b) "${a}_$b" else "${b}_$a"

    private fun DocumentSnapshot.toUser() = LumoUser(
        uid = id,
        phone = getString("phone").orEmpty(),
        name = getString("name").orEmpty(),
    )

    suspend fun myProfile(): LumoUser? {
        val doc = db.collection("users").document(myUid).get().await()
        return if (doc.exists() && !doc.getString("name").isNullOrBlank()) doc.toUser().also { myName = it.name } else null
    }

    suspend fun saveProfile(name: String) {
        myName = name.trim()
        db.collection("users").document(myUid).set(
            mapOf("phone" to myPhone, "name" to name.trim(), "updatedAt" to FieldValue.serverTimestamp()),
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

    suspend fun findByPhone(phone: String): LumoUser? =
        db.collection("users").whereEqualTo("phone", phone).limit(1).get().await()
            .documents.firstOrNull()?.toUser()

    /** Finds which of these E.164 numbers belong to Lumo users. */
    suspend fun findByPhones(phones: List<String>): List<LumoUser> {
        val out = mutableListOf<LumoUser>()
        phones.distinct().filter { it != myPhone }.chunked(30).forEach { chunk ->
            out += db.collection("users").whereIn("phone", chunk).get().await().documents.map { it.toUser() }
        }
        out.forEach { nameCache[it.uid] = it }
        return out
    }

    fun chats(): Flow<List<Chat>> = callbackFlow {
        val reg = db.collection("chats")
            .whereArrayContains("members", myUid)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                val list = snap.documents.mapNotNull { d ->
                    val members = (d.get("members") as? List<*>)?.filterIsInstance<String>() ?: return@mapNotNull null
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
        val reg = db.collection("chats").document(chatId).collection("messages")
            .orderBy("time", Query.Direction.DESCENDING)
            .limit(300)
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                trySend(snap.documents.map { d ->
                    Message(
                        id = d.id,
                        sender = d.getString("sender").orEmpty(),
                        text = d.getString("text").orEmpty(),
                        time = d.getTimestamp("time")?.toDate()?.time ?: System.currentTimeMillis(),
                        pending = d.metadata.hasPendingWrites(),
                    )
                })
            }
        awaitClose { reg.remove() }
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

    fun signOut() {
        nameCache.clear()
        auth.signOut()
    }
}
