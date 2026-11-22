package com.lumo.app.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Watch Together: one small shared document per chat (chats/{chat}/watch/live) holds what is
 * playing, whether it plays, and where. Whoever presses play, pause or seeks writes it; the other
 * phone follows within a fraction of a second. Each viewer also refreshes a heartbeat so both
 * can see who is watching right now.
 */
object Watch {
    data class Session(
        val fileId: String,
        val playing: Boolean,
        val positionMs: Long,
        /** Server time of the last control, so a late arrival can catch up. */
        val at: Long?,
        val by: String,
        val seq: Long,
        val viewers: Map<String, Long>,
    )

    private val db by lazy { FirebaseFirestore.getInstance() }
    private fun ref(chatId: String) = db.collection("chats").document(chatId).collection("watch").document("live")

    private fun DocumentSnapshot.millis(field: String): Long? =
        getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toDate()?.time

    fun listen(chatId: String): Flow<Session?> = callbackFlow {
        val reg = ref(chatId).addSnapshotListener { d, _ ->
            if (d == null || !d.exists()) { trySend(null); return@addSnapshotListener }
            val viewers = (d.get("viewers") as? Map<*, *>)?.keys?.filterIsInstance<String>()
                ?.associateWith { uid -> d.millis("viewers.$uid") ?: 0L }.orEmpty()
            trySend(
                Session(
                    fileId = d.getString("fileId").orEmpty(),
                    playing = d.getBoolean("playing") == true,
                    positionMs = d.getLong("pos") ?: 0L,
                    at = d.millis("at"),
                    by = d.getString("by").orEmpty(),
                    seq = d.getLong("seq") ?: 0L,
                    viewers = viewers,
                )
            )
        }
        awaitClose { reg.remove() }
    }

    /** Play, pause or seek for both people. */
    fun control(chatId: String, fileId: String, playing: Boolean, positionMs: Long) {
        ref(chatId).set(
            mapOf(
                "fileId" to fileId, "playing" to playing, "pos" to positionMs,
                "at" to FieldValue.serverTimestamp(), "by" to Repo.myUid, "seq" to FieldValue.increment(1),
            ),
            SetOptions.merge(),
        )
    }

    fun heartbeat(chatId: String) {
        ref(chatId).set(mapOf("viewers" to mapOf(Repo.myUid to FieldValue.serverTimestamp())), SetOptions.merge())
    }

    fun leave(chatId: String) {
        ref(chatId).update(FieldPath.of("viewers", Repo.myUid), FieldValue.delete())
    }
}
