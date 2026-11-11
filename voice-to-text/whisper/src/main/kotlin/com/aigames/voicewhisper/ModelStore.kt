package com.aigames.voicewhisper

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Keeps downloaded speech models in the app's private storage. */
class ModelStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, "whisper-models")

    fun file(model: WhisperModel): File = File(dir, model.fileName)

    /** A model counts as downloaded only when the whole file is there. */
    fun isDownloaded(model: WhisperModel): Boolean = file(model).length() == model.sizeBytes

    fun delete(model: WhisperModel) {
        file(model).delete()
    }

    /**
     * Downloads [model]. Blocks, so call it from a background thread.
     * [onProgress] gets a value from 0 to 1; return false from [keepGoing] to cancel.
     * @throws IOException when the download fails or is cancelled.
     */
    @Throws(IOException::class)
    fun download(model: WhisperModel, keepGoing: () -> Boolean = { true }, onProgress: (Float) -> Unit = {}) {
        dir.mkdirs()
        val part = File(dir, model.fileName + ".part")
        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed (HTTP ${connection.responseCode})")
            }
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastShown = -1
                    while (true) {
                        if (!keepGoing()) throw IOException("Download cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = (done * 100 / model.sizeBytes).toInt()
                        if (percent != lastShown) {
                            lastShown = percent
                            onProgress((done.toFloat() / model.sizeBytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (part.length() != model.sizeBytes) throw IOException("Download was incomplete")
            val target = file(model)
            target.delete()
            if (!part.renameTo(target)) throw IOException("Could not save the model")
        } catch (e: IOException) {
            part.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }
}
