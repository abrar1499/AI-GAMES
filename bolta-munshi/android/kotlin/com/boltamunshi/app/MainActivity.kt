package com.boltamunshi.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Smart Book: a memory book you talk to. One brain, an AI, hears every sentence, keeps
 * what should be kept (money, notes, tasks) in the memory book, and answers in a few
 * natural words. The screen is the brain picture; tap to talk, or type below. Settings
 * open with three quick taps and then any stroke on the screen.
 */
class MainActivity : Activity(), RecognitionListener, TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val voiceNet = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var memory: Memory
    private lateinit var brainAi: Brain
    private lateinit var live: LiveVoice
    private lateinit var google: GoogleVoice
    private lateinit var clips: VoiceClips
    private lateinit var updater: Updater

    private lateinit var picture: WebView
    private var pictureReady = false
    private lateinit var statusView: TextView
    private lateinit var captionView: TextView
    private lateinit var problemView: TextView
    private lateinit var receipt: LinearLayout
    private lateinit var receiptText: TextView
    private val hideReceipt = Runnable { receipt.visibility = View.GONE }
    private var quietListens = 0
    private var catchingUp = false
    private lateinit var chatInput: EditText
    private lateinit var updateCard: LinearLayout
    private lateinit var updateTitle: TextView
    private lateinit var updateSub: TextView
    private lateinit var updateButton: TextView

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var hindi = false
    private var recogniser: SpeechRecognizer? = null
    private var inCall = false
    private var typed = false
    private var state = IDLE
    private var utterance = 0
    private var afterSpeech: Runnable? = null
    private var pendingCaption = ""
    private var lastPartial = ""
    private var softErrors = 0
    private var levelAt = 0L
    private val chat = mutableListOf<Pair<String, String>>()     // the conversation the AI sees (role, text)

    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val taps = mutableListOf<Long>()
    private var singleTap: Runnable? = null
    private var zUntil = 0L

    // ------------------------------------------------------------------ screen ---

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        val dp = resources.displayMetrics.density
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        memory = Memory(File(filesDir, "memory.json"))
        brainAi = Brain(prefs)
        live = LiveVoice(this)
        google = GoogleVoice(this)
        clips = VoiceClips(this)
        updater = Updater(this)
        moveOldKeys()
        moveOldHisaab()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        picture = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { pictureReady = true; show(state) }
            }
            loadUrl("file:///android_asset/home.html")
            contentDescription = "Tap to talk"
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; moved = false }
                    MotionEvent.ACTION_MOVE -> if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > 12 * dp) moved = true
                    MotionEvent.ACTION_UP -> if (!moved) tapped() else if (System.currentTimeMillis() < zUntil) { zUntil = 0; openSettings() }
                }
                true
            }
        }
        root.addView(picture, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        top.addView(TextView(this).apply { text = "Smart Book"; textSize = 20f; setTextColor(Sheet.TEXT); typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(-2, -2))
        statusView = TextView(this).apply { textSize = 13f }
        top.addView(statusView, LinearLayout.LayoutParams(-2, -2))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = (28 * dp).toInt() })

        problemView = TextView(this).apply { textSize = 12f; setTextColor(Sheet.AMBER); gravity = Gravity.CENTER }
        root.addView(problemView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins((24 * dp).toInt(), 0, (24 * dp).toInt(), (196 * dp).toInt()) })

        // what was just written in the memory book, with Undo
        receipt = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding((14 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
            background = GradientDrawable().apply { setColor(0x1A3DDC84); cornerRadius = 16 * dp; setStroke(maxOf(1, dp.toInt()), 0x333DDC84) }
        }
        receiptText = TextView(this).apply { setTextColor(0xFFB8F0D0.toInt()); textSize = 13f; maxLines = 3; ellipsize = TextUtils.TruncateAt.END; maxWidth = (240 * dp).toInt() }
        receipt.addView(receiptText, LinearLayout.LayoutParams(-2, -2))
        receipt.addView(TextView(this).apply {
            text = "Undo"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Sheet.TEXT); gravity = Gravity.CENTER
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { undoLast() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (6 * dp).toInt() })
        root.addView(receipt, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (140 * dp).toInt() })

        captionView = TextView(this).apply {
            setTextColor(0xFFC9C9D1.toInt()); textSize = 13f; gravity = Gravity.CENTER; maxLines = 3
            ellipsize = TextUtils.TruncateAt.END; maxWidth = (300 * dp).toInt()
            setPadding((14 * dp).toInt(), (7 * dp).toInt(), (14 * dp).toInt(), (7 * dp).toInt())
            background = GradientDrawable().apply { setColor(0x0FFFFFFF); cornerRadius = 14 * dp; setStroke(maxOf(1, dp.toInt()), 0x14FFFFFF) }
            visibility = View.GONE
        }
        root.addView(captionView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (88 * dp).toInt() })

        buildChatBar(root, dp)
        buildUpdateCard(root, dp)
        setContentView(root)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        show(IDLE)
        tts = TextToSpeech(this, this, "com.google.android.tts")
        main.postDelayed({ checkUpdate() }, 2500)
        main.postDelayed({ catchUp() }, 4000)
        Remind.sync(this, memory)
    }

    override fun onDestroy() {
        hangUp()
        recogniser?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun show(s: Int) {
        state = s
        val color = when (s) { LISTENING -> Sheet.GREEN; SPEAKING -> Sheet.AMBER; THINKING -> Sheet.BLUE; else -> Sheet.SUB }
        statusView.setTextColor(color)
        statusView.text = when (s) { LISTENING -> "●  Listening…"; SPEAKING -> "●  Speaking"; THINKING -> "●  Thinking…"; else -> "●  Ready" }
        if (pictureReady) picture.evaluateJavascript("show('" + (when (s) { LISTENING -> "listening"; SPEAKING -> "speaking"; else -> "idle" }) + "')", null)
    }

    private fun caption(text: String) {
        captionView.text = text
        captionView.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** One tap talks (after a moment, to tell it from a triple tap); three quick taps arm the settings stroke. */
    private fun tapped() {
        val now = System.currentTimeMillis()
        taps.removeAll { now - it > 700 }
        taps += now
        singleTap?.let { main.removeCallbacks(it) }
        if (taps.size >= 3) { taps.clear(); zUntil = now + 4000; return }
        if (taps.size == 1) { singleTap = Runnable { taps.clear(); onTap() }.also { main.postDelayed(it, 250) } }
    }

    private fun onTap() {
        when {
            !inCall -> if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startCall()
                       else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), ASK_MIC)
            state == SPEAKING -> { stopSpeaking(); listen() }          // cut in while it is talking
            else -> hangUp()
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        if (code == ASK_MIC && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startCall()
        else if (code == ASK_MIC) problemView.text = "Allow the microphone for Smart Book in the phone's settings"
    }

    // ------------------------------------------------------------------- call ---

    private fun startCall() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { problemView.text = "Install the Google app for speech recognition"; return }
        if (recogniser == null) recogniser = SpeechRecognizer.createSpeechRecognizer(this).also { it.setRecognitionListener(this) }
        inCall = true
        softErrors = 0
        quietListens = 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        say("Ji?", "جی؟") { listen() }
    }

    private fun hangUp() {
        inCall = false
        typed = false
        recogniser?.cancel()
        stopSpeaking()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show(IDLE)
    }

    private fun listen() {
        if (!inCall) { if (typed) { typed = false; show(IDLE) }; return }
        show(LISTENING)
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2500L)
        }
        lastPartial = ""
        recogniser?.startListening(i)
    }

    override fun onResults(results: Bundle) {
        if (!inCall) return
        softErrors = 0
        quietListens = 0
        val heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        if (heard.isEmpty()) listen() else handle(heard)
    }

    override fun onPartialResults(partial: Bundle) {
        partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { lastPartial = it }
    }

    override fun onError(error: Int) {
        if (!inCall || state != LISTENING) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (lastPartial.isNotEmpty()) { val p = lastPartial; lastPartial = ""; quietListens = 0; handle(listOf(p)) }      // a soft voice it gave up on
                else if (++quietListens >= 4) hangUp()                     // nobody has spoken for a while: the call ends by itself
                else main.postDelayed({ listen() }, 250)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                if (++softErrors > 5) { problemView.text = "The microphone is busy · another app may be using it"; hangUp(); return }
                recogniser?.cancel(); main.postDelayed({ listen() }, 600)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { problemView.text = "Allow the microphone for Smart Book"; hangUp() }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                problemView.text = "No internet"; hangUp()
            }
            else -> { problemView.text = "Speech recognition is not available in Urdu · Google app › Voice › download Urdu"; hangUp() }
        }
    }

    override fun onRmsChanged(rms: Float) {
        val now = System.currentTimeMillis()
        if (!pictureReady || state != LISTENING || now - levelAt < 60) return
        levelAt = now
        picture.evaluateJavascript("level(" + ((rms + 2f) / 12f).coerceIn(0f, 1f) + ")", null)
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    // ------------------------------------------------------------------ brain ---

    /** Everything said or typed goes to the one brain, with the memory book; its answer is kept and spoken. */
    private fun handle(heard: List<String>) {
        caption("")
        if (!brainAi.ready()) {
            say("Pehle Settings mein AI key daalein.", "پہلے سیٹنگز میں اے آئی کی ڈالیں۔") { listen() }
            return
        }
        show(THINKING)
        val now = System.currentTimeMillis()
        val waiting = if (catchingUp) JSONArray() else pending()
        var user = if (heard.size == 1) heard[0] else heard[0] + "\n(other ways it may have been heard: " + heard.drop(1).joinToString(" | ") + ")"
        if (waiting.length() > 0) user = earlier(waiting) + "\n\nNow: " + user
        val messages = chat.toList() + ("user" to user)
        val asked: Future<String?> = net.submit<String?> { brainAi.ask(systemFor(heard.joinToString(" "), now), messages) }
        Thread {
            val answer = try { asked.get(25, TimeUnit.SECONDS) } catch (e: Exception) { null }
            main.post { if (inCall || typed) answered(heard[0], answer, now, waiting.length()) else if (answer != null) keepQuietly(answer, now, waiting.length()) }
        }.start()
    }

    private fun systemFor(said: String, now: Long): String {
        val older = memory.relevant(said)
        return SYSTEM + "\n\nTOTALS (exact, use these numbers):\n" + memory.summary(now) + "\n\nMEMORY BOOK (newest first):\n" + memory.recent() +
            (if (older.isEmpty()) "" else "\n\nOLDER RECORDS THAT MAY BE ABOUT THIS:\n$older")
    }

    // ----------------------------------------------------- nothing gets lost ---

    /** Sentences said while no AI could answer; they are written down when one answers again. */
    private fun pending(): JSONArray = runCatching { JSONArray(prefs.getString("pending", "[]")) }.getOrDefault(JSONArray())

    private fun keep(text: String, now: Long) {
        val p = pending()
        p.put(JSONObject().put("text", text).put("at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(now))))
        while (p.length() > 30) p.remove(0)
        prefs.edit().putString("pending", p.toString()).apply()
    }

    /** Drops the first [n] kept sentences: they have been written down. */
    private fun dropKept(n: Int) {
        if (n <= 0) return
        val p = pending(); repeat(minOf(n, p.length())) { p.remove(0) }
        prefs.edit().putString("pending", p.toString()).apply()
    }

    private fun earlier(p: JSONArray) = "Earlier, while you could not answer, I said these. Keep what is worth keeping from them, dated when I said them; " +
        "do not answer them one by one:\n" + (0 until p.length()).joinToString("\n") { "- " + p.getJSONObject(it).optString("at") + ": " + p.getJSONObject(it).optString("text") }

    /** Writes down the kept sentences quietly, when the app opens and an AI is there. */
    private fun catchUp() {
        val p = pending()
        if (p.length() == 0 || catchingUp || inCall || typed || !brainAi.ready()) return
        catchingUp = true
        val now = System.currentTimeMillis()
        net.execute {
            val answer = brainAi.ask(systemFor("", now), listOf("user" to earlier(p)))
            main.post { catchingUp = false; if (answer != null) keepQuietly(answer, now, p.length()) }
        }
    }

    private fun keepQuietly(answer: String, now: Long, kept: Int) {
        val o = Brain.jsonIn(answer) ?: return
        dropKept(kept)
        if (memory.apply(o, now) > 0) written()
    }

    /** After the book changed: back it up, set reminders, and show what was written. */
    private fun written() {
        backup()
        Remind.sync(this, memory)
        if (memory.added.any { it.at.isNotEmpty() } && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFY)
        val lines = memory.added.map { "✓ " + it.short() + (if (it.at.isNotEmpty()) " · ⏰ " + it.at.drop(5) else "") } +
            memory.changed.map { "✎ " + it.short() } + memory.removed.map { "✕ " + it.short() }
        if (lines.isEmpty()) return
        receiptText.text = lines.take(3).joinToString("\n") + (if (lines.size > 3) "\n+" + (lines.size - 3) + " more" else "")
        receipt.visibility = View.VISIBLE
        main.removeCallbacks(hideReceipt)
        main.postDelayed(hideReceipt, 9000)
    }

    private fun undoLast() {
        main.removeCallbacks(hideReceipt)
        receipt.visibility = View.GONE
        if (memory.undo()) {
            backup(); Remind.sync(this, memory)
            chat += "user" to "(I pressed Undo: the last change to the memory book was taken back.)"
            chat += "assistant" to "{\"reply\": \"Theek hai.\"}"
            problemView.text = "Undone"
            main.postDelayed({ if (problemView.text == "Undone") problemView.text = "" }, 2500)
        }
    }

    private fun answered(heard: String, answer: String?, now: Long, kept: Int) {
        val o = answer?.let { Brain.jsonIn(it) }
        if (o == null) {
            problemView.text = if (answer == null) brainAi.lastProblem.ifEmpty { "The AI did not answer in time" } else "The AI's answer could not be read"
            keep(heard, now)                     // nothing is lost: it is written down when an AI answers again
            say("Abhi jawab nahi mila. Baat rakh li hai.", "ابھی جواب نہیں ملا۔ بات رکھ لی ہے۔") { listen() }
            return
        }
        problemView.text = ""
        dropKept(kept)
        val changed = memory.apply(o, now)
        if (changed > 0) written()
        var ro = o.optString("reply").trim().ifEmpty { if (changed > 0) "Likh liya." else "Ji." }
        var ur = o.optString("urdu").trim()
        if (memory.skipped > 0 && changed == 0 && !ro.contains("?")) { ro = "Kitne rupay?"; ur = "کتنے روپے؟" }   // never "likh liya" for nothing
        if (o.optBoolean("show_book")) openBook()
        chat += "user" to heard
        chat += "assistant" to JSONObject().put("reply", ro).toString()
        while (chat.size > 16) chat.removeAt(0)
        appendLog(heard, ro)
        if (o.optBoolean("report")) { makeReport(ro, ur); return }
        say(ro, ur.ifEmpty { ro }) { listen() }
    }

    // ------------------------------------------------------------------ voice ---

    /** A natural voice from the internet first (Google or ElevenLabs, in the chosen order), then the phone's own. */
    private fun say(ro: String, ur: String, then: (() -> Unit)?) {
        show(SPEAKING)
        afterSpeech = then?.let { Runnable { it() } }
        pendingCaption = ro
        val id = (++utterance).toString()
        val phone = Runnable { sayPhone(ro, ur, id) }
        if (prefs.getBoolean("googleFirst", true)) sayGoogle(ro, ur, id) { sayEleven(ro, id) { phone.run() } }
        else sayEleven(ro, id) { sayGoogle(ro, ur, id) { phone.run() } }
    }

    private fun current(id: String) = id == utterance.toString()

    private fun spoken(id: String) {
        if (!current(id)) return
        val next = afterSpeech
        afterSpeech = null
        if (next != null) next.run() else if (!inCall) show(IDLE)
    }

    private fun stopSpeaking() {
        utterance++
        afterSpeech = null
        clips.stopPlaying()
        live.stop()
        tts?.stop()
    }

    private fun showCaption() { caption(pendingCaption) }

    private fun sayEleven(ro: String, id: String, otherwise: () -> Unit) {
        val key = prefs.getString("elevenKey", "").orEmpty()
        val voice = prefs.getString("elevenVoice", "").orEmpty()
        if (!prefs.getBoolean("liveVoice", true) || key.isEmpty() || voice.isEmpty()) { otherwise(); return }
        live.speak(key, voice, prefs.getString("elevenModel", ElevenLabs.MODEL).orEmpty(), live.text(ro), object : LiveVoice.Listener {
            override fun started() { main.post { if (current(id)) showCaption() } }
            override fun done() { main.post { spoken(id) } }
            override fun failed(why: String) { main.post { if (current(id)) { if (why.isNotEmpty()) problemView.text = "ElevenLabs: $why"; otherwise() } } }
        })
    }

    private fun sayGoogle(ro: String, ur: String, id: String, otherwise: () -> Unit) {
        val key = prefs.getString("googleKey", "").orEmpty()
        val voice = prefs.getString("googleVoice", "").orEmpty()
        if (!prefs.getBoolean("googleOn", true) || key.isEmpty() || voice.isEmpty()) { otherwise(); return }
        val text = if (voice.startsWith("ur")) ur else live.text(ro)
        voiceNet.execute {
            val said = google.say(key, voice, text)
            main.post {
                if (!current(id)) return@post
                val pcm = said.pcm
                if (pcm == null) { problemView.text = "Google voice: " + said.problem; otherwise(); return@post }
                showCaption()
                clips.playPcm(pcm) { spoken(id) }
            }
        }
    }

    // the phone's own voice: the best installed voice, a touch slow, a breath between sentences
    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val urdu = listOf(Locale("ur", "PK"), Locale("ur", "IN")).firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
        val hi = Locale("hi", "IN")
        hindi = prefs.getString("voiceLang", "") == "hi" && t.isLanguageAvailable(hi) >= TextToSpeech.LANG_AVAILABLE || urdu == null
        t.language = if (hindi) hi else urdu
        bestVoice(if (hindi) "hi" else "ur")
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) { main.post { showCaption() } }
            override fun onDone(id: String) { main.post { spoken(id) } }
            @Deprecated("old engines") override fun onError(id: String) { main.post { spoken(id) } }
        })
        ttsReady = true
    }

    private fun phoneVoices(lang: String): List<Voice> = runCatching {
        tts!!.voices.filter { it.locale.language == lang && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { if (it.isNetworkConnectionRequired) 0 else 1 }.thenBy { it.latency })
    }.getOrDefault(emptyList())

    private fun bestVoice(lang: String) {
        val t = tts ?: return
        val chosen = prefs.getString("voice", "")
        val list = phoneVoices(lang)
        (list.firstOrNull { it.name == chosen } ?: list.firstOrNull())?.let { runCatching { t.voice = it } }
        t.setSpeechRate(prefs.getFloat("ttsRate", 0.95f))
        t.setPitch(prefs.getFloat("ttsPitch", 1.0f))
    }

    private fun sayPhone(ro: String, ur: String, id: String) {
        val t = tts
        if (!ttsReady || t == null) { showCaption(); main.postDelayed({ spoken(id) }, 300); return }
        val line = if (hindi) Devanagari.fromRoman(LiveVoice.spokenNumbers(ro)) else ur
        val parts = line.trim().split(Regex("(?<=[.!?۔।؟])\\s+")).filter { it.isNotBlank() }
        if (parts.size <= 1) { t.speak(line, TextToSpeech.QUEUE_FLUSH, null, id); return }
        parts.forEachIndexed { i, p ->
            val last = i == parts.size - 1
            t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (last) id else id + "_" + i)
            if (!last) t.playSilentUtterance(180, TextToSpeech.QUEUE_ADD, id + "_s" + i)
        }
    }

    // ------------------------------------------------------------------- chat ---

    private fun buildChatBar(root: FrameLayout, dp: Float) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
            background = Sheet.round(this@MainActivity, Sheet.GROUP, 24f, Sheet.LINE)
        }
        chatInput = EditText(this).apply {
            hint = "Type a message…"; setHintTextColor(Sheet.DIM); setTextColor(Sheet.TEXT); textSize = 15f
            background = null; setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEND; setPadding(0, 0, 0, 0)
            setOnEditorActionListener { _, _, _ -> sendTyped(); true }
        }
        bar.addView(chatInput, LinearLayout.LayoutParams(0, (44 * dp).toInt(), 1f))
        bar.addView(TextView(this).apply {
            text = "↑"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(0xFF0D0D0F.toInt())
            contentDescription = "Send"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Sheet.TEXT) }
            setOnClickListener { sendTyped() }
        }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
        root.addView(bar, FrameLayout.LayoutParams(minOf((520 * dp).toInt(), resources.displayMetrics.widthPixels - (32 * dp).toInt()), -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (20 * dp).toInt() })
    }

    private fun sendTyped() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        chatInput.setText("")
        stopSpeaking()
        if (inCall) recogniser?.cancel() else typed = true
        handle(listOf(text))
    }

    // ----------------------------------------------------------------- memory ---

    /** The memory book's copy in Downloads, kept up to date: it survives uninstalling the app. */
    private fun backup() {
        if (Build.VERSION.SDK_INT < 29) return
        val text = memory.json()
        net.execute {
            runCatching {
                val known = prefs.getString("memoryUri", null)
                if (known != null) runCatching { contentResolver.openOutputStream(Uri.parse(known), "wt")?.use { it.write(text.toByteArray()); return@execute } }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "smart-book-memory.json"); put(MediaStore.Downloads.MIME_TYPE, "application/json")
                }) ?: return@execute
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                prefs.edit().putString("memoryUri", uri.toString()).apply()
            }
        }
    }

    private fun appendLog(heard: String, said: String) {
        runCatching {
            File(filesDir, "talk.txt").appendText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date()) + " | " + heard + " | " + said + "\n")
        }
    }

    /** The old app's hisaab (BolKhata's ledger) moved into the memory book once. */
    private fun moveOldHisaab() {
        if (prefs.getBoolean("movedToMemory", false)) return
        prefs.edit().putBoolean("movedToMemory", true).apply()
        val raw = getSharedPreferences("bolkhata", MODE_PRIVATE).getString("entries", null) ?: return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        val add = JSONArray()
        val kinds = mapOf("KHARCHA" to "expense", "AAMDANI" to "income", "DIYA" to "gave", "LIYA" to "took", "WAPIS_MILA" to "got_back",
            "WAPIS_DIYA" to "paid_back", "SALE" to "sale", "PURCHASE" to "purchase", "TRANSFER_IN" to "income", "TRANSFER_OUT" to "expense")
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            add.put(JSONObject().put("kind", kinds[o.optString("type")] ?: "note").put("amount", o.optDouble("amount", 0.0))
                .put("person", o.optString("person")).put("item", o.optString("note").ifBlank { o.optString("item") })
                .put("date", Memory.day(o.optLong("date", System.currentTimeMillis()))).put("text", o.optString("note")))
        }
        if (memory.apply(JSONObject().put("add", add), System.currentTimeMillis()) > 0) backup()
    }

    /** Keys from before the per-service boxes. */
    private fun moveOldKeys() {
        val old = prefs.getString("groqKey", "").orEmpty().trim()
        if (old.isEmpty()) return
        val s = Brain.serviceFor(old) ?: Brain.SERVICES.last()
        val e = prefs.edit().remove("groqKey").remove("groqModel")
        if (prefs.getString(s.slot, "").isNullOrEmpty()) e.putString(s.slot, old)
        e.apply()
    }

    private fun makeReport(ro: String, ur: String) {
        var where: String? = null
        var done = false
        Thread { val w = runCatching { Report.make(this, memory) }.getOrNull(); main.post { where = w; done = true } }.start()
        say(ro.ifEmpty { "Ek minute." }, ur.ifEmpty { "ایک منٹ۔" }) {
            fun after() {
                if (!done) { main.postDelayed({ after() }, 300); return }
                if (where != null) say("Report Downloads mein.", "رپورٹ ڈاؤن لوڈز میں۔") { listen() }
                else say("Report nahi bani.", "رپورٹ نہیں بنی۔") { listen() }
            }
            after()
        }
    }

    // --------------------------------------------------------------- settings ---

    private fun openSettings() {
        if (inCall) hangUp()
        val p = Sheet.open(this, "Settings", null, null)
        val g = Sheet.group(p.body)
        val on = Brain.SERVICES.count { brainAi.key(it).isNotEmpty() && prefs.getBoolean("on_" + it.slot, true) }
        val ok = Brain.SERVICES.count { brainAi.key(it).isNotEmpty() && prefs.getBoolean("on_" + it.slot, true) && prefs.getString("st_" + it.slot, "") == "ok" }
        Sheet.row(g, "AI", 0xFFD9A679.toInt(), "Brain", if (on == 0) "No AI key yet · needed" else "$ok of $on working", "›", 0xFF5D5D66.toInt(), null) { p.dialog.dismiss(); openBrain() }
        val gSw = Sheet.Toggle(this, prefs.getBoolean("googleOn", true))
        val gRow = Sheet.row(g, "G", 0xFF8AB4F8.toInt(), "Google voice", "", null, 0, gSw) { p.dialog.dismiss(); openGoogleVoice() }
        val gPaint = {
            val v = prefs.getString("googleVoice", "").orEmpty()
            val set = prefs.getString("googleKey", "").orEmpty().isNotEmpty()
            if (!gSw.on) gRow.status("Off", Sheet.DIM) else gRow.status(if (!set) "Not set up · tap to add a key" else v.ifEmpty { "No voice chosen" }, if (set && v.isNotEmpty()) Sheet.GREEN else Sheet.DIM)
        }
        gPaint(); gSw.onChange = Sheet.OnFlag { prefs.edit().putBoolean("googleOn", it).apply(); gPaint() }
        val eSw = Sheet.Toggle(this, prefs.getBoolean("liveVoice", true))
        val eRow = Sheet.row(g, "11", 0xFF9AA6FF.toInt(), "ElevenLabs", "", null, 0, eSw) { p.dialog.dismiss(); openEleven() }
        val ePaint = {
            val v = prefs.getString("elevenVoiceName", "").orEmpty().ifEmpty { prefs.getString("elevenVoice", "").orEmpty() }
            if (!eSw.on) eRow.status("Off", Sheet.DIM) else eRow.status(if (v.isEmpty()) "Not set up · tap to add a key" else v.split(" ")[0] + " · " + modelName(), if (v.isEmpty()) Sheet.DIM else Sheet.GREEN)
        }
        ePaint(); eSw.onChange = Sheet.OnFlag { prefs.edit().putBoolean("liveVoice", it).apply(); live.stop(); ePaint() }
        Sheet.row(g, "Aa", 0xFFC7C7D1.toInt(), "Phone voice", (if (hindi) "Hindi" else "Urdu") + " · used when offline", "›", 0xFF5D5D66.toInt(), null) { p.dialog.dismiss(); openPhoneVoice() }
        val g2 = Sheet.group(p.body)
        Sheet.row(g2, "M", 0xFF7FD4C1.toInt(), "Memory book", memory.recs.size.toString() + " things remembered", "›", 0xFF5D5D66.toInt(), null) { p.dialog.dismiss(); openBook(true) }
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrDefault("?")
        Sheet.row(g2, "i", 0xFFC7C7D1.toInt(), "Smart Book $ver", "Tap to talk · type below · 3 taps + Z for this screen", null, 0, null, null)
        Sheet.primary(p.body, "Done") { p.dialog.dismiss() }
        p.dialog.show()
    }

    private fun openBrain() {
        val p = Sheet.open(this, "Brain", "One AI hears everything · tried top to bottom", Runnable { openSettings() })
        val g = Sheet.group(p.body)
        val hints = listOf("sk-ant-...", "sk-or-...", "AIza...", "gsk_...")
        val tints = listOf(0xFFD9A679, 0xFF9AA6FF, 0xFF7FD4C1, 0xFFF0A3A3).map { it.toInt() }
        var openAt = Brain.SERVICES.indexOfFirst { brainAi.key(it).isEmpty() }.let { if (it < 0) 0 else it }
        Brain.SERVICES.forEachIndexed { i, s ->
            val sw = Sheet.Toggle(this, prefs.getBoolean("on_" + s.slot, true))
            lateinit var keyLine: LinearLayout
            val row = Sheet.row(g, s.name.take(1), tints[i], s.name, "", null, 0, sw) { keyLine.visibility = if (keyLine.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            val paint = { row.status(brainAi.statusText(s), brainAi.statusColor(s)); row.view.alpha = if (prefs.getBoolean("on_" + s.slot, true)) 1f else 0.55f }
            paint()
            keyLine = Sheet.keyLine(g, hints[i], brainAi.key(s), true, true, Sheet.OnText { text ->
                val e = prefs.edit().putString(s.slot, text).putBoolean("on_" + s.slot, true)
                sw.set(true)
                when {
                    text.isEmpty() -> { e.remove("st_" + s.slot).apply(); paint() }
                    Brain.serviceFor(text) != s -> { e.putString("st_" + s.slot, "wrong").apply(); paint() }
                    else -> {
                        e.putString("st_" + s.slot, "checking").apply(); paint()
                        net.execute { brainAi.askOne(s, text, "Reply with JSON only: {\"reply\": \"ok\"}", listOf("user" to "salam")); main.post { paint() } }
                    }
                }
            })
            keyLine.visibility = if (i == openAt) View.VISIBLE else View.GONE
            sw.onChange = Sheet.OnFlag { prefs.edit().putBoolean("on_" + s.slot, it).apply(); paint() }
        }
        Sheet.note(p.body, "Tap a name to enter its key")
        Sheet.primary(p.body, "OK") { p.dialog.dismiss(); problemView.text = ""; openSettings() }
        p.dialog.show()
    }

    private val modelIds = listOf("eleven_turbo_v2_5", "eleven_flash_v2_5", "eleven_multilingual_v2", "eleven_v3", "eleven_v4")
    private val modelNames = listOf("Turbo v2.5", "Flash v2.5", "Multilingual v2", "v3", "v4")
    private val modelNotes = listOf("Fast and good", "Fastest · fewest credits", "Clearest · a little slower", "Expressive · slower", "Newest")
    private fun modelName() = modelIds.indexOf(prefs.getString("elevenModel", ElevenLabs.MODEL)).let { if (it < 0) prefs.getString("elevenModel", "").orEmpty() else modelNames[it] }

    private fun openEleven() {
        val p = Sheet.open(this, "ElevenLabs", "Natural voice while online", Runnable { openSettings() })
        val g = Sheet.group(p.body)
        lateinit var st: Sheet.Row
        val check = {
            val k = prefs.getString("elevenKey", "").orEmpty()
            if (k.isEmpty()) st.status("No key", Sheet.DIM) else {
                st.status("Checking…", Sheet.BLUE)
                Thread { val got = ElevenLabs.credits(k); main.post {
                    val left = got.first
                    when { left == null -> st.status(got.second, Sheet.RED); left < 0 -> st.status(got.second, Sheet.GREEN)
                        else -> st.status("Working · " + Memory.money(left.toDouble()) + " characters left", Sheet.GREEN) }
                } }.start()
            }
        }
        Sheet.keyLine(g, "sk_... (ElevenLabs key)", prefs.getString("elevenKey", "").orEmpty(), true, false, Sheet.OnText { prefs.edit().putString("elevenKey", it).apply(); check() })
        st = Sheet.statusLine(g)
        check()
        Sheet.keyLine(g, "Voice ID", prefs.getString("elevenVoice", "").orEmpty(), false, false, Sheet.OnText {
            prefs.edit().putString("elevenVoice", it).putString("elevenVoiceName", "").apply()
            st.status(if (it.isEmpty()) "No voice" else "Voice saved", if (it.isEmpty()) Sheet.AMBER else Sheet.GREEN)
        })
        Sheet.row(g, null, 0, "Choose from my voices", prefs.getString("elevenVoiceName", "").orEmpty().ifEmpty { "Lists the voices on this key" }, "›", 0xFF5D5D66.toInt(), null) {
            val k = prefs.getString("elevenKey", "").orEmpty()
            if (k.isEmpty()) { st.status("Save a key first", Sheet.AMBER); return@row }
            st.status("Loading voices…", Sheet.BLUE)
            Thread { val got = ElevenLabs.voices(k); main.post {
                if (got.first.isEmpty()) { st.status(got.second.ifEmpty { "No voices · paste a Voice ID above" }, Sheet.RED); return@post }
                p.dialog.dismiss()
                val vp = Sheet.open(this, "Choose a voice", null, Runnable { openEleven() })
                val vg = Sheet.group(vp.body)
                got.first.forEach { v -> Sheet.row(vg, null, 0, v.name, null, null, 0, null) {
                    prefs.edit().putString("elevenVoice", v.id).putString("elevenVoiceName", v.name).apply(); vp.dialog.dismiss(); openEleven()
                } }
                vp.dialog.show()
            } }.start()
        }
        Sheet.label(p.body, "MODEL")
        val mg = Sheet.group(p.body)
        val cur = prefs.getString("elevenModel", ElevenLabs.MODEL)
        val radios = modelIds.map { Sheet.Radio(this, it == cur) }
        modelIds.forEachIndexed { i, id -> Sheet.row(mg, null, 0, modelNames[i], modelNotes[i], null, 0, radios[i]) {
            prefs.edit().putString("elevenModel", id).apply(); radios.forEachIndexed { j, r -> r.set(j == i) }
        } }
        Sheet.primary(p.body, "OK") { p.dialog.dismiss(); openSettings(); say("Ji, main Smart Book hoon.", "جی، میں اسمارٹ بک ہوں۔", null) }
        p.dialog.show()
    }

    private fun openGoogleVoice() {
        val p = Sheet.open(this, "Google voice", "Google Cloud Text-to-Speech · natural, large free allowance", Runnable { openSettings() })
        val g = Sheet.group(p.body)
        lateinit var st: Sheet.Row
        val pick = {
            val k = prefs.getString("googleKey", "").orEmpty()
            if (k.isEmpty()) st.status("Save a key first", Sheet.AMBER) else {
                st.status("Loading voices…", Sheet.BLUE)
                Thread { val got = google.voices(k); main.post {
                    if (got.first.isEmpty()) { st.status(got.second.ifEmpty { "No Urdu or Hindi voices" }, Sheet.RED); return@post }
                    p.dialog.dismiss()
                    val vp = Sheet.open(this, "Choose a voice", "Tap one to hear it · most natural first", Runnable { openGoogleVoice() })
                    val vg = Sheet.group(vp.body)
                    got.first.forEach { v -> Sheet.row(vg, null, 0, (if (v.lang.startsWith("ur")) "Urdu" else "Hindi") + " · " + v.kind + " · " + v.gender, v.name, null, 0, null) {
                        prefs.edit().putString("googleVoice", v.name).apply(); say("Ji, main Smart Book hoon.", "جی، میں اسمارٹ بک ہوں۔", null)
                    } }
                    Sheet.primary(vp.body, "OK") { vp.dialog.dismiss(); openGoogleVoice() }
                    vp.dialog.show()
                } }.start()
            }
        }
        Sheet.keyLine(g, "AIza... (Google Cloud API key)", prefs.getString("googleKey", "").orEmpty(), true, false, Sheet.OnText { prefs.edit().putString("googleKey", it).apply(); pick() })
        st = Sheet.statusLine(g)
        st.status(if (prefs.getString("googleKey", "").isNullOrEmpty()) "No key" else "Key saved", Sheet.SUB)
        Sheet.row(g, null, 0, "Voice", prefs.getString("googleVoice", "").orEmpty().ifEmpty { "Not chosen" }, "Change", Sheet.AMBER, null) { pick() }
        val g2 = Sheet.group(p.body)
        val first = Sheet.Toggle(this, prefs.getBoolean("googleFirst", true))
        first.onChange = Sheet.OnFlag { prefs.edit().putBoolean("googleFirst", it).apply() }
        Sheet.row(g2, null, 0, "Before ElevenLabs", "Saves ElevenLabs credits", null, 0, first, null)
        Sheet.note(p.body, "Key: console.cloud.google.com › enable Text-to-Speech API › Credentials › API key")
        Sheet.primary(p.body, "OK") { p.dialog.dismiss(); openSettings() }
        p.dialog.show()
    }

    private fun openPhoneVoice() {
        val p = Sheet.open(this, "Phone voice", "Used when there is no internet voice", Runnable { openSettings() })
        val test = { stopSpeaking(); sayPhone("Ji, main Smart Book hoon. Aaj ka hisaab bataoon?", "جی، میں اسمارٹ بک ہوں۔ آج کا حساب بتاؤں؟", (++utterance).toString()) }
        Sheet.label(p.body, "LANGUAGE")
        val lg = Sheet.group(p.body)
        listOf("ur" to "Urdu", "hi" to "Hindi").forEach { (code, name) ->
            Sheet.row(lg, null, 0, name, if (code == "hi") "Often smoother · same spoken language" else "Urdu letters", null, 0, Sheet.Radio(this, (code == "hi") == hindi)) {
                prefs.edit().putString("voiceLang", code).remove("voice").apply(); onInit(TextToSpeech.SUCCESS); p.dialog.dismiss(); openPhoneVoice(); test()
            }
        }
        Sheet.label(p.body, "VOICE · BEST FIRST")
        val vg = Sheet.group(p.body)
        val voices = phoneVoices(if (hindi) "hi" else "ur").take(8)
        val cur = runCatching { tts?.voice?.name }.getOrNull()
        val marks = voices.map { Sheet.Radio(this, it.name == cur) }
        voices.forEachIndexed { i, v ->
            val q = if (v.quality >= Voice.QUALITY_VERY_HIGH) "Very high" else if (v.quality >= Voice.QUALITY_HIGH) "High" else "Normal"
            Sheet.row(vg, null, 0, "Voice " + (i + 1), q + " quality · " + (if (v.isNetworkConnectionRequired) "online" else "offline"), null, 0, marks[i]) {
                runCatching { tts?.voice = v }; prefs.edit().putString("voice", v.name).apply(); marks.forEachIndexed { j, r -> r.set(j == i) }; test()
            }
        }
        if (voices.isEmpty()) Sheet.row(vg, null, 0, "No voices listed", "Phone settings › Text-to-speech › install Urdu or Hindi", null, 0, null, null)
        Sheet.label(p.body, "SPEED")
        val sg = Sheet.group(p.body)
        val rates = listOf(0.85f to "Slow", 0.95f to "Natural", 1.08f to "Fast")
        val rm = rates.map { Sheet.Radio(this, kotlin.math.abs(prefs.getFloat("ttsRate", 0.95f) - it.first) < 0.01f) }
        rates.forEachIndexed { i, r -> Sheet.row(sg, null, 0, r.second, null, null, 0, rm[i]) {
            prefs.edit().putFloat("ttsRate", r.first).apply(); tts?.setSpeechRate(r.first); rm.forEachIndexed { j, m -> m.set(j == i) }; test()
        } }
        Sheet.primary(p.body, "OK") { p.dialog.dismiss(); openSettings() }
        p.dialog.show()
    }

    private val kindNames = mapOf("expense" to "Spent", "income" to "Earned", "gave" to "Gave", "took" to "Borrowed", "got_back" to "Got back",
        "paid_back" to "Paid back", "sale" to "Sold", "purchase" to "Bought", "note" to "Note", "task" to "To do")

    /** The whole memory book: today's and this month's totals, search, kinds, every record by day; tap twice to delete. */
    private fun openBook(fromSettings: Boolean = false) {
        val now = System.currentTimeMillis()
        val today = Memory.day(now)
        val yesterday = Memory.day(now - 86_400_000L)
        val month = today.take(7)
        fun spent(test: (String) -> Boolean) = memory.recs.filter { test(it.date) && (it.kind == "expense" || it.kind == "purchase") }.sumOf { it.amount }
        val waiting = pending().length()
        val p = Sheet.open(this, "Memory book", memory.recs.size.toString() + " things remembered" + (if (waiting > 0) " · $waiting waiting for the AI" else ""),
            if (fromSettings) Runnable { openSettings() } else null)
        Sheet.stats(p.body, Memory.money(spent { it == today }) to "Spent today", Memory.money(spent { it.startsWith(month) }) to "This month",
            memory.balances().count().toString() to "Open loans")

        val search = Sheet.field(this, "Search: a name, an item, a word…", "", false)
        p.body.addView(search, LinearLayout.LayoutParams(-1, Sheet.dp(this, 44f)).apply { bottomMargin = Sheet.dp(this@MainActivity, 10f) })
        val filters = listOf("All" to { _: Memory.Rec -> true }, "Money" to { r: Memory.Rec -> r.kind in Memory.MONEY },
            "Loans" to { r: Memory.Rec -> r.kind in setOf("gave", "took", "got_back", "paid_back") },
            "Notes" to { r: Memory.Rec -> r.kind == "note" }, "To do" to { r: Memory.Rec -> r.kind == "task" })
        var filter = 0
        var shown = 120
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipViews = mutableListOf<TextView>()
        p.body.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Sheet.dp(this@MainActivity, 10f) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.body.addView(list, LinearLayout.LayoutParams(-1, -2))

        lateinit var draw: () -> Unit
        draw = {
            list.removeAllViews()
            chipViews.forEachIndexed { i, c ->
                c.setTextColor(if (i == filter) 0xFF0D0D0F.toInt() else Sheet.SUB)
                c.background = Sheet.round(this, if (i == filter) Sheet.TEXT else Sheet.GROUP, 14f, if (i == filter) 0 else Sheet.LINE)
            }
            val q = search.text.toString().trim().lowercase(Locale.ROOT)
            val all = memory.recs.filter { filters[filter].second(it) }
                .filter { q.isEmpty() || (it.item + " " + it.person + " " + it.text + " " + Memory.money(it.amount)).lowercase(Locale.ROOT).contains(q) }
                .sortedWith(compareByDescending<Memory.Rec> { it.date }.thenByDescending { it.id })
            if (all.isEmpty()) {
                val g = Sheet.group(list)
                Sheet.row(g, null, 0, if (memory.recs.isEmpty()) "Nothing yet" else "Nothing found",
                    if (memory.recs.isEmpty()) "Say or type anything to keep it" else "Try another word", null, 0, null, null)
            }
            if (q.isNotEmpty() && all.any { it.kind in Memory.MONEY }) {
                val money = all.filter { it.kind == "expense" || it.kind == "purchase" }.sumOf { it.amount }
                if (money > 0) Sheet.note(list, "Spent on “$q”: " + Memory.money(money))
            }
            all.take(shown).groupBy { it.date }.forEach { (date, recs) ->
                Sheet.label(list, when (date) { today -> "TODAY"; yesterday -> "YESTERDAY"
                    else -> runCatching { java.text.SimpleDateFormat("EEE d MMM yyyy", Locale.US).format(java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!).uppercase(Locale.US) }.getOrDefault(date) })
                val g = Sheet.group(list)
                recs.forEach { r ->
                    val (icon, color) = when (r.kind) {
                        "expense", "purchase" -> "−" to Sheet.RED
                        "income", "sale" -> "+" to Sheet.GREEN
                        "gave", "paid_back" -> "↗" to Sheet.AMBER
                        "took", "got_back" -> "↙" to Sheet.BLUE
                        "task" -> "☐" to 0xFFC7C7D1.toInt()
                        else -> "✎" to 0xFF7FD4C1.toInt()
                    }
                    val name = r.item.ifBlank { r.text }.ifBlank { kindNames[r.kind] ?: r.kind }.replaceFirstChar { it.uppercase() }
                    val sub = listOf(kindNames[r.kind] ?: r.kind, r.person, if (r.at.isNotEmpty()) "⏰ " + r.at.drop(5) else "",
                        if (r.text.isNotBlank() && r.text != name && r.item.isNotBlank()) r.text else "").filter { it.isNotBlank() }.joinToString(" · ")
                    var sure = false
                    lateinit var row: Sheet.Row
                    row = Sheet.row(g, icon, color, name, sub, if (r.amount != 0.0) Memory.money(r.amount) else null, Sheet.TEXT, null) {
                        if (!sure) { sure = true; row.status("Tap again to delete", Sheet.RED); main.postDelayed({ if (sure) { sure = false; row.plain(sub) } }, 4000); return@row }
                        sure = false
                        if (memory.delete(r.id)) { backup(); Remind.sync(this, memory); draw() }
                    }
                }
            }
            if (all.size > shown) Sheet.primary(list, "Show more (" + (all.size - shown) + ")") { shown += 200; draw() }
        }
        filters.forEachIndexed { i, f ->
            val c = TextView(this).apply {
                text = f.first; textSize = 13f; gravity = Gravity.CENTER
                setPadding(Sheet.dp(this@MainActivity, 14f), Sheet.dp(this@MainActivity, 6f), Sheet.dp(this@MainActivity, 14f), Sheet.dp(this@MainActivity, 6f))
                setOnClickListener { filter = i; shown = 120; draw() }
            }
            chips.addView(c, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = Sheet.dp(this@MainActivity, 6f) })
            chipViews += c
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { shown = 120; draw() }
        })
        draw()

        Sheet.label(p.body, "TOOLS")
        val g2 = Sheet.group(p.body)
        lateinit var rep: Sheet.Row
        rep = Sheet.row(g2, "↓", 0xFF7FD4C1.toInt(), "Report (PDF)", "Totals, loans, notes and entries", "Make", Sheet.AMBER, null) {
            rep.status("Making…", Sheet.BLUE)
            Thread { val w = runCatching { Report.make(this, memory) }.getOrNull(); main.post { if (w == null) rep.status("Could not make it", Sheet.RED) else rep.status("Saved · $w", Sheet.GREEN) } }.start()
        }
        Sheet.row(g2, "⇧", 0xFFC7C7D1.toInt(), "Backup", "Kept up to date in Downloads as smart-book-memory.json", null, 0, null, null)
        Sheet.row(g2, "⇩", 0xFFC7C7D1.toInt(), "Restore a backup", "Pick smart-book-memory.json", "›", 0xFF5D5D66.toInt(), null) {
            p.dialog.dismiss()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_BACKUP)
        }
        lateinit var del: Sheet.Row
        var sure = false
        del = Sheet.row(g2, "×", Sheet.RED, "Forget everything", "Clears the memory book (the backup file stays)", null, 0, null) {
            if (!sure) { sure = true; del.status("Tap again to forget everything", Sheet.RED); return@row }
            memory.clear(); chat.clear(); Remind.sync(this, memory); del.status("Forgotten", Sheet.GREEN); draw()
        }
        Sheet.primary(p.body, "Done") { p.dialog.dismiss() }
        p.dialog.show()
    }

    @Deprecated("Activity result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != PICK_BACKUP || result != RESULT_OK) return
        val uri = data?.data ?: return
        val text = runCatching { contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.getOrNull()
        problemView.text = if (text != null && memory.restore(text)) "Backup restored · " + memory.recs.size + " things" else "That file is not a Smart Book backup"
    }

    // ---------------------------------------------------------------- updates ---

    private fun buildUpdateCard(root: FrameLayout, dp: Float) {
        updateCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt())
            background = Sheet.round(this@MainActivity, Sheet.GROUP, 16f, Sheet.LINE); visibility = View.GONE
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        updateTitle = TextView(this).apply { textSize = 14f; setTextColor(Sheet.TEXT); typeface = Typeface.DEFAULT_BOLD }
        updateSub = TextView(this).apply { textSize = 12f; setTextColor(Sheet.SUB); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        texts.addView(updateTitle); texts.addView(updateSub)
        updateCard.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        updateButton = Sheet.pill(this, "Update", Runnable { })
        updateCard.addView(updateButton, LinearLayout.LayoutParams(-2, (38 * dp).toInt()).apply { leftMargin = (10 * dp).toInt() })
        updateCard.addView(TextView(this).apply {
            text = "×"; textSize = 20f; setTextColor(Sheet.SUB); gravity = Gravity.CENTER; contentDescription = "Not now"
            setOnClickListener { prefs.edit().putInt("skipUpdate", prefs.getInt("offeredUpdate", 0)).apply(); updateCard.visibility = View.GONE }
        }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
        root.addView(updateCard, FrameLayout.LayoutParams(minOf((420 * dp).toInt(), resources.displayMetrics.widthPixels - (32 * dp).toInt()), -2,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = (96 * dp).toInt() })
    }

    private fun checkUpdate() {
        net.execute {
            val r = updater.check() ?: return@execute
            if (r.code == prefs.getInt("skipUpdate", 0)) return@execute
            main.post {
                prefs.edit().putInt("offeredUpdate", r.code).apply()
                updateTitle.text = "Update available · " + r.name
                updateSub.text = r.notes.ifEmpty { "Tap Update to install" }
                updateButton.text = "Update"
                updateButton.setOnClickListener { startUpdate(r) }
                updateCard.visibility = View.VISIBLE
            }
        }
    }

    private fun startUpdate(r: Updater.Release) {
        if (!updater.canInstall()) {
            updateSub.text = "Allow “Install unknown apps” for Smart Book, then tap Update again"
            runCatching { startActivity(updater.allowIntent()) }
            return
        }
        val id = runCatching { updater.download(r) }.getOrElse { updateSub.text = "Download could not start"; return }
        updateButton.text = "…"; updateButton.setOnClickListener { }
        main.post(object : Runnable {
            override fun run() {
                val pct = updater.progress(id)
                when {
                    pct < 0 -> { updateSub.text = "Download failed · tap Update"; updateButton.text = "Update"; updateButton.setOnClickListener { startUpdate(r) } }
                    pct < 100 -> { updateSub.text = "Downloading $pct%"; main.postDelayed(this, 500) }
                    else -> {
                        updateSub.text = "Opening the installer…"; updateButton.text = "Install"
                        val install = { updater.installIntent(id)?.let { runCatching { startActivity(it) } } }
                        updateButton.setOnClickListener { install() }
                        install()
                    }
                }
            }
        })
    }

    companion object {
        private const val IDLE = 0
        private const val LISTENING = 1
        private const val SPEAKING = 2
        private const val THINKING = 3
        private const val ASK_MIC = 1
        private const val PICK_BACKUP = 2
        private const val ASK_NOTIFY = 3

        /** The brain's instructions. The memory book and exact totals are added under it each time. */
        const val SYSTEM = """You are Smart Book, a memory book the user talks to on a phone call in Pakistan. You keep everything they want remembered (money and anything else) and answer like a real person.

Speak Roman Urdu (Urdu in Latin letters). Be very short: usually 1 to 6 words ("Ji.", "Likh liya.", "Hmm, theek hai.", "Acha, phir?"). Use up to two short sentences only when the user asks for information or an explanation. Every word is spoken by a paid voice: no filler, no greetings, no emojis, no repeating what they said.

Remembering: when the user tells you something worth keeping, add it to "add". Kinds: expense, income, gave (lent or gave money to a person), took (borrowed from a person), got_back (a person paid the user back), paid_back (the user paid a person back), sale, purchase, note (any fact or memory), task (something to do). Money needs an amount. "date" is when it happened (yyyy-MM-dd; "kal" means yesterday). "item" is what it was for, in the user's words (nashta, petrol, bijli ka bill). "person" is a name. "isko", "usko", "us ne" mean the person last talked about. Never add the same thing twice, and never add anything the user did not say.

Changing: "remove" takes record ids (#12 means id 12) when the user asks to delete or undo; "change" takes {"id": .., and the fields to change}.

Answering questions: use TOTALS for any total; they are exact. For anything else, read the MEMORY BOOK. Never invent a number or a record. If something is not in the book, say so in a few words. "Total kitna hua" right after talking about a person means that person's balance.

Reminders: when the user asks to be reminded ("yaad dilana", "remind karna"), add a task with "text" and "remind_at" (yyyy-MM-dd HH:mm, from the date and time now; "subah" without a time is 09:00, "shaam" 18:00, "raat" 21:00, "dopahar" 13:00). Reply like "Theek hai, kal 9 baje yaad dila doonga.". When a task is done, remove it.

If the amount, person or meaning is missing for money, ask one very short question instead of adding.
If the user asks for a report or PDF, set "report": true and reply "Ek minute.".
If the user asks to see the memory book or the list ("dikhao", "list kholo"), set "show_book": true.

Reply with JSON only:
{"reply": "<Roman Urdu>", "urdu": "<the same reply in Urdu script>", "add": [], "remove": [], "change": [], "report": false, "show_book": false}"""
    }
}
