package com.aigames.voicetext.demo

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.aigames.voicetext.VoiceLanguages
import com.aigames.voicetext.VoiceToText

/** A dictation pad: tap the microphone, speak, and the words appear in the box. */
class MainActivity : Activity() {

    private lateinit var voice: VoiceToText
    private lateinit var text: EditText
    private lateinit var partial: TextView
    private lateinit var status: TextView
    private lateinit var mic: ImageButton
    private lateinit var halo: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        text = findViewById(R.id.text)
        partial = findViewById(R.id.partial)
        status = findViewById(R.id.status)
        mic = findViewById(R.id.mic)
        halo = findViewById(R.id.halo)
        val keep = findViewById<Switch>(R.id.keep)
        val roman = findViewById<Switch>(R.id.roman)
        val language = findViewById<Spinner>(R.id.language)

        val prefs = getPreferences(MODE_PRIVATE)

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
            status.text = error.message
        }
        voice.onListeningChanged = { showListening(it) }
        voice.onLevel = { level ->
            // The halo behind the button swells with the voice.
            val size = 1f + 0.55f * level
            halo.scaleX = size
            halo.scaleY = size
        }

        language.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, VoiceLanguages.COMMON)
        val saved = prefs.getString(KEY_LANGUAGE, VoiceLanguages.COMMON.first().tag)
        language.setSelection(VoiceLanguages.COMMON.indexOfFirst { it.tag == saved }.coerceAtLeast(0))
        language.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val picked = VoiceLanguages.COMMON[position]
                if (picked.tag == voice.language) return
                // A new language only takes effect on the next start.
                if (voice.isListening) voice.cancel()
                voice.language = picked.tag
                prefs.edit().putString(KEY_LANGUAGE, picked.tag).apply()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        voice.language = (language.selectedItem as com.aigames.voicetext.VoiceLanguage).tag

        keep.setOnCheckedChangeListener { _, checked -> voice.continuous = checked }
        roman.setOnCheckedChangeListener { _, checked ->
            // Roman Urdu is listened to differently, so start afresh.
            if (voice.isListening) voice.cancel()
            partial.text = ""
            voice.roman = checked
            prefs.edit().putBoolean(KEY_ROMAN, checked).apply()
        }
        mic.setOnClickListener { toggle() }
        findViewById<Button>(R.id.copy).setOnClickListener { copy() }
        findViewById<Button>(R.id.share).setOnClickListener { share() }
        findViewById<Button>(R.id.clear).setOnClickListener {
            text.setText("")
            partial.text = ""
        }

        if (!VoiceToText.isAvailable(this)) {
            status.text = com.aigames.voicetext.VoiceError.NOT_AVAILABLE.message
        }
    }

    private fun toggle() {
        if (voice.isListening) {
            voice.stop()
        } else if (VoiceToText.hasMicPermission(this)) {
            voice.start()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            voice.start()
        } else {
            status.setText(R.string.mic_denied)
        }
    }

    /** Puts a finished sentence where the cursor is, with a space before it if needed. */
    private fun addSentence(sentence: String) {
        val at = text.selectionEnd.coerceAtLeast(0)
        val before = text.text
        val needsSpace = at > 0 && !before[at - 1].isWhitespace()
        val piece = (if (needsSpace) " " else "") + sentence
        before.insert(at, piece)
        text.setSelection(at + piece.length)
    }

    private fun showListening(active: Boolean) {
        mic.setBackgroundResource(if (active) R.drawable.mic_live else R.drawable.mic_idle)
        halo.animate().alpha(if (active) 1f else 0f).setDuration(150).start()
        if (active) {
            status.setText(R.string.listening)
        } else {
            halo.scaleX = 1f
            halo.scaleY = 1f
            // Keep an error message on screen; otherwise go back to the prompt.
            if (status.text.toString() == getString(R.string.listening)) status.setText(R.string.tap_to_speak)
        }
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
    }

    override fun onDestroy() {
        voice.release()
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_MIC = 1
        const val KEY_LANGUAGE = "language"
        const val KEY_ROMAN = "roman"
    }
}
