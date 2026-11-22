package com.lumo.app.data

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Voice messages: AAC at 32 kbps mono, clear speech at about 240 KB per minute,
 * so they send quickly even on slow internet.
 */
class VoiceRecorder(context: Context) {
    val file = File(File(context.cacheDir, "voice").apply { mkdirs() }, "rec_${System.currentTimeMillis()}.m4a")
    private val startedAt = System.currentTimeMillis()
    private val recorder: MediaRecorder = (
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
    ).apply {
        setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION) // clean voice, no call-style processing
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(1)
        setAudioSamplingRate(44_100)
        setAudioEncodingBitRate(32_000)
        setOutputFile(file.path)
        prepare()
        start()
    }

    val elapsedMs get() = System.currentTimeMillis() - startedAt

    /** Finishes and returns the length in ms, or null if it was too short or failed. */
    fun stop(): Long? {
        val ms = elapsedMs
        val ok = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        if (!ok || ms < 800) { file.delete(); return null }
        return ms
    }

    fun cancel() {
        runCatching { recorder.stop() }
        recorder.release()
        file.delete()
    }
}

/** One voice message plays at a time, like WhatsApp. */
object VoicePlayer {
    data class State(val fileId: String = "", val playing: Boolean = false, val positionMs: Int = 0, val durationMs: Int = 0)

    val state = MutableStateFlow(State())
    private var player: MediaPlayer? = null

    fun toggle(fileId: String, file: File) {
        val s = state.value
        val p = player
        if (s.fileId == fileId && p != null) {
            if (p.isPlaying) { p.pause(); state.value = s.copy(playing = false) }
            else { p.start(); state.value = s.copy(playing = true) }
            return
        }
        stop()
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { state.value = state.value.copy(playing = false, positionMs = 0); seekTo(0) }
            prepare()
            start()
        }
        state.value = State(fileId, true, 0, player?.duration ?: 0)
    }

    /** Called a few times a second by the visible player to move its progress bar. */
    fun tick() {
        val p = player ?: return
        if (p.isPlaying) state.value = state.value.copy(positionMs = p.currentPosition)
    }

    fun seek(fraction: Float) {
        val p = player ?: return
        val pos = (fraction * p.duration).toInt()
        p.seekTo(pos)
        state.value = state.value.copy(positionMs = pos)
    }

    fun stop() {
        player?.release()
        player = null
        state.value = State()
    }
}
