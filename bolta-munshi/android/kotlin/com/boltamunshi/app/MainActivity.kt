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
    /** The AI has a thread of its own, so backups, update checks and key checks never hold up an answer. */
    private val ai = Executors.newSingleThreadExecutor()
    /** Goes up with every new sentence and every hang-up: an answer for an older turn is written quietly, never spoken. */
    private var turn = 0
    /** What the last change to the book did, with ids, for the AI's next turn. */
    private var lastChange = ""
    /** The sentence the last question was about ("kal petrol dalwaya" before "Kitne ka?"), for its day. */
    private var askedAbout = ""
    private var lastTurnAt = 0L
    private val inFlight = mutableSetOf<Long>()
    private var bookDialog: android.app.Dialog? = null
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
        memory = Memory.open(File(filesDir, "memory.json"))
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

    /** Leaving the app ends the call: the microphone and the voice never run in the background. */
    override fun onStop() {
        if (inCall) hangUp()
        super.onStop()
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
        singleTap = Runnable { taps.clear(); onTap() }.also { main.postDelayed(it, 250) }        // a quick double tap counts as one
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
        turn++
        softErrors = 0
        quietListens = 0
        if (System.currentTimeMillis() - lastTurnAt > 10 * 60_000L) { chat.clear(); askedAbout = "" }     // a new call after a while starts fresh
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        say("Ji?", "جی؟") { listen() }
    }

    private fun hangUp() {
        inCall = false
        typed = false
        turn++
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
        if (!inCall || state != LISTENING) return
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
                if (++softErrors > 5) { problemView.text = "The microphone is busy · another app may be using it"; freshRecogniser(); hangUp(); return }
                recogniser?.cancel(); main.postDelayed({ listen() }, 600)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { problemView.text = "Allow the microphone for Smart Book"; freshRecogniser(); hangUp() }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                problemView.text = "No internet for speech recognition"; freshRecogniser(); hangUp()
            }
            else -> { problemView.text = "Speech recognition stopped · tap to try again (Google app › Voice › Urdu)"; freshRecogniser(); hangUp() }
        }
    }

    /** After a hard error the recogniser is made new for the next call. */
    private fun freshRecogniser() {
        runCatching { recogniser?.destroy() }
        recogniser = null
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

    private class Kept(val id: Long, val text: String, val at: Long)

    /** Everything said or typed goes to the one brain, with the memory book; its answer is checked, kept and spoken. */
    private fun handle(heard: List<String>) {
        caption("")
        if (!brainAi.ready()) {
            say("Pehle AI key daalein. Settings khol raha hoon.", "پہلے اے آئی کی ڈالیں۔ سیٹنگز کھول رہا ہوں۔") { hangUp(); openBrain() }
            return
        }
        show(THINKING)
        val now = System.currentTimeMillis()
        val myTurn = ++turn
        val sent = if (catchingUp) emptyList() else pendingItems().filter { it.id !in inFlight }
        inFlight += sent.map { it.id }
        var user = "HEARD: " + heard[0] + (if (heard.size > 1) "\nALSO HEARD: " + heard.drop(1).joinToString(" | ") else "")
        if (sent.isNotEmpty()) user = earlier(sent) + "\n\nNOW " + user
        val system = systemFor(heard.joinToString(" "), now)                 // built here: the book is only touched on this thread
        val messages = chat.toList() + ("user" to user)
        ask(system, messages) { answer ->
            inFlight -= sent.map { it.id }.toSet()
            when {
                myTurn == turn && (inCall || typed) -> answered(heard, answer, now, sent, system, messages, false)
                answer != null && Brain.jsonIn(answer) != null -> keepQuietly(answer, heard[0], now, sent)     // the call ended meanwhile: written, not spoken
                else -> keep(heard[0], now)
            }
        }
    }

    /** Asks the AI on its own thread and hands the answer (or null) back on the main thread. */
    private fun ask(system: String, messages: List<Pair<String, String>>, then: (String?) -> Unit) {
        val asked: Future<String?> = ai.submit<String?> { brainAi.ask(system, messages) }
        Thread {
            val answer = try { asked.get(40, TimeUnit.SECONDS) } catch (e: Exception) { asked.cancel(true); null }
            main.post { then(answer) }
        }.start()
    }

    private fun systemFor(said: String, now: Long): String =
        SYSTEM + "\n\n" + memory.forAi(now, said, lastChange) + "\n\nAnswer the LAST message with one JSON object, like the EXAMPLES."

    // ----------------------------------------------------- nothing gets lost ---

    /** Sentences said while no AI could answer; they are written down when one answers again. */
    private fun pendingItems(): List<Kept> = runCatching {
        val a = JSONArray(prefs.getString("pending", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            val at = o.optLong("ms", 0L).takeIf { it > 0 } ?: runCatching { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(Words.str(o, "at"))!!.time }.getOrDefault(0L)
            Kept(o.optLong("id", at + i), Words.str(o, "text"), at)
        } }
    }.getOrDefault(emptyList())

    private fun savePending(items: List<Kept>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("ms", it.at)
            .put("at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(it.at)))) }
        prefs.edit().putString("pending", a.toString()).apply()
    }

    private fun keep(text: String, now: Long) {
        val p = pendingItems().toMutableList()
        p += Kept(maxOf(now, (p.maxOfOrNull { it.id } ?: 0L) + 1), text, now)
        while (p.size > 50) p.removeAt(0)                       // the oldest go first; the new sentence always stays
        savePending(p)
    }

    /** Drops kept sentences that have now been written down. */
    private fun dropKept(done: List<Kept>) {
        if (done.isEmpty()) return
        val ids = done.map { it.id }.toSet()
        savePending(pendingItems().filter { it.id !in ids })
    }

    private fun earlier(p: List<Kept>) = "EARLIER, while you could not answer, I said these. Each line has the time I said it: count its days " +
        "(kal, parson, weekdays) from that time, not from today. Keep what is worth keeping; do not answer them one by one:\n" +
        p.joinToString("\n") { k ->
            val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(k.at))
            "- said $f (that day's kal = " + Dates.plusDays(k.at, -1) + "): " + k.text
        }

    /** Writes down the kept sentences quietly, when the app opens and an AI is there. */
    private fun catchUp() {
        val p = pendingItems().filter { it.id !in inFlight }
        if (p.isEmpty() || catchingUp || inCall || typed || !brainAi.ready()) return
        catchingUp = true
        inFlight += p.map { it.id }
        val now = System.currentTimeMillis()
        ask(systemFor(p.joinToString(" ") { it.text }, now), listOf("user" to earlier(p))) { answer ->
            catchingUp = false
            inFlight -= p.map { it.id }.toSet()
            if (answer != null) keepQuietly(answer, null, now, p)
        }
    }

    /** An answer nobody is waiting to hear: its records are written and shown, nothing is spoken. */
    private fun keepQuietly(answer: String, heard: String?, now: Long, sent: List<Kept>) {
        val o = Brain.jsonIn(answer) ?: return
        dropKept(sent)
        // the dates are checked against the words, each counted from when they were said
        when {
            heard != null && sent.isEmpty() -> Dates.fix(o, heard, now, "") { id -> memory.recs.firstOrNull { it.id == id }?.kind }
            heard == null && sent.size == 1 -> Dates.fix(o, sent[0].text, sent[0].at, "") { id -> memory.recs.firstOrNull { it.id == id }?.kind }
        }
        if (memory.apply(o, now) > 0) { lastChange = describeChange(); written(now) }
    }

    /** What the last change did, with ids, for the AI's next turn. */
    private fun describeChange(): String {
        val parts = memory.added.map { "added " + it.line() } + memory.changed.map { "changed " + it.line() } + memory.removed.map { "removed " + it.line() }
        return parts.take(6).joinToString(" | ")
    }

    /** After the book changed: back it up, set reminders, and show what was written. */
    private fun written(now: Long = System.currentTimeMillis()) {
        backup()
        Remind.sync(this, memory)
        if (memory.added.any { it.at.isNotEmpty() } && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), ASK_NOTIFY)
        val today = Dates.day(now)
        fun tag(r: Memory.Rec) = (if (r.kind != "task" && r.date != today) " · " + Dates.spoken(r.date, now) else "") +
            (if (r.at.isNotEmpty()) " · ⏰ " + Dates.spoken(r.at.take(10), now).let { if (it == "kal" && r.at.take(10) > today) "kal (tomorrow)" else it } + " " + r.at.drop(11) else "")
        val lines = memory.added.map { "✓ " + it.short() + tag(it) } + memory.changed.map { "✎ " + it.short() + tag(it) } + memory.removed.map { "✕ " + it.short() }
        val balances = touchedPeople().map { "= " + Memory.sentence(it, memory.balanceOf(it)).trimEnd('.') }
        val all = lines.take(3) + (if (lines.size > 3) listOf("+" + (lines.size - 3) + " more") else emptyList()) + balances.take(2)
        if (all.isEmpty()) return
        receiptText.text = all.joinToString("\n")
        receipt.visibility = View.VISIBLE
        main.removeCallbacks(hideReceipt)
        main.postDelayed(hideReceipt, 10000)
    }

    /** People whose lena-dena the last change touched. */
    private fun touchedPeople(): List<String> = (memory.added + memory.changed + memory.removed)
        .filter { it.person.isNotBlank() && it.effect() != 0.0 }.map { it.person }.distinctBy { People.key(it) }.take(2)

    private fun undoLast() {
        main.removeCallbacks(hideReceipt)
        receipt.visibility = View.GONE
        if (memory.undo()) {
            backup(); Remind.sync(this, memory)
            lastChange = "the user pressed Undo: the last change was taken back"
            chat += "user" to "(I pressed Undo: the last change to the memory book was taken back.)"
            chat += "assistant" to "{\"reply\": \"Theek hai.\"}"
            problemView.text = "Undone"
            main.postDelayed({ if (problemView.text == "Undone") problemView.text = "" }, 2500)
        }
    }

    /** "Likh liya" and the like: the reply says something was written. */
    private fun claimsWrite(s: String) = Regex("(?i)likh|لکھ|note kar|yaad rakh|یاد رکھ|save|darj|درج|kar diya|کر دیا|mita|مٹا|hata|ہٹا").containsMatchIn(s)

    private fun answered(heard: List<String>, answer: String?, now: Long, sent: List<Kept>, system: String, messages: List<Pair<String, String>>, retried: Boolean) {
        val o = answer?.let { Brain.jsonIn(it) }
        if (o == null) {
            problemView.text = if (answer == null) brainAi.lastProblem.ifEmpty { "The AI did not answer in time" } else "The AI's answer could not be read"
            keep(heard[0], now)                     // nothing is lost: it is written down when an AI answers again
            say("Abhi jawab nahi mila. Baat rakh li hai.", "ابھی جواب نہیں ملا۔ بات رکھ لی ہے۔") { listen() }
            return
        }
        problemView.text = ""
        dropKept(sent)
        // the day the user's own words name wins over the AI's date
        val fixedDates = Dates.fix(o, heard[0], now, askedAbout) { id -> memory.recs.firstOrNull { it.id == id }?.kind }
        val n = memory.apply(o, now)
        var ro = Words.str(o, "reply")
        var ur = Words.str(o, "urdu")
        // "Likh liya" with nothing written: ask the AI once more, then tell the truth
        if (n == 0 && claimsWrite(ro) && memory.skipped == 0 && memory.alreadyEven.isEmpty() && !retried) {
            val again = messages + ("assistant" to (answer ?: "")) + ("user" to "(App: NOTHING was written: your add, change and remove were empty or unreadable. " +
                "If my last message has something to keep, send it now in add/change/remove, in the exact format of the EXAMPLES. If not, reply without saying it was written.)")
            val myTurn = turn
            ask(system, again) { a2 -> if (myTurn == turn && (inCall || typed)) answered(heard, a2 ?: answer, now, emptyList(), system, messages, true) else if (a2 != null) keepQuietly(a2, heard[0], now, emptyList()) }
            return
        }
        if (n > 0) { lastChange = describeChange(); written(now) }
        // what could not be written becomes one short question
        val q = when {
            memory.needAmount > 0 -> "Kitne rupay?" to "کتنے روپے؟"
            memory.needPerson > 0 -> "Kis ka hisaab? Naam batayein." to "کس کا حساب؟ نام بتائیں۔"
            memory.unclear > 0 -> "Ye kharcha tha ya udhaar?" to "یہ خرچہ تھا یا ادھار؟"
            else -> null
        }
        if (q != null) {
            if (n == 0) { ro = q.first; ur = q.second }
            else if (!ro.contains("?")) { ro = ro.trimEnd() + " " + q.first; ur = ur.trimEnd() + " " + q.second }
        }
        if (n == 0 && memory.alreadyEven.isNotEmpty()) { ro = memory.alreadyEven[0] + " ka hisaab pehle se barabar hai."; ur = memory.alreadyEven[0] + " کا حساب پہلے سے برابر ہے۔" }
        if (n == 0 && q == null && claimsWrite(ro) && !Regex("(?i)nahi|نہیں").containsMatchIn(ro)) { ro = "Kuch likha nahi gaya. Dobara batayein?"; ur = "کچھ لکھا نہیں گیا۔ دوبارہ بتائیں؟" }
        if (ro.isEmpty()) ro = if (n > 0) "Likh liya." else "Ji."
        if (n > 0) {
            // the day it was written for, when it is not today and the reply did not say it
            val today = Dates.day(now)
            val days = memory.added.filter { it.kind != "task" && it.date != today }.map { it.date }.distinct()
            if (days.size == 1 && (fixedDates > 0 || !ro.contains(Dates.spoken(days[0], now), true))) {
                ro = ro.trimEnd() + " " + Dates.spoken(days[0], now).replaceFirstChar { it.uppercase() } + " ki tareekh par."
                if (ur.isNotEmpty()) ur = ur.trimEnd() + " " + Dates.spokenUrdu(days[0], now) + " کی تاریخ پر۔"
            }
            // lena-dena: the new balance, said by the app, always the right way round
            for (p in touchedPeople()) {
                val b = memory.balanceOf(p)
                ro = ro.trimEnd() + " " + when { b > 0.005 -> People.oblique(p) + " se ab " + Memory.money(b) + " lene hain."
                    b < -0.005 -> People.oblique(p) + " ko ab " + Memory.money(-b) + " dene hain."; else -> "$p ka hisaab ab barabar hai." }
                if (ur.isNotEmpty()) ur = ur.trimEnd() + " " + Memory.sentenceUrdu(p, b)
            }
        }
        if (ur.isEmpty()) ur = ro
        askedAbout = if (ro.trimEnd().endsWith("?")) (askedAbout.takeIf { n == 0 && it.isNotEmpty() }?.let { "$it. " } ?: "") + heard[0] else ""
        if (o.optBoolean("show_book")) openBook()
        chat += "user" to heard[0]
        chat += "assistant" to historyOf(ro)
        while (chat.size > 8) chat.removeAt(0)              // the last four turns: enough to follow, small enough for free keys
        lastTurnAt = now
        appendLog(heard[0], ro)
        if (o.optBoolean("report")) { makeReport(ro, ur); return }
        say(ro, ur) { listen() }
    }

    /** The assistant's turn as the AI will see it next time: the reply and what was really written, with ids. */
    private fun historyOf(reply: String): String {
        val o = JSONObject().put("reply", reply)
        if (memory.added.isNotEmpty()) o.put("add", JSONArray().apply { memory.added.forEach { r ->
            put(JSONObject().put("id", r.id).put("kind", r.kind).put("amount", r.amount).put("item", r.item).put("person", r.person).put("date", r.date)
                .apply { if (r.at.isNotEmpty()) put("remind_at", r.at); if (r.udhaar) put("udhaar", true); if (r.text.isNotBlank() && r.item.isBlank()) put("text", r.text.take(80)) })
        } })
        if (memory.changed.isNotEmpty()) o.put("change", JSONArray().apply { memory.changed.forEach { put(JSONObject().put("id", it.id).put("now", it.line())) } })
        if (memory.removed.isNotEmpty()) o.put("remove", JSONArray().apply { memory.removed.forEach { put(it.id) } })
        return o.toString()
    }

    // ------------------------------------------------------------------ voice ---

    /** A natural voice from the internet first (Google or ElevenLabs, in the chosen order), then the phone's own. */
    private fun say(ro: String, ur: String, then: (() -> Unit)?) {
        recogniser?.cancel()                         // never hear its own voice
        clips.stopPlaying(); live.stop(); tts?.stop()
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
        var ok = true
        if (parts.size <= 1) ok = t.speak(line, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        else parts.forEachIndexed { i, p ->
            val last = i == parts.size - 1
            ok = ok && t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (last) id else id + "_" + i) == TextToSpeech.SUCCESS
            if (!last) t.playSilentUtterance(180, TextToSpeech.QUEUE_ADD, id + "_s" + i)
        }
        // a voice that does not start must not leave the call stuck on "Speaking"
        if (!ok) { showCaption(); main.postDelayed({ spoken(id) }, 1200) }
        else main.postDelayed({ if (current(id) && state == SPEAKING && t.isSpeaking.not()) spoken(id) }, 8000 + line.length * 120L)
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
    private fun backup(name: String = "smart-book-memory.json") {
        if (memory.recs.isEmpty() && name == "smart-book-memory.json") return       // an empty book never writes over the copy
        val text = memory.json()
        val slot = "uri_$name"
        net.execute {
            runCatching {
                if (Build.VERSION.SDK_INT < 29) {
                    File(getExternalFilesDir(null), name).writeText(text)        // Android 9 and older: kept in the app's own folder
                    return@execute
                }
                val known = prefs.getString(slot, if (name == "smart-book-memory.json") prefs.getString("memoryUri", null) else null)
                if (known != null) runCatching { contentResolver.openOutputStream(Uri.parse(known), "wt")?.use { it.write(text.toByteArray()); return@execute } }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "application/json")
                }) ?: return@execute
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                prefs.edit().putString(slot, uri.toString()).apply()
            }
        }
    }

    private fun backupWhere() = if (Build.VERSION.SDK_INT < 29) "the app's folder (Android/data)" else "Downloads"

    private fun appendLog(heard: String, said: String) {
        val line = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date()) + " | " + heard + " | " + said + "\n"
        net.execute {
            runCatching {
                val f = File(filesDir, "talk.txt")
                if (f.length() > 512 * 1024) f.writeText(f.readText().takeLast(256 * 1024))
                f.appendText(line)
            }
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
        main.post { where = runCatching { Report.make(this, memory) }.getOrNull(); done = true }          // the book is read on this thread only
        say(ro.ifEmpty { "Ek minute." }, ur.ifEmpty { "ایک منٹ۔" }) {
            fun after() {
                if (!done) { main.postDelayed({ after() }, 300); return }
                if (where != null) { say("Report ban gayi.", "رپورٹ بن گئی۔") { listen() }; problemView.text = "Report saved · $where" }
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
        val painters = mutableListOf<() -> Unit>()
        Brain.SERVICES.forEachIndexed { i, s ->
            val sw = Sheet.Toggle(this, prefs.getBoolean("on_" + s.slot, true))
            lateinit var keyLine: LinearLayout
            val row = Sheet.row(g, s.name.take(1), tints[i], s.name, "", null, 0, sw) { keyLine.visibility = if (keyLine.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            var info: Sheet.Row? = null
            val paint: () -> Unit = {
                row.status(brainAi.statusText(s), brainAi.statusColor(s)); row.view.alpha = if (prefs.getBoolean("on_" + s.slot, true)) 1f else 0.55f
                val d = brainAi.detail(s)
                info?.let { it.view.visibility = if (d.isEmpty() || brainAi.key(s).isEmpty()) View.GONE else View.VISIBLE; it.status(d, Sheet.DIM) }
            }
            painters += paint
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
            info = Sheet.statusLine(g)                 // the service's own words when something is wrong
            paint()
            sw.onChange = Sheet.OnFlag { prefs.edit().putBoolean("on_" + s.slot, it).apply(); paint() }
        }
        Sheet.note(p.body, "Tap a name to enter its key · more keys = never silent")
        lateinit var checkAll: TextView
        checkAll = Sheet.primary(p.body, "Check all keys") {
            checkAll.text = "Checking…"
            Brain.SERVICES.forEach { s -> if (brainAi.key(s).isNotEmpty()) prefs.edit().putString("st_" + s.slot, "checking").apply() }
            painters.forEach { it() }
            net.execute {
                Brain.SERVICES.forEach { s -> if (brainAi.key(s).isNotEmpty()) brainAi.askOne(s, brainAi.key(s), "Reply with JSON only: {\"reply\": \"ok\"}", listOf("user" to "salam")) }
                main.post { painters.forEach { it() }; checkAll.text = "Check all keys" }
            }
        }
        checkAll.setTextColor(Sheet.TEXT); checkAll.background = Sheet.round(this, Sheet.GROUP, 14f, Sheet.LINE)
        (checkAll.layoutParams as LinearLayout.LayoutParams).bottomMargin = Sheet.dp(this, 10f)
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

    private val kindNames = mapOf("expense" to "Spent", "income" to "Earned", "gave" to "Gave (udhaar)", "took" to "Borrowed", "got_back" to "Got back",
        "paid_back" to "Paid back", "owes_me" to "To receive", "i_owe" to "To pay", "sale" to "Sold", "purchase" to "Bought", "note" to "Note", "task" to "To do")

    /**
     * The whole memory book: spending today and this month, lena-dena per person, search,
     * kinds, and every record by day. Tap a record twice to delete it (Undo brings it back).
     */
    private fun openBook(fromSettings: Boolean = false) {
        bookDialog?.let { runCatching { it.dismiss() } }
        val now = System.currentTimeMillis()
        val today = Dates.day(now)
        val yesterday = Dates.plusDays(now, -1)
        val ps = memory.periods(now)
        val waiting = pendingItems().size
        val p = Sheet.open(this, "Memory book", memory.recs.size.toString() + " things remembered" + (if (waiting > 0) " · $waiting waiting for the AI" else ""),
            if (fromSettings) Runnable { openSettings() } else null)
        bookDialog = p.dialog
        val people = memory.people()
        val lena = people.filter { it.bal > 0.005 }.sumOf { it.bal }
        val dena = -people.filter { it.bal < -0.005 }.sumOf { it.bal }
        Sheet.stats(p.body, Memory.money(memory.spent(ps[0])) to "Spent today", Memory.money(memory.spent(ps[6])) to "Spent this month")
        Sheet.stats(p.body, Memory.money(lena) to "To receive (lena)", Memory.money(dena) to "To pay (dena)")

        val search = Sheet.field(this, "Search: a name, an item, a word…", "", false)
        p.body.addView(search, LinearLayout.LayoutParams(-1, Sheet.dp(this, 44f)).apply { bottomMargin = Sheet.dp(this@MainActivity, 10f) })
        val filters = listOf("All" to { _: Memory.Rec -> true }, "Money" to { r: Memory.Rec -> r.kind in Memory.CASH },
            "Lena-dena" to { r: Memory.Rec -> r.effect() != 0.0 },
            "Notes" to { r: Memory.Rec -> r.kind == "note" }, "To do" to { r: Memory.Rec -> r.kind == "task" })
        var filter = 0
        var shown = 120
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipViews = mutableListOf<TextView>()
        p.body.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Sheet.dp(this@MainActivity, 10f) })
        val undoBar = Sheet.pill(this, "Deleted · Undo", Runnable { })
        undoBar.visibility = View.GONE
        p.body.addView(undoBar, LinearLayout.LayoutParams(-1, Sheet.dp(this, 40f)).apply { bottomMargin = Sheet.dp(this@MainActivity, 10f) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        p.body.addView(list, LinearLayout.LayoutParams(-1, -2))

        lateinit var draw: () -> Unit
        undoBar.setOnClickListener { if (memory.undo()) { backup(); Remind.sync(this, memory); undoBar.visibility = View.GONE; draw() } }
        draw = {
            list.removeAllViews()
            chipViews.forEachIndexed { i, c ->
                c.setTextColor(if (i == filter) 0xFF0D0D0F.toInt() else Sheet.SUB)
                c.background = Sheet.round(this, if (i == filter) Sheet.TEXT else Sheet.GROUP, 14f, if (i == filter) 0 else Sheet.LINE)
            }
            val q = search.text.toString().trim().lowercase(Locale.ROOT)
            val qKey = People.key(q)
            // lena-dena first: who owes whom, in words
            if (q.isEmpty() && (filter == 0 || filter == 2)) {
                val owed = memory.people().filter { kotlin.math.abs(it.bal) > 0.005 }
                if (owed.isNotEmpty()) {
                    Sheet.label(list, "LENA-DENA")
                    val g = Sheet.group(list)
                    owed.take(30).forEach { person ->
                        Sheet.row(g, if (person.bal > 0) "↙" else "↗", if (person.bal > 0) Sheet.GREEN else Sheet.AMBER, person.name,
                            Memory.sentence(person.name, person.bal).trimEnd('.'), Memory.money(kotlin.math.abs(person.bal)),
                            if (person.bal > 0) Sheet.GREEN else Sheet.AMBER, null) { search.setText(person.name) }
                    }
                }
            }
            val all = memory.recs.filter { filters[filter].second(it) }
                .filter { r -> q.isEmpty() || (r.item + " " + r.person + " " + r.text + " " + Memory.plain(r.amount) + " " + (kindNames[r.kind] ?: "")).lowercase(Locale.ROOT).contains(q) ||
                    qKey.length >= 3 && r.person.isNotBlank() && People.key(r.person) == qKey }
                .sortedWith(compareByDescending<Memory.Rec> { it.date }.thenByDescending { it.id })
            if (all.isEmpty()) {
                val g = Sheet.group(list)
                Sheet.row(g, null, 0, if (memory.recs.isEmpty()) "Nothing yet" else "Nothing found",
                    if (memory.recs.isEmpty()) "Say or type anything to keep it" else "Try another word", null, 0, null, null)
            }
            if (q.isNotEmpty() && all.isNotEmpty()) {
                val spentOn = all.filter { it.kind == "expense" || it.kind == "purchase" }.sumOf { it.amount }
                val person = all.firstOrNull { it.person.isNotBlank() && (People.key(it.person) == qKey || it.person.lowercase(Locale.ROOT).contains(q)) }?.person
                val parts = mutableListOf<String>()
                if (spentOn > 0) parts += "Spent: " + Memory.money(spentOn)
                if (person != null && all.any { it.effect() != 0.0 }) parts += Memory.sentence(person, memory.balanceOf(person))
                if (parts.isNotEmpty()) Sheet.note(list, parts.joinToString(" · "))
            }
            all.take(shown).groupBy { it.date }.forEach { (date, recs) ->
                Sheet.label(list, when (date) { today -> "TODAY"; yesterday -> "YESTERDAY"
                    else -> runCatching { java.text.SimpleDateFormat("EEE d MMM yyyy", Locale.US).format(java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!).uppercase(Locale.US) }.getOrDefault(date) })
                val g = Sheet.group(list)
                recs.forEach { r ->
                    val e = r.effect()
                    val (icon, color) = when {
                        e > 0 -> "↗" to Sheet.AMBER
                        e < 0 -> "↙" to Sheet.BLUE
                        r.kind == "expense" || r.kind == "purchase" -> "−" to Sheet.RED
                        r.kind == "income" || r.kind == "sale" -> "+" to Sheet.GREEN
                        r.kind == "task" -> "☐" to 0xFFC7C7D1.toInt()
                        else -> "✎" to 0xFF7FD4C1.toInt()
                    }
                    val name = (Memory.PERSON_WORDS[r.kind]?.first?.takeIf { r.person.isNotBlank() }?.replace("X", r.person)
                        ?: r.item.ifBlank { r.text }.ifBlank { kindNames[r.kind] ?: r.kind }).replaceFirstChar { it.uppercase() }
                    val sub = listOf(kindNames[r.kind] ?: r.kind, if (r.udhaar) "udhaar" else "", if (Memory.PERSON_WORDS.containsKey(r.kind)) r.item else r.person,
                        if (r.at.isNotEmpty()) "⏰ " + r.at.drop(5) else "",
                        if (r.text.isNotBlank() && r.text != name && r.item.isNotBlank()) r.text else "").filter { it.isNotBlank() }.joinToString(" · ")
                    var sure = false
                    lateinit var row: Sheet.Row
                    row = Sheet.row(g, icon, color, name, sub, if (r.amount != 0.0) Memory.money(r.amount) else null, Sheet.TEXT, null) {
                        if (!sure) { sure = true; row.status("Tap again to delete", Sheet.RED); main.postDelayed({ if (sure) { sure = false; row.plain(sub) } }, 4000); return@row }
                        sure = false
                        if (memory.delete(r.id)) { backup(); Remind.sync(this, memory); lastChange = "removed " + r.line(); undoBar.visibility = View.VISIBLE; draw() }
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
        val redraw = Runnable { shown = 120; draw() }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { main.removeCallbacks(redraw); main.postDelayed(redraw, 250) }   // not on every key
        })
        draw()

        Sheet.label(p.body, "TOOLS")
        val g2 = Sheet.group(p.body)
        lateinit var rep: Sheet.Row
        rep = Sheet.row(g2, "↓", 0xFF7FD4C1.toInt(), "Report (PDF)", "Totals, lena-dena, notes and entries", "Make", Sheet.AMBER, null) {
            val w = runCatching { Report.make(this, memory) }.getOrNull()
            if (w == null) rep.status("Could not make it", Sheet.RED) else rep.status("Saved · $w", Sheet.GREEN)
        }
        Sheet.row(g2, "⇧", 0xFFC7C7D1.toInt(), "Backup", "Kept up to date in " + backupWhere() + " as smart-book-memory.json", null, 0, null, null)
        Sheet.row(g2, "⇩", 0xFFC7C7D1.toInt(), "Restore a backup", "Pick smart-book-memory.json", "›", 0xFF5D5D66.toInt(), null) {
            p.dialog.dismiss()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_BACKUP)
        }
        lateinit var del: Sheet.Row
        var sure = false
        del = Sheet.row(g2, "×", Sheet.RED, "Forget everything", "A copy is kept as smart-book-memory-old.json", null, 0, null) {
            if (!sure) { sure = true; del.status("Tap again to forget everything", Sheet.RED); main.postDelayed({ if (sure) { sure = false; del.plain("A copy is kept as smart-book-memory-old.json") } }, 4000); return@row }
            sure = false
            backup("smart-book-memory-old.json")
            memory.clear(); chat.clear(); lastChange = "the whole book was cleared"; savePending(emptyList()); Remind.sync(this, memory)
            del.status("Forgotten · Undo brings it back", Sheet.GREEN); undoBar.text = "Forgotten · Undo"; undoBar.visibility = View.VISIBLE; draw()
        }
        Sheet.primary(p.body, "Done") { p.dialog.dismiss() }
        p.dialog.setOnDismissListener { if (bookDialog === p.dialog) bookDialog = null }
        p.dialog.show()
    }

    @Deprecated("Activity result")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code != PICK_BACKUP || result != RESULT_OK) return
        val uri = data?.data ?: return
        val text = runCatching { contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } }.getOrNull()
        if (text != null && memory.restore(text)) {
            backup(); Remind.sync(this, memory); chat.clear(); lastChange = "a backup was restored"
            receiptText.text = "✓ Backup restored · " + memory.recs.size + " things"
            receipt.visibility = View.VISIBLE; main.removeCallbacks(hideReceipt); main.postDelayed(hideReceipt, 10000)
        } else problemView.text = "That file is not a Smart Book backup (or it is empty)"
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
        /** How many of the newest records go with every question (older ones go when their words are said). */
        private const val RECENT = 40

        /** The brain's instructions. The memory book and exact totals are added under it each time. */
        const val SYSTEM = """You are Smart Book: a voice hisaab and memory book on a phone call in Pakistan. Read the user's LAST message and return ONE JSON object. The app writes your add, remove and change into the book, checks every date against the user's words, and works out every total and balance itself.

INPUT
- Speech comes in Urdu script (کل 500 کا پیٹرول ڈلوایا) or Roman Urdu; understand both. ALSO HEARD is the same sentence heard another way: use it only to fix a word.
- Only the LAST message is new. Earlier messages are already written (see LAST CHANGE and the history): never add them again.

OUTPUT: JSON only
{"reply":"","urdu":"","add":[],"remove":[],"change":[],"report":false,"show_book":false}
- reply: Roman Urdu in Latin letters (even when the user spoke Urdu script), digits for numbers, short: 1-8 words. After writing, say what you wrote ("Kal ka petrol 500, likh liya."). After a question, the answer in one short sentence.
- urdu: the same reply in Urdu script.
- add: one object per thing: {"kind","amount","item","person","date","text","remind_at","udhaar"}; leave out empty fields.
- remove: record ids (#57 is 57). change: [{"id":57, only the fields that change}].

KINDS
expense: the user paid for something (kharcha, bill, kiraya, fees, petrol, sabzi). income: the user earned (tankhwah, kamai).
sale, purchase: shop goods sold, bought. Normal shopping is expense.
note: a fact to remember (birthday, where something is kept). task: something to do, with remind_at when they want a reminder.
LENA-DENA kinds, always with "person":
gave: the user gave X money as udhaar or qarz, or paid something for X that X will return. X owes more.
took: the user took money from X as udhaar or qarz. The user owes more.
got_back: X returned money to the user. paid_back: the user returned money to X.
owes_me: no money moved now, X must pay the user ("X se 1000 lene hain", "X ne mujhe 1000 dene hain", "X ki taraf mere 3000 hain").
i_owe: no money moved now, the user must pay X ("X ke 500 dene hain", "mujhe X ko 500 dene hain", "dukaan wale ka 2000 udhaar hai").
settle: "X ka hisaab clear/barabar", "X ne saare paise de diye", "sara udhaar utar gaya": {"kind":"settle","person":"X"} with no amount; the app closes the exact balance.

LENA-DENA: the way the money went decides it
- "X ko ... diye / bheje / lauta diye / wapis kiye" = from the user TO X: gave (paid_back if DENA lists X).
- "X se ... liye / mile" and "X ne ... diye / bheje / wapis kiye" = FROM X to the user: took (got_back if LENA lists X).
- "dene hain" = the user must pay later: i_owe. "lene hain" = the user will receive later: owes_me. "X ne dene hain" = X must pay: owes_me.
- Urdu: کو ko, سے se, نے ne, دیے diye, لیے liye, واپس wapis, ادھار udhaar, قرض qarz, دینے ہیں dene hain, لینے ہیں lene hain.
- Paying X for work, goods, a bill, salary or rent, or a gift or eidi, is an expense with "person", not a loan. "X ko N diye" with no reason: gave for a friend or relative by name, expense for a shop or worker (wala, driver, maasi, mistri, dukaan); if unsure ask "Udhaar ya kharcha?".
- Goods or a bill on credit ("udhaar pe liya", "udhaar pe diya"): the expense, purchase or sale with "person" and "udhaar":true.
- A promise ("kal dega", "agle mahine lauta dunga") moves no money: add nothing unless they ask for a reminder.
- "person": the name only, in Roman letters, without bhai, sahab or ji (علی بھائی is Ali), spelled as in PEOPLE.
- Questions about lena-dena: say the LENA-DENA sentence exactly as written there; never turn "lene" into "dene".

DATES
- "date" is the day it happened: copy yyyy-MM-dd from CALENDAR, or write "kal", "parson", or "-3" for 3 days ago. Today: leave it out.
- Money already happened, so its date is never after today. "kal" or "کل" with spent or given words (diye, liya, dalwaya, kiya, hua) is yesterday.
- A weekday ("mangal ko", "جمعہ کو") is the last such day: copy it from CALENDAR. "10 tareekh" also comes from CALENDAR.
- "کل" can also mean total ("کل ملا کر", "کل خرچہ", "کل ادھار"): then it is not a day.
- When the user corrects a day ("ye kal ka tha"), change that record's date; never add it again.

REMINDERS ("yaad dilana", "remind karna"): a task with "text" and "remind_at" as "yyyy-MM-dd HH:mm" from CALENDAR, or "kal 09:00" (here kal is tomorrow), or "+30 min". subah 09:00, dopahar 13:00, shaam 18:00, raat 21:00. When a task is done, remove its id.

AMOUNTS: a plain number (1250), never "1,250" or "Rs 1250". sau 100, hazaar 1000, lakh 100000; dedh x1.5, dhai x2.5, sawa +0.25, saadhe +0.5, paune -0.25 (dhai sau 250, sawa hazaar 1250, paune do hazaar 1750, 1.5 lakh 150000). "500 petrol aur 200 chai" is 2 records.

FIXING: "nahi 600 tha", "ghalat", "ulta likha" = change the record in LAST CHANGE (else the newest that fits). "mita do", "hata do" = remove its id. A fix never adds a second record.
ITEMS: 1-3 lowercase Roman words (petrol, chai, bijli ka bill), spelled as in ITEMS.
QUESTIONS add nothing. Take every number from TOTALS, ITEM TOTALS, LENA-DENA or the records; never add up records yourself, never guess or invent. If it is not there: "Is ka record nahi hai."
CHAT (salam, shukriya, acha): reply only.
ASK one short question and add nothing when money has no amount ("Kitne rupay?"), lena-dena has no name ("Kis ka?") or the meaning is unclear.
Report or PDF: "report":true, reply "Ek minute.". "List/book dikhao": "show_book":true.

EXAMPLES
User: کل 500 کا پیٹرول ڈلوایا
{"reply":"Kal ka petrol 500, likh liya.","urdu":"کل کا پیٹرول 500، لکھ لیا۔","add":[{"kind":"expense","amount":500,"item":"petrol","date":"kal"}]}
User: dhai sau ki sabzi aur sawa hazaar ka doodh
{"reply":"Sabzi 250, doodh 1250, likh liya.","urdu":"سبزی 250، دودھ 1250، لکھ لیا۔","add":[{"kind":"expense","amount":250,"item":"sabzi"},{"kind":"expense","amount":1250,"item":"doodh"}]}
User: علی کو 2000 ادھار دیے
{"reply":"Ali ko 2000 udhaar, likh liya.","urdu":"علی کو 2000 ادھار، لکھ لیا۔","add":[{"kind":"gave","amount":2000,"person":"Ali"}]}
User: parson Bilal se 5000 qarz liya
{"reply":"Parson Bilal se 5000 qarz, likh liya.","urdu":"پرسوں بلال سے 5000 قرض، لکھ لیا۔","add":[{"kind":"took","amount":5000,"person":"Bilal","date":"parson"}]}
User: علی نے 500 واپس کر دیے
{"reply":"Ali se 500 wapis, likh liya.","urdu":"علی سے 500 واپس، لکھ لیا۔","add":[{"kind":"got_back","amount":500,"person":"Ali"}]}
User: Hamza ke 3000 dene hain
{"reply":"Hamza ko 3000 dene hain, likh liya.","urdu":"حمزہ کو 3000 دینے ہیں، لکھ لیا۔","add":[{"kind":"i_owe","amount":3000,"person":"Hamza"}]}
User: Usman se 1000 lene hain
{"reply":"Usman se 1000 lene hain, likh liya.","urdu":"عثمان سے 1000 لینے ہیں، لکھ لیا۔","add":[{"kind":"owes_me","amount":1000,"person":"Usman"}]}
User: kiryane wale se 1500 ka rashan udhaar liya
{"reply":"Rashan 1500 udhaar, likh liya.","urdu":"راشن 1500 ادھار، لکھ لیا۔","add":[{"kind":"expense","amount":1500,"item":"rashan","person":"Kiryana wala","udhaar":true}]}
User: Ali ka hisaab clear ho gaya
{"reply":"Ali ka hisaab barabar.","urdu":"علی کا حساب برابر۔","add":[{"kind":"settle","person":"Ali"}]}
User: Imran ko 1000 diye   (Imran is not in LENA-DENA)
{"reply":"Udhaar ya kharcha?","urdu":"ادھار یا خرچہ؟"}
User: علی سے کتنے لینے ہیں   (LENA-DENA says: "Ali se 1,500 lene hain.")
{"reply":"Ali se 1500 lene hain.","urdu":"علی سے 1500 لینے ہیں۔"}
User: is mahine petrol pe kitna laga   (ITEM TOTALS This month: petrol 3500 (5x))
{"reply":"Is mahine petrol pe 3500 lage.","urdu":"اس مہینے پیٹرول پر 3500 لگے۔"}
User: nahi 600 tha   (LAST CHANGE: added #57 ... expense 500 petrol)
{"reply":"Petrol 600 kar diya.","urdu":"پیٹرول 600 کر دیا۔","change":[{"id":57,"amount":600}]}
User: kal subah bijli ka bill yaad dilana
{"reply":"Kal subah 9 baje yaad dila dunga.","urdu":"کل صبح 9 بجے یاد دلا دوں گا۔","add":[{"kind":"task","text":"bijli ka bill","remind_at":"kal 09:00"}]}
User: Ayesha ki salgirah 5 March ko hai
{"reply":"Yaad rakh liya.","urdu":"یاد رکھ لیا۔","add":[{"kind":"note","text":"Ayesha ki salgirah 5 March"}]}"""
    }
}
