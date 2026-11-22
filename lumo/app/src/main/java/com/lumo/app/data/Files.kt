package com.lumo.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Attachments without paid file storage: a file is split into ~900 KB pieces stored as
 * Firestore documents next to the chat (chats/{chat}/files/{fileId}_{n}), readable only by the
 * two people in that chat. Downloads are kept on the phone so each file is fetched once.
 */
object Files {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val CHUNK = 900 * 1024
    private val db by lazy { FirebaseFirestore.getInstance() }

    data class Picked(val bytes: ByteArray, val name: String, val mime: String, val width: Int = 0, val height: Int = 0)

    class TooLarge : Exception("Files can be up to 16 MB")

    /** Reads what the user picked. Photos are resized and compressed like WhatsApp does. */
    suspend fun read(context: Context, uri: Uri, asPhoto: Boolean): Picked = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "file"
        if (asPhoto && mime.startsWith("image/")) {
            val bmp = decodeScaled(context, uri, 1600)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            return@withContext Picked(out.toByteArray(), name.substringBeforeLast('.') + ".jpg", "image/jpeg", bmp.width, bmp.height)
        }
        val bytes = cr.openInputStream(uri)?.use { input ->
            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(tmp)
                if (n < 0) break
                buf.write(tmp, 0, n)
                if (buf.size() > MAX_BYTES) throw TooLarge()
            }
            buf.toByteArray()
        } ?: error("Couldn't open the file")
        Picked(bytes, name, mime)
    }

    private fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                val w = info.size.width; val h = info.size.height
                val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
                d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })!!
        }
    }

    /** Uploads the pieces and returns the new file id; [progress] goes 0..1. */
    suspend fun upload(context: Context, chatId: String, bytes: ByteArray, progress: (Float) -> Unit): Pair<String, Int> {
        val fileId = UUID.randomUUID().toString().replace("-", "")
        val pieces = (bytes.size + CHUNK - 1) / CHUNK
        val col = db.collection("chats").document(chatId).collection("files")
        for (i in 0 until pieces) {
            val part = bytes.copyOfRange(i * CHUNK, minOf(bytes.size, (i + 1) * CHUNK))
            col.document("${fileId}_$i").set(mapOf("data" to Blob.fromBytes(part), "n" to i)).await()
            progress((i + 1f) / pieces)
        }
        // Keep our own copy so we never download what we just sent.
        withContext(Dispatchers.IO) { cacheFile(context, fileId).writeBytes(bytes) }
        return fileId to pieces
    }

    fun cacheFile(context: Context, fileId: String) =
        File(File(context.cacheDir, "attachments").apply { mkdirs() }, fileId)

    /** The file on this phone, downloading it first if needed. */
    suspend fun get(context: Context, chatId: String, fileId: String, pieces: Int): File {
        val f = cacheFile(context, fileId)
        if (f.exists() && f.length() > 0) return f
        val col = db.collection("chats").document(chatId).collection("files")
        val out = ByteArrayOutputStream()
        for (i in 0 until pieces) {
            val blob = col.document("${fileId}_$i").get().await().getBlob("data") ?: error("Missing part of the file")
            out.write(blob.toBytes())
        }
        withContext(Dispatchers.IO) {
            val tmp = File(f.parentFile, "$fileId.part")
            tmp.writeBytes(out.toByteArray())
            tmp.renameTo(f)
        }
        return f
    }

    suspend fun deleteParts(chatId: String, fileId: String, pieces: Int) {
        val col = db.collection("chats").document(chatId).collection("files")
        for (i in 0 until pieces) runCatching { col.document("${fileId}_$i").delete().await() }
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
