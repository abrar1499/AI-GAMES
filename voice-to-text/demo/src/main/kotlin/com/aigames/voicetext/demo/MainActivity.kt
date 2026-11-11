package com.aigames.voicetext.demo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.aigames.voicetext.VoiceError
import com.aigames.voicetext.VoiceLanguage
import com.aigames.voicetext.VoiceLanguages
import com.aigames.voicetext.VoiceToText
import com.aigames.voicewhisper.AudioRecorder
import com.aigames.voicewhisper.ModelStore
import com.aigames.voicewhisper.WhisperEngine
import com.aigames.voicewhisper.WhisperModel
import com.aigames.voicewhisper.WhisperText
import java.io.IOException
import java.util.concurrent.Executors

/**
 * A dictation pad with two ways of working:
 *
 * - Live: the words appear while you speak (Android's own recognizer).
 * - Record: record first, then the recording is turned into text on the
 *   phone by a Whisper model. Slower, but it works on a finished recording
 *   and without internet.
 */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var text: EditText
    private lateinit var partial: TextView
    private lateinit var status: TextView
    private lateinit var mic: ImageButton
    private lateinit var halo: View
    private lateinit var keep: Switch
    private lateinit var roman: Switch
    private lateinit var modelButton: Button

    private val handler = Handler(Looper.getMainLooper())

    // Live mode.
    private lateinit var voice: VoiceToText
    private var languageTag = VoiceLanguages.COMMON.first().tag

    // Record mode.
    private var recordMode = false
    private val recorder = AudioRecorder()
    private lateinit var models: ModelStore
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: WhisperEngine? = null
    private var engineModel: WhisperModel? = null
    private var busy = false
    private var downloading = false
    private var startedAt = 0L

    /** The status line is showing "listening" or the recording clock. */
    private var activityShown = false

    /** What the microphone permission was asked for, so we can carry on afterwards. */
    private var afterPermission: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getPreferences(MODE_PRIVATE)
        models = ModelStore(this)
        text = findViewById(R.id.text)
        partial = findViewById(R.id.partial)
        status = findViewById(R.id.status)
        mic = findViewById(R.id.mic)
        halo = findViewById(R.id.halo)
        keep = findViewById(R.id.keep)
        roman = findViewById(R.id.roman)
        modelButton = findViewById(R.id.model)
        val language = findViewById<Spinner>(R.id.language)
        val mode = findViewById<RadioGroup>(R.id.mode)

        roman.isChecked = prefs.getBoolean(KEY_ROMAN, true)

        voice = VoiceToText(this)
        voice.continuous = keep.isChecked
        voice.roman = roman.isChecked
        voice.onPartial = { partial.text = it }
        voice.onFinal = { sentence ->
            partial.text = ""
            addSentence(sentence)
        }
        voice.onError = { error ->
            partial.text = ""
            activityShown = false
            status.text = error.message
        }
        voice.onListeningChanged = { showActive(it, R.string.listening, R.string.tap_to_speak) }
        voice.onLevel = { showLevel(it) }

        recorder.onLevel = { showLevel(it) }
        recorder.onLimitReached = {
            Toast.makeText(this, R.string.limit_reached, Toast.LENGTH_SHORT).show()
            finishRecording()
        }

        language.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, VoiceLanguages.COMMON)
        val saved = prefs.getString(KEY_LANGUAGE, languageTag)
        language.setSelection(VoiceLanguages.COMMON.indexOfFirst { it.tag == saved }.coerceAtLeast(0))
        languageTag = (language.selectedItem as VoiceLanguage).tag
        voice.language = languageTag
        language.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val picked = VoiceLanguages.COMMON[position]
                if (picked.tag == languageTag) return
                // A new language only takes effect on the next start.
                if (voice.isListening) voice.cancel()
                languageTag = picked.tag
                voice.language = picked.tag
                prefs.edit().putString(KEY_LANGUAGE, picked.tag).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        keep.setOnCheckedChangeListener { _, checked -> voice.continuous = checked }
        roman.setOnCheckedChangeListener { _, checked ->
            // Roman Urdu is listened to differently, so start afresh.
            if (voice.isListening) voice.cancel()
            partial.text = ""
            voice.roman = checked
            prefs.edit().putBoolean(KEY_ROMAN, checked).apply()
        }
        mode.setOnCheckedChangeListener { _, id -> setRecordMode(id == R.id.mode_record) }
        modelButton.setOnClickListener { if (!busy && !recorder.isRecording && !downloading) chooseModel(thenRecord = false) }
        mic.setOnClickListener { if (recordMode) toggleRecording() else toggleLive() }
        findViewById<Button>(R.id.copy).setOnClickListener { copy() }
        findViewById<Button>(R.id.share).setOnClickListener { share() }
        findViewById<Button>(R.id.clear).setOnClickListener {
            text.setText("")
            if (!busy && !downloading) partial.text = ""
        }

        if (!VoiceToText.isAvailable(this)) status.text = VoiceError.NOT_AVAILABLE.message
    }

    // ---------------------------------------------------------------- modes

    private fun setRecordMode(on: Boolean) {
        if (on == recordMode) return
        if (voice.isListening) voice.cancel()
        if (recorder.isRecording) recorder.stop()
        showActive(false, R.string.listening, R.string.tap_to_speak)
        recordMode = on
        keep.isEnabled = !on
        modelButton.visibility = if (on) View.VISIBLE else View.GONE
        if (!busy && !downloading) {
            partial.text = ""
            status.setText(if (on) R.string.tap_to_record else R.string.tap_to_speak)
        }
    }

    private fun withMicPermission(action: () -> Unit) {
        if (VoiceToText.hasMicPermission(this)) {
            action()
        } else {
            afterPermission = action
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        val action = afterPermission
        afterPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            action?.invoke()
        } else {
            status.setText(R.string.mic_denied)
        }
    }

    // ---------------------------------------------------------------- live mode

    private fun toggleLive() {
        if (voice.isListening) voice.stop() else withMicPermission { voice.start() }
    }

    // ---------------------------------------------------------------- record mode

    private fun chosenModel(): WhisperModel =
        WhisperModel.entries.firstOrNull { it.name == prefs.getString(KEY_MODEL, null) } ?: WhisperModel.ACCURATE

    private fun toggleRecording() {
        when {
            busy || downloading -> return
            recorder.isRecording -> finishRecording()
            !models.isDownloaded(chosenModel()) -> chooseModel(thenRecord = true)
            else -> withMicPermission { startRecording() }
        }
    }

    private fun startRecording() {
        if (!recorder.start()) {
            status.setText(R.string.mic_failed)
            return
        }
        partial.text = ""
        startedAt = SystemClock.elapsedRealtime()
        showActive(true, R.string.listening, R.string.tap_to_record)
        tickRecording()
    }

    private fun tickRecording() {
        if (!recorder.isRecording) return
        val seconds = recorder.seconds.toInt()
        status.text = getString(R.string.recording, "%d:%02d".format(seconds / 60, seconds % 60))
        handler.postDelayed({ tickRecording() }, 250)
    }

    private fun finishRecording() {
        val samples = recorder.stop()
        showActive(false, R.string.listening, R.string.tap_to_record)
        if (samples.size < AudioRecorder.SAMPLE_RATE * 6 / 10) {
            status.setText(R.string.too_short)
            return
        }
        convert(samples)
    }

    /** Runs the recording through the speech model on a background thread. */
    private fun convert(samples: FloatArray) {
        val model = chosenModel()
        val wantRoman = roman.isChecked
        val code = WhisperText.languageCode(languageTag)
        // Urdu script leaves out vowels, so for Roman text the model is asked to
        // write the same speech as Hindi, which Romanizer can spell out. Only
        // the bigger model does that reliably.
        val language = if (wantRoman && code in HINDI_URDU && model == WhisperModel.ACCURATE) "hi" else code

        busy = true
        mic.isEnabled = false
        mic.alpha = 0.5f
        partial.setText(R.string.converting_note)
        startedAt = SystemClock.elapsedRealtime()
        tickConverting()

        worker.execute {
            var result: String? = null
            var failed = false
            try {
                if (engine == null || engineModel != model) {
                    engine?.close()
                    engine = null
                    engine = WhisperEngine(models.file(model))
                    engineModel = model
                }
                result = engine?.transcribe(samples, language)
            } catch (e: IOException) {
                failed = true
            }
            val words = result?.let { if (wantRoman) VoiceToText.romanize(it) else it }
            handler.post {
                if (isDestroyed) return@post
                busy = false
                mic.isEnabled = true
                mic.alpha = 1f
                partial.text = ""
                when {
                    failed -> status.setText(R.string.model_failed)
                    words.isNullOrBlank() -> status.setText(R.string.heard_nothing)
                    else -> {
                        addSentence(words)
                        status.setText(if (recordMode) R.string.tap_to_record else R.string.tap_to_speak)
                    }
                }
            }
        }
    }

    private fun tickConverting() {
        if (!busy) return
        val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        status.text = getString(R.string.converting, seconds)
        handler.postDelayed({ tickConverting() }, 500)
    }

    private fun chooseModel(thenRecord: Boolean) {
        val all = WhisperModel.entries
        var picked = all.indexOf(chosenModel())
        val labels = all.map { model ->
            val note = if (model == WhisperModel.ACCURATE) getString(R.string.model_best) else ""
            model.label + note + if (models.isDownloaded(model)) "  ✓" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_model)
            .setSingleChoiceItems(labels, picked) { _, which -> picked = which }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.use) { _, _ ->
                val model = all[picked]
                prefs.edit().putString(KEY_MODEL, model.name).apply()
                if (models.isDownloaded(model)) {
                    status.setText(R.string.model_ready)
                    if (thenRecord) withMicPermission { startRecording() }
                } else {
                    download(model)
                }
            }
            .show()
    }

    private fun download(model: WhisperModel) {
        downloading = true
        mic.isEnabled = false
        mic.alpha = 0.5f
        status.text = getString(R.string.downloading, 0)
        Thread({
            val ok = try {
                models.download(model, keepGoing = { !isDestroyed }) { done ->
                    handler.post { if (downloading) status.text = getString(R.string.downloading, (done * 100).toInt()) }
                }
                true
            } catch (e: IOException) {
                false
            }
            handler.post {
                if (isDestroyed) return@post
                downloading = false
                mic.isEnabled = true
                mic.alpha = 1f
                status.setText(if (ok) R.string.model_ready else R.string.download_failed)
            }
        }, "model-download").start()
    }

    // ---------------------------------------------------------------- shared

    /** Puts a finished sentence where the cursor is, with a space before it if needed. */
    private fun addSentence(sentence: String) {
        val at = text.selectionEnd.coerceAtLeast(0)
        val before = text.text
        val needsSpace = at > 0 && !before[at - 1].isWhitespace()
        val piece = (if (needsSpace) " " else "") + sentence
        before.insert(at, piece)
        text.setSelection(at + piece.length)
    }

    /** The microphone turns red with a halo while it is listening or recording. */
    private fun showActive(active: Boolean, activeText: Int, idleText: Int) {
        mic.setBackgroundResource(if (active) R.drawable.mic_live else R.drawable.mic_idle)
        halo.animate().alpha(if (active) 1f else 0f).setDuration(150).start()
        if (active) {
            status.setText(activeText)
            activityShown = true
        } else {
            halo.scaleX = 1f
            halo.scaleY = 1f
            // Go back to the prompt, unless an error has replaced the "listening" text.
            if (activityShown) status.setText(idleText)
            activityShown = false
        }
    }

    private fun showLevel(level: Float) {
        // The halo behind the button swells with the voice.
        val size = 1f + 0.55f * level
        halo.scaleX = size
        halo.scaleY = size
    }

    private fun copy() {
        val words = text.text.toString()
        if (words.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), words))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val words = text.text.toString()
        if (words.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, words)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    override fun onPause() {
        super.onPause()
        // Do not keep the microphone open while the app is in the background.
        if (voice.isListening) voice.stop()
        if (recorder.isRecording) finishRecording()
    }

    override fun onDestroy() {
        voice.release()
        if (recorder.isRecording) recorder.stop()
        handler.removeCallbacksAndMessages(null)
        engine?.cancel()
        worker.execute {
            engine?.close()
            engine = null
        }
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_MIC = 1
        const val KEY_LANGUAGE = "language"
        const val KEY_ROMAN = "roman"
        const val KEY_MODEL = "model"
        val HINDI_URDU = setOf("ur", "hi")
    }
}
