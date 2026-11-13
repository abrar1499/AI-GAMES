package com.boltamunshi.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Smart Book (formerly Bolta Munshi): a brain on a black screen that you talk to. Tap once to start
 * the call, tap again (or say "khuda hafiz") to end it. The phone's own speech
 * recogniser listens and its text-to-speech answers. One full-screen WebView
 * does the rest: brain.js understands, visual.js draws the brain.
 */
public class MainActivity extends Activity implements RecognitionListener, TextToSpeech.OnInitListener {

    private static final int IDLE = 0, LISTENING = 1, SPEAKING = 2;
    private static final int ASK_MIC = 1, PICK_BACKUP = 2, ASK_MIC_RECORD = 3;
    private static final String BACKUP_NAME = "smart-book-backup.json";
    private static final String LOG_FILE = "baatein.txt", LOG_NAME = "smart-book-baatein.txt";
    private static final String SAVE_FILE = "hisaab.json";

    // every line the app itself says: {Roman Urdu, Urdu, Hindi}
    private static final String[] HELLO = {"Ji?", "جی؟", "जी?"};              // short: every word costs voice credits
    private static final String[] REPORT_WAIT = {"Ek minute, de rahi hoon.", "ایک منٹ، دے رہی ہوں۔", "एक मिनट, दे रही हूं।"};
    private static final String[] REPORT_DONE = {"Report Downloads mein aa gayi.", "رپورٹ ڈاؤن لوڈز میں آ گئی۔", "रिपोर्ट डाउनलोड्स में आ गई।"};
    private static final String[] REPORT_FAIL = {"Report nahi ban saki.", "رپورٹ نہیں بن سکی۔", "रिपोर्ट नहीं बन सकी।"};
    // a change to the khata is confirmed in a word or two; the screen shows the full line
    private static final String[][] ACKS = {
            {"Likh liya.", "لکھ لیا۔", "लिख लिया।"},
            {"Ho gaya.", "ہو گیا۔", "हो गया।"},
            {"Kar diya.", "کر دیا۔", "कर दिया।"},
            {"Theek hai, likh liya.", "ٹھیک ہے، لکھ لیا۔", "ठीक है, लिख लिया।"}};
    private static final String[] DELETED = {"Mita diya.", "مٹا دیا۔", "मिटा दिया।"};
    private static final java.util.regex.Pattern REPORT = java.util.regex.Pattern.compile(
            "(report|riport|ripot|raport|pdf|p\\.?\\s?d\\.?\\s?f|رپورٹ|पीडीएफ|रिपोर्ट)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final String CHAT_SYSTEM =
            "You are Smart Book, a voice assistant on a phone call that keeps a money ledger (hisaab) for a user in Pakistan. "
            + "Talk like a real person on a call, in Roman Urdu (Urdu written in Latin letters). "
            + "Be as short as natural speech allows: usually 1 to 6 words, e.g. \"Ji.\", \"Hmm, theek hai.\", \"Acha, phir?\", \"Aap batayein.\", \"Sahi hai, aisa kyun?\". "
            + "Go longer, at most two short sentences, only when the user asks for an explanation or information. "
            + "Every character is spoken by a paid voice, so never add filler, greetings, emojis, lists or repeats. "
            + "If the user wants to record money (expense, income, loan, sale, stock) or asks about their hisaab, do not answer it yourself: "
            + "put a short plain ledger command in Roman Urdu in \"command\" (like \"petrol 500\", \"ahmed ko 1000 diye\", \"aaj ka kharcha kitna\") and leave \"reply\" empty. "
            + "Reply with JSON only: {\"reply\": \"<Roman Urdu>\", \"urdu\": \"<the same reply in Urdu script>\", \"command\": \"\"}";
    private static final String[] BACKED_UP = {"Backup phone ke Downloads folder mein mehfooz hai.", "بیک اپ فون کے ڈاؤن لوڈز فولڈر میں محفوظ ہے۔", "बैकअप फ़ोन के डाउनलोड्स फ़ोल्डर में सुरक्षित है।"};
    private static final String[] BACKUP_FAILED = {"Backup nahi ban saka.", "بیک اپ نہیں بن سکا۔", "बैकअप नहीं बन सका।"};
    private static final String[] PICK_FILE = {"Backup ki file chunein.", "بیک اپ کی فائل چنیں۔", "बैकअप की फ़ाइल चुनें।"};
    private static final String[] RESTORED = {"Backup wapas aa gaya. Sab kuch yaad hai.", "بیک اپ واپس آ گیا۔ سب کچھ یاد ہے۔", "बैकअप वापस आ गया। सब कुछ याद है।"};
    private static final String[] BAD_FILE = {"Yeh file backup nahi lagti.", "یہ فائل بیک اپ نہیں لگتی۔", "यह फ़ाइल बैकअप नहीं लगती।"};
    private static final String[] NOT_SAVED = {"Yeh save nahi ho saka. Phone ki storage bhari hui hai.", "یہ محفوظ نہیں ہو سکا۔ فون کی اسٹوریج بھری ہوئی ہے۔", "यह सेव नहीं हो सका। फ़ोन की स्टोरेज भरी हुई है।"};
    private static final String[] NO_MIC_PERMISSION = {"Mic ki ijazat nahi mili. Phone ki settings mein is app ko microphone ki ijazat dein.", "مائیک کی اجازت نہیں ملی۔ فون کی سیٹنگز میں اس ایپ کو مائیکروفون کی اجازت دیں۔", "माइक की इजाज़त नहीं मिली। फ़ोन की सेटिंग्स में इस ऐप को माइक्रोफ़ोन की इजाज़त दें।"};
    private static final String[] NO_NETWORK = {"Awaaz samajhne wali service se rabta nahi ho raha. Internet check karein.", "آواز سمجھنے والی سروس سے رابطہ نہیں ہو رہا۔ انٹرنیٹ چیک کریں۔", "आवाज़ समझने वाली सर्विस से संपर्क नहीं हो रहा। इंटरनेट चेक करें।"};
    private static final String[] MIC_BUSY = {"Mic se awaaz nahi aa rahi. Shayad koi aur app mic use kar rahi hai.", "مائیک سے آواز نہیں آ رہی۔ شاید کوئی اور ایپ مائیک استعمال کر رہی ہے۔", "माइक से आवाज़ नहीं आ रही। शायद कोई और ऐप माइक इस्तेमाल कर रही है।"};
    private static final String[] NO_LANGUAGE = {"Is phone mein Urdu sunne ki suhulat nahi hai. Google app mein Urdu zabaan download karein.", "اس فون میں اردو سننے کی سہولت نہیں ہے۔ گوگل ایپ میں اردو زبان ڈاؤن لوڈ کریں۔", "इस फ़ोन में उर्दू सुनने की सुविधा नहीं है। गूगल ऐप में उर्दू भाषा डाउनलोड करें।"};
    private static final String[] NO_RECOGNISER = {"Is phone mein awaaz samajhne wali service nahi hai. Google app install karein.", "اس فون میں آواز سمجھنے والی سروس نہیں ہے۔ گوگل ایپ انسٹال کریں۔", "इस फ़ोन में आवाज़ समझने वाली सर्विस नहीं है। गूगल ऐप इंस्टॉल करें।"};
    private static final String[] BRAIN_ERROR = {"Maaf kijiye, andar koi masla aa gaya. App band kar ke dobara kholein.", "معاف کیجیے، اندر کوئی مسئلہ آ گیا۔ ایپ بند کر کے دوبارہ کھولیں۔", "माफ़ कीजिए, अंदर कोई समस्या आ गई। ऐप बंद करके दोबारा खोलें।"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final ExecutorService voiceNet = Executors.newSingleThreadExecutor();   // ElevenLabs lines, apart from the teacher

    // The teacher and the shared lessons. The keys are typed in by the user, never kept in the code.
    // Any service that speaks the OpenAI chat format will do; the key's prefix says which one it is.
    private static class Service {
        final String name, url; final String[] models;
        Service(String name, String url, String... models) { this.name = name; this.url = url; this.models = models; }
    }
    private static final Service GROQ = new Service("Groq", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile", "openai/gpt-oss-120b");
    private static final Service GEMINI = new Service("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.5-flash", "gemini-2.0-flash");
    private static final Service OPENROUTER = new Service("OpenRouter", "https://openrouter.ai/api/v1/chat/completions");   // its free models change often: the list is fetched (see freeModels)
    // Claude speaks its own format (the Messages API), not the OpenAI one: askClaude handles it
    private static final Service ANTHROPIC = new Service("Claude", "https://api.anthropic.com/v1/messages", "claude-haiku-5-5");
    // every service has its own key box; they are tried in this order until one answers
    private static final String[] KEY_SLOTS = {"key_claude", "key_openrouter", "key_gemini", "key_groq"};
    private static final Service[] KEY_SERVICES = {ANTHROPIC, OPENROUTER, GEMINI, GROQ};
    private boolean announce = true;       // say a key problem on screen (off while other services may still answer)
    private final java.util.Map<String, Long> restingUntil = new java.util.HashMap<>();   // a service that just failed rests a while

    private static String slotFor(Service svc) {
        for (int i = 0; i < KEY_SERVICES.length; i++) if (KEY_SERVICES[i] == svc) return KEY_SLOTS[i];
        return KEY_SLOTS[KEY_SLOTS.length - 1];
    }

    /** The one key box of before, moved into the box of its own service, once. */
    private void moveOldKey() {
        String old = settings.getString("groqKey", "").trim();
        if (old.isEmpty()) return;
        String slot = slotFor(serviceFor(old));
        SharedPreferences.Editor e = settings.edit().remove("groqKey").remove("groqModel");
        if (settings.getString(slot, "").isEmpty()) e.putString(slot, old);
        e.apply();
    }

    private boolean anyBrainKey() {
        for (String slot : KEY_SLOTS) if (!settings.getString(slot, "").trim().isEmpty()) return true;
        return false;
    }

    /** Ask every service that is on and has a key, in order, until one answers; each one's status is kept for the Brain screen. */
    private String askBrains(String system, String user) {
        StringBuilder why = new StringBuilder();
        boolean tried = false;
        announce = false;
        try {
            for (int i = 0; i < KEY_SLOTS.length; i++) {
                String key = settings.getString(KEY_SLOTS[i], "").trim();
                if (key.isEmpty() || !settings.getBoolean("on_" + KEY_SLOTS[i], true)) continue;
                String name = KEY_SERVICES[i].name;
                Long until = restingUntil.get(name);
                if (until != null && until > System.currentTimeMillis()) continue;
                tried = true;
                String got = tryBrain(i, key, system, user);
                if (got != null) { restingUntil.remove(name); if (badKeySaid) { badKeySaid = false; main.post(() -> problem.setText("")); } return got; }
                restingUntil.put(name, System.currentTimeMillis() + 5 * 60 * 1000);
                why.append(name).append(": ").append(statusText(KEY_SLOTS[i])).append('\n');
            }
        } finally {
            announce = true;
        }
        lastProblem = why.toString().trim();
        if (tried && !badKeySaid) { badKeySaid = true; final String w = lastProblem; main.post(() -> problem.setText(w + "\nAnswering on my own for now.")); }
        return null;
    }

    /** One service, one question; its status (working, limit, invalid key...) is saved either way. */
    private String tryBrain(int i, String key, String system, String user) {
        String slot = KEY_SLOTS[i];
        lastProblem = "";
        String got = askTeacher(key, system, user);
        SharedPreferences.Editor e = settings.edit();
        if (got != null) e.putString("st_" + slot, "ok").putString("stm_" + slot, settings.getString("worked_" + KEY_SERVICES[i].name, ""));
        else e.putString("st_" + slot, kindOf(lastCode, lastProblem));
        e.apply();
        return got;
    }

    /** An HTTP answer as one of: limit, bad, blocked, offline, error. */
    private static String kindOf(int code, String problem) {
        String p = problem == null ? "" : problem.toLowerCase();
        if (code == 429 || code == 402 || p.contains("limit") || p.contains("credit") || p.contains("quota")) return "limit";
        if (code == 401 || p.contains("api key") || p.contains("invalid")) return "bad";
        if (code == 403) return "blocked";
        if (code == -1) return "offline";
        return "error";
    }

    /** The status line of one AI key, and its colour. */
    private String statusText(String slot) {
        String key = settings.getString(slot, "").trim();
        if (!settings.getBoolean("on_" + slot, true)) return "Off";
        if (key.isEmpty()) return "No key";
        String st = settings.getString("st_" + slot, "");
        switch (st) {
            case "ok": String m = settings.getString("stm_" + slot, ""); return "Working" + (m.isEmpty() ? "" : " · " + m);
            case "limit": return "Limit reached · retries later";
            case "bad": return "Invalid key · copy it again";
            case "blocked": return "Blocked here · try a VPN or another key";
            case "offline": return "No internet";
            case "checking": return "Checking…";
            case "error": return "Not answering · retries later";
            case "wrong": return "Not a key for this service";
            default: return "Saved · not checked yet";
        }
    }

    private int statusColor(String slot) {
        if (!settings.getBoolean("on_" + slot, true) || settings.getString(slot, "").trim().isEmpty()) return Sheet.DIM;
        switch (settings.getString("st_" + slot, "")) {
            case "ok": return Sheet.GREEN;
            case "limit": case "offline": case "error": return Sheet.AMBER;
            case "bad": case "blocked": case "wrong": return Sheet.RED;
            case "checking": return Sheet.BLUE;
            default: return Sheet.SUB;
        }
    }

    private static Service serviceFor(String key) {
        if (key.startsWith("sk-ant-")) return ANTHROPIC;
        if (key.startsWith("AIza")) return GEMINI;
        if (key.startsWith("sk-or-")) return OPENROUTER;
        return GROQ;
    }
    private static final String REPO = "abrar1499/AI-GAMES", SHARED_PATH = "bolta-munshi/learned/shared.json", DEFAULT_BRANCH = "claude/new-session-fb21rr";
    private SharedPreferences settings;
    private boolean badKeySaid;
    private String lastProblem = "";       // why the teacher could not be used, in plain words
    private Runnable longPress;
    private boolean longPressed;
    private TextView problem;
    private TextView statusView, captionView;      // top: Ready / Listening / Speaking; bottom: what was heard or said
    private int lastCode;                           // the last AI service's HTTP answer (-1: no answer)
    private String pendingCaption = "", captionOverride;   // the line shown once its sound starts
    private final java.util.List<String> chatLog = new java.util.ArrayList<>();   // the last few lines, for the AI
    private int ackTurn;
    private long zUntil;                            // after three quick taps, a stroke on the screen opens the settings
    private final java.util.List<Long> taps = new java.util.ArrayList<>();
    private Runnable singleTap;
    private float downX, downY;
    private boolean moved;
    private long levelSentAt;

    private WebView brain;
    /** BolKhata's khata logic, run by voice; it keeps the hisaab in BolKhata's own format. */
    private VoiceKhata khata;
    private KhataStore khataStore;
    private VoiceClips clips;               // the user's own recorded voice
    private LiveVoice live;                 // whole replies from ElevenLabs while there is internet
    private boolean liveProblemShown;
    private String partId;                  // the phone's voice saying words with no recording, inside a line in the user's voice
    private Runnable partNext;
    private boolean switchedToHindi;        // the Urdu voice cannot read Roman words: Hindi says them for a moment
    private boolean brainReady;
    private String savedJson = "";         // the hisaab exactly as last written

    private SpeechRecognizer recogniser;
    private TextToSpeech tts;
    private boolean ttsReady;
    private int script = 0;                 // index into the {Roman, Urdu, Hindi} arrays
    private String listenLanguage = "ur-PK";

    private boolean inCall;
    private int state = IDLE;
    private int softErrors;                 // recogniser hiccups in a row
    private int utterance;                  // id of what is being spoken now
    private Runnable afterSpeech;

    // ------------------------------------------------------------ screen ---

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        float dp = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        startBrain();
        brain.setBackgroundColor(Color.BLACK);
        brain.setContentDescription("Tap to talk");
        // the whole screen is the only control; a WebView swallows clicks, so watch touches instead.
        // One tap talks. Three quick taps, then any stroke (a "Z") within 4 seconds, open the settings.
        brain.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getX(); downY = e.getY(); moved = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getX() - downX, e.getY() - downY) > 12 * dp) moved = true;
                    break;
                case MotionEvent.ACTION_UP:
                    if (!moved) tapped();
                    else if (System.currentTimeMillis() < zUntil) { zUntil = 0; openSettings(); }
                    break;
            }
            return true;
        });
        root.addView(brain, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        problem = new TextView(this);
        problem.setTextColor(0xFFF0B24A);
        problem.setTextSize(15);
        problem.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        lp.setMargins((int) (16 * dp), 0, (int) (16 * dp), (int) (32 * dp));
        root.addView(problem, lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView name = new TextView(this);
        name.setText("Smart Book");
        name.setTextColor(0xFFECECF1);
        name.setTextSize(20);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(name);
        statusView = new TextView(this);
        statusView.setTextSize(13);
        top.addView(statusView);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        tlp.setMargins(0, (int) (28 * dp), 0, 0);
        root.addView(top, tlp);

        captionView = new TextView(this);
        captionView.setTextColor(0xFFF3F4F6);
        captionView.setTextSize(18);
        captionView.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        clp.setMargins((int) (24 * dp), 0, (int) (24 * dp), (int) (84 * dp));
        root.addView(captionView, clp);

        setContentView(root);
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        khataStore = new KhataStore(this);
        khata = new VoiceKhata(khataStore);
        moveOldKey();
        clips = new VoiceClips(this);
        live = new LiveVoice(this);
        show(IDLE);
        tts = makeTts();
    }

    /** Tell the brain on screen what the call is doing: grey and quiet, green and listening, amber and speaking. */
    private void show(int newState) {
        state = newState;
        if (statusView != null) {
            int color = newState == LISTENING ? 0xFF34D399 : newState == SPEAKING ? 0xFFF5B14A : 0xFF8E8E99;
            statusView.setTextColor(color);
            statusView.setText(newState == LISTENING ? "●  Listening…" : newState == SPEAKING ? "●  Speaking" : "●  Ready");
        }
        if (!brainReady) return;           // onPageFinished repeats this once the page exists
        String name = newState == LISTENING ? "listening" : newState == SPEAKING ? "speaking" : "idle";
        brain.evaluateJavascript("show('" + name + "')", null);
    }

    /** One tap talks (after a moment, to tell it from a triple tap); three quick taps arm the settings stroke. */
    private void tapped() {
        long now = System.currentTimeMillis();
        java.util.Iterator<Long> it = taps.iterator();
        while (it.hasNext()) if (now - it.next() > 700) it.remove();
        taps.add(now);
        if (singleTap != null) main.removeCallbacks(singleTap);
        if (taps.size() >= 3) { taps.clear(); zUntil = now + 4000; return; }
        if (taps.size() == 1) {
            singleTap = () -> { taps.clear(); onTap(); };
            main.postDelayed(singleTap, 380);
        }
    }

    private void onTap() {
        if (!inCall) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startCall();
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, ASK_MIC);
        } else if (state == SPEAKING) {     // cut in while it is talking
            stopSpeaking();
            listen();
        } else {
            hangUp();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == ASK_MIC_RECORD) { if (granted) openRecorder(); else fail(NO_MIC_PERMISSION); return; }
        if (code != ASK_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCall();
        else fail(NO_MIC_PERMISSION);
    }

    // -------------------------------------------------------------- call ---

    private void startCall() {
        problem.setText("");
        // once: say how to get the Urdu voice when the phone does not have it
        if (ttsReady && urduState < TextToSpeech.LANG_AVAILABLE && !settings.getBoolean("urduHint", false)) {
            settings.edit().putBoolean("urduHint", true).apply();
            problem.setText("Is phone mein Urdu awaaz nahi, is liye lehja Urdu nahi lagta. Screen ko der tak daba kar settings mein \"Urdu awaaz\" wala button dabayein.");
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { fail(NO_RECOGNISER); return; }
        if (!brainReady) { fail(BRAIN_ERROR); return; }
        if (recogniser == null) {
            recogniser = SpeechRecognizer.createSpeechRecognizer(this);
            recogniser.setRecognitionListener(this);
        }
        inCall = true;
        softErrors = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        say(HELLO, this::listen);              // just "Ji?": short, and kept after the first time
    }

    private void hangUp() {
        inCall = false;
        main.removeCallbacksAndMessages(null);
        if (recogniser != null) recogniser.cancel();
        stopSpeaking();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        show(IDLE);
        pushShared();
    }

    /** A problem ends the call. It is spoken, and written in case speech is the problem. */
    private void fail(String[] message) {
        hangUp();
        problem.setText(message[0]);
        say(message, null);
        show(IDLE);
    }

    // ------------------------------------------------------------- voice ---

    private static final String GOOGLE_TTS = "com.google.android.tts";
    /** Urdu is a voice of Google's speech engine; when the phone has that engine, it is used even if another is the default. */
    private TextToSpeech makeTts() {
        try {
            getPackageManager().getPackageInfo(GOOGLE_TTS, 0);
            return new TextToSpeech(this, this, GOOGLE_TTS);
        } catch (Exception notThere) {
            return new TextToSpeech(this, this);
        }
    }

    /** -2: the engine has no Urdu, -1: it has Urdu but the voice is not downloaded, 0+: Urdu is ready. */
    private int urduState = -2;
    private Locale urdu = new Locale("ur", "PK");

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        // Urdu if the phone can speak it, else Hindi (the same spoken language),
        // else an English voice reading Roman Urdu.
        for (Locale l : new Locale[]{new Locale("ur", "PK"), new Locale("ur", "IN"), new Locale("ur")}) {
            int st = tts.isLanguageAvailable(l);
            if (st > urduState) { urduState = st; urdu = l; }
        }
        chooseLanguage();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { main.post(MainActivity.this::showCaption); }
            @Override public void onDone(String id) { main.post(() -> ttsDone(id)); }
            @Override public void onError(String id) { main.post(() -> ttsDone(id)); }
        });
        ttsReady = true;
    }

    /**
     * Urdu if the phone can speak it, else Hindi (the same spoken language), else English reading Roman Urdu.
     * "Hindi awaaz" in the settings picks Hindi even when Urdu is there: on many phones it sounds better.
     * Listening stays in Urdu then, which is what the recogniser has been hearing well.
     */
    private void chooseLanguage() {
        boolean hindi = tts.isLanguageAvailable(new Locale("hi", "IN")) >= TextToSpeech.LANG_AVAILABLE;
        boolean wantHindi = "hi".equals(settings.getString("voiceLang", ""));
        if (wantHindi && hindi) {
            tts.setLanguage(new Locale("hi", "IN")); script = 2; listenLanguage = "ur-PK"; useBestVoice("hi");
        } else if (urduState >= TextToSpeech.LANG_AVAILABLE) {
            tts.setLanguage(urdu); script = 1; listenLanguage = "ur-PK"; useBestVoice("ur");
        } else if (hindi) {
            tts.setLanguage(new Locale("hi", "IN")); script = 2; listenLanguage = "hi-IN"; useBestVoice("hi");
        } else {
            tts.setLanguage(new Locale("en", "IN")); script = 0; listenLanguage = "ur-PK"; useBestVoice("en");
        }
    }

    /** Phones ship several voices per language; the highest-quality one sounds the least robotic. */
    private void useBestVoice(String language) {
        try {
            // a voice picked in the settings comes first
            String chosen = settings == null ? "" : settings.getString("voice", "");
            for (Voice v : tts.getVoices()) {
                if (v.getName().equals(chosen) && language.equals(v.getLocale().getLanguage())) { tts.setVoice(v); return; }
            }
            Voice best = null;
            for (Voice v : tts.getVoices()) {
                if (!language.equals(v.getLocale().getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (best == null || v.getQuality() > best.getQuality()) best = v;
            }
            if (best != null) tts.setVoice(best);
        } catch (Exception ignored) {
            // some engines cannot list voices; the language default is still set
        }
    }

    private void say(String[] texts, Runnable then) { say(texts, then, false); }

    /** [mine]: the user's own voice for every recorded word, however little of the line is recorded (the test line). */
    private void say(String[] texts, Runnable then, boolean mine) {
        show(SPEAKING);
        pendingCaption = captionOverride != null ? captionOverride : texts[0];
        captionOverride = null;
        afterSpeech = then;
        final String id = String.valueOf(++utterance);
        if (switchedToHindi) { switchedToHindi = false; chooseLanguage(); }
        // ElevenLabs, the whole line at once: the most natural voice, while there is internet
        final String eKey = settings.getString("elevenKey", ""), eVoice = settings.getString("elevenVoice", "");
        if (!mine && settings.getBoolean("liveVoice", true) && !eKey.isEmpty() && !eVoice.isEmpty()) {
            final String model = settings.getString("elevenModel", ElevenLabs.MODEL);
            final String text = live.text(texts[0]);
            short[] kept = live.cached(eVoice, model, text);
            if (kept != null) { showCaption(); clips.playPcm(kept, () -> spoken(id)); return; }
            final java.util.concurrent.Future<short[]> asked = voiceNet.submit(() -> live.get(eKey, eVoice, model, text));
            new Thread(() -> {
                short[] got = null;
                try { got = asked.get(6, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception slowOrFailed) { /* the line is still kept for next time */ }
                final short[] pcm = got;
                main.post(() -> {
                    if (!id.equals(String.valueOf(utterance))) return;            // interrupted meanwhile
                    if (pcm != null) { showCaption(); clips.playPcm(pcm, () -> spoken(id)); return; }
                    if (!liveProblemShown && !live.getProblem().isEmpty()) { liveProblemShown = true; problem.setText(live.getProblem()); }
                    sayHere(texts, id, false);
                });
            }).start();
            return;
        }
        sayHere(texts, id, mine);
    }

    /** The line said on the phone: the user's recordings joined up, or the phone's own voice. */
    private void sayHere(String[] texts, String id, boolean mine) {
        // the user's own voice, when enough of the line is recorded
        if (settings.getBoolean("myVoice", true) && !clips.recorded().isEmpty()) {
            MyVoice.Plan plan = MyVoice.INSTANCE.plan(texts[0], clips.recorded());
            noteMissing(plan.getMissing());
            if (mine ? plan.getAnyClip() : plan.getUsable()) { showCaption(); playParts(plan.getParts(), 0, id); return; }
        }
        if (!ttsReady) { showCaption(); main.postDelayed(() -> spoken(id), 300); return; }   // no voice: carry on silently
        String line = texts[script];
        // a Hindi voice reads Latin letters the English way: any Roman Urdu left in the line goes into Hindi letters
        if (script == 2) line = Devanagari.INSTANCE.fromRoman(line);
        tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, id);
    }

    /** The reply's text appears when its sound starts, not before. */
    private void showCaption() { if (captionView != null) captionView.setText(pendingCaption); }

    private void spoken(String id) {
        if (!id.equals(String.valueOf(utterance))) return;                    // an older, interrupted line
        Runnable next = afterSpeech;
        afterSpeech = null;
        if (next != null) next.run(); else if (!inCall) show(IDLE);
    }

    private void ttsDone(String id) {
        if (id.equals(partId)) {
            Runnable next = partNext;
            partId = null; partNext = null;
            if (next != null) next.run();
        } else spoken(id);
    }

    /** A line in the user's own voice: recordings joined together, and the phone's voice for words not recorded yet. */
    private void playParts(java.util.List<MyVoice.Part> parts, int from, String id) {
        if (!id.equals(String.valueOf(utterance))) return;                  // interrupted
        if (from >= parts.size()) { spoken(id); return; }
        MyVoice.Part p = parts.get(from);
        if (p instanceof MyVoice.Part.Say) {
            if (!ttsReady) { playParts(parts, from + 1, id); return; }
            String text = ((MyVoice.Part.Say) p).getText();
            if (script == 1 && !switchedToHindi && tts.isLanguageAvailable(new Locale("hi", "IN")) >= TextToSpeech.LANG_AVAILABLE) {
                tts.setLanguage(new Locale("hi", "IN")); useBestVoice("hi"); switchedToHindi = true;
            }
            if (script == 2 || switchedToHindi) text = Devanagari.INSTANCE.fromRoman(text);
            partId = "p" + id + "_" + from;
            partNext = () -> playParts(parts, from + 1, id);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, partId);
            return;
        }
        int to = from;
        while (to < parts.size() && !(parts.get(to) instanceof MyVoice.Part.Say)) to++;
        final int next = to;
        clips.play(parts.subList(from, to), () -> playParts(parts, next, id));
    }

    /** Words the munshi had to say without a recording, counted, so the recording screen asks for them first. */
    private void noteMissing(java.util.List<String> words) {
        if (words.isEmpty()) return;
        try {
            JSONObject counts = new JSONObject(settings.getString("missingWords", "{}"));
            for (String w : words) if (w.length() > 1) counts.put(w, counts.optInt(w) + 1);
            settings.edit().putString("missingWords", counts.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void stopSpeaking() {
        utterance++;                       // whatever was playing no longer counts
        afterSpeech = null;
        partNext = null;
        if (clips != null) clips.stopPlaying();
        if (tts != null) tts.stop();
    }

    // --------------------------------------------------------- listening ---

    private void listen() {
        if (!inCall) return;
        show(LISTENING);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, listenLanguage);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, listenLanguage);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);   // its other guesses help with an unfamiliar accent
        // wait a little longer before deciding the sentence is over, so a pause mid-thought is not cut off
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        recogniser.startListening(i);
    }

    @Override
    public void onResults(Bundle results) {
        if (!inCall) return;
        softErrors = 0;
        ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        JSONArray guesses = new JSONArray();
        if (heard != null) for (String h : heard) if (h != null && !h.trim().isEmpty()) guesses.put(h.trim());
        if (guesses.length() == 0) listen();
        else { captionView.setText("\u201C" + guesses.optString(0) + "\u201D"); respond(guesses); }
    }

    @Override
    public void onError(int error) {
        if (!inCall || state != LISTENING) return;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                softErrors = 0;                                   // just silence: keep the line open
                main.postDelayed(this::listen, 250);
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case SpeechRecognizer.ERROR_CLIENT:
                if (++softErrors > 5) { fail(MIC_BUSY); break; }
                recogniser.cancel();
                main.postDelayed(this::listen, 600);
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                fail(NO_MIC_PERMISSION);
                break;
            case SpeechRecognizer.ERROR_AUDIO:
                fail(MIC_BUSY);
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                fail(NO_NETWORK);
                break;
            default:                                              // language not supported / unavailable
                fail(NO_LANGUAGE);
        }
    }

    @Override public void onReadyForSpeech(Bundle params) { }
    @Override public void onBeginningOfSpeech() { }
    /** The microphone's loudness drives how busy the brain looks while it listens. */
    @Override
    public void onRmsChanged(float rms) {
        long now = System.currentTimeMillis();
        if (!brainReady || state != LISTENING || now - levelSentAt < 60) return;
        levelSentAt = now;
        float level = Math.max(0f, Math.min(1f, (rms + 2f) / 12f));     // the recogniser reports roughly -2..10 dB
        brain.evaluateJavascript("level(" + level + ")", null);
    }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle partial) { }
    @Override public void onEvent(int type, Bundle params) { }

    // ------------------------------------------------------------- brain ---

    private void startBrain() {
        brain = new WebView(this);
        brain.getSettings().setJavaScriptEnabled(true);
        brain.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                savedJson = readSaved();
                brain.evaluateJavascript("init(" + JSONObject.quote(savedJson) + ")", r -> {
                    brainReady = r != null && r.contains("ready");
                    show(state);
                    // the hisaab lives in BolKhata's ledger now: this brain only talks
                    brain.evaluateJavascript("chatOnly()", null);
                    moveOldHisaab();
                    pullShared();
                });
            }
        });
        brain.loadUrl("file:///android_asset/brain.html");
    }

    /**
     * Answer what was heard. BolKhata's khata logic reads it first; anything that is
     * not about the hisaab goes to the talking brain. With a Groq key that brain shows
     * the sentence to the teacher, and a command the teacher writes is handed back to
     * the khata.
     */
    private void respond(JSONArray guesses) {
        show(SPEAKING);
        if (wantsReport(guesses)) { makeReport(guesses.optString(0)); return; }
        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(2, guesses.length()); i++) {
            String heard = guesses.optString(i);
            VoiceKhata.Reply k = null;
            try { k = khata.reply(heard, now); } catch (Exception ignored) { }
            if (k != null) { speakKhata(heard, k); return; }
        }
        // the brain on the phone answers first; with an AI key, the AI makes the chat natural
        brain.evaluateJavascript("ask(" + guesses + ")", raw -> {
            if (!inCall) return;
            JSONObject local;
            try { local = new JSONObject((String) new JSONTokener(raw).nextValue()); } catch (Exception e) { fail(BRAIN_ERROR); return; }
            boolean phoneOnly = !local.optString("action").isEmpty() || !local.optString("db").isEmpty();
            if (phoneOnly || !anyBrainKey()) { finishReply(local); return; }
            final String heard = guesses.optString(0);
            final String user = (chatLog.isEmpty() ? "" : "Conversation so far:\n" + android.text.TextUtils.join("\n", chatLog) + "\n\n")
                    + "The user just said (speech recognition guesses, best first): " + guesses;
            final java.util.concurrent.Future<String> asked = net.submit(() -> askBrains(CHAT_SYSTEM, user));
            new Thread(() -> {
                String got = null;
                try { got = asked.get(8, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception slow) { /* the phone's answer will do */ }
                final String answer = got;
                main.post(() -> { if (inCall) chatReply(heard, local, answer); });
            }).start();
        });
    }

    /** The AI's short reply, or the ledger command it found in what was said, or else the phone's own answer. */
    private void chatReply(String heard, JSONObject local, String answer) {
        JSONObject o = null;
        if (answer != null) {
            try {
                int a = answer.indexOf('{'), b = answer.lastIndexOf('}');
                o = new JSONObject(a >= 0 && b > a ? answer.substring(a, b + 1) : answer);
            } catch (Exception ignored) { }
        }
        if (o == null) { finishReply(local); return; }
        String command = o.optString("command").trim();
        if (!command.isEmpty()) {
            VoiceKhata.Reply k = null;
            try { k = khata.reply(command, System.currentTimeMillis()); } catch (Exception ignored) { }
            if (k != null) { speakKhata(heard, k); return; }
        }
        String ro = o.optString("reply").trim();
        if (ro.isEmpty()) { finishReply(local); return; }
        String ur = o.optString("urdu").trim();
        appendLog(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                + " | SUNA: " + heard + " | JAWAB: " + ro + " | AI");
        noteChat(heard, ro);
        say(new String[]{ro, ur.isEmpty() ? ro : ur, Devanagari.INSTANCE.fromRoman(ro)}, this::listen);
    }

    private void noteChat(String heard, String said) {
        chatLog.add("User: " + heard);
        chatLog.add("You: " + said);
        while (chatLog.size() > 8) chatLog.remove(0);
    }

    private boolean wantsReport(JSONArray guesses) {
        for (int i = 0; i < Math.min(2, guesses.length()); i++) if (REPORT.matcher(guesses.optString(i)).find()) return true;
        return false;
    }

    /** "Ek minute, de rahi hoon." while the PDF is made, then where it went; it lands in Downloads. */
    private void makeReport(String heard) {
        final String[] where = {null};
        final boolean[] done = {false};
        new Thread(() -> {
            String w = null;
            try { w = ReportPdf.INSTANCE.make(this, khataStore.getEntries(), khataStore.getCurrency()); } catch (Exception ignored) { }
            final String got = w;
            main.post(() -> { where[0] = got; done[0] = true; });
        }).start();
        noteChat(heard, REPORT_WAIT[0]);
        say(REPORT_WAIT, new Runnable() {
            @Override public void run() {
                if (!done[0]) { main.postDelayed(this, 300); return; }
                if (where[0] != null) { captionOverride = "Saved · " + where[0]; say(REPORT_DONE, MainActivity.this::listen); }
                else say(REPORT_FAIL, MainActivity.this::listen);
            }
        });
    }

    /** Speak the khata's answer, keep its line in the conversation file, and back the khata up. */
    private void speakKhata(String heard, VoiceKhata.Reply k) {
        appendLog(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                + " | SUNA: " + heard + " | JAWAB: " + k.getRo() + " | KHATA");
        if (k.getChanged()) exportKhata();
        noteChat(heard, k.getRo());
        String[] full = {k.getRo(), k.getUr(), Devanagari.INSTANCE.fromRoman(k.getRo())};
        if (!k.getChanged()) { say(full, this::listen); return; }
        // a change is confirmed in a word or two (fewer voice credits); the full line is on the screen
        captionOverride = k.getRo();
        String low = k.getRo().toLowerCase(java.util.Locale.ROOT);
        say(low.contains("mita") || low.contains("hata") ? DELETED : ACKS[ackTurn++ % ACKS.length], this::listen);
    }

    /** Bolta Munshi's own money records from before, moved into BolKhata's ledger once. */
    private void moveOldHisaab() {
        if (settings.getBoolean("movedToKhata", false)) return;
        brain.evaluateJavascript("oldHisaab()", raw -> {
            try {
                String json = (String) new JSONTokener(raw).nextValue();
                java.util.List<com.bolkhata.app.Entry> old = Migrate.INSTANCE.fromMunshi(json);
                if (!old.isEmpty()) {
                    java.util.List<com.bolkhata.app.Entry> all = new java.util.ArrayList<>(old);
                    all.addAll(khataStore.getEntries());
                    khataStore.setEntries(all);
                    exportKhata();
                }
                brain.evaluateJavascript("dropHisaab()", rest -> {
                    try { writeSaved((String) new JSONTokener(rest).nextValue()); } catch (Exception ignored) { }
                });
                settings.edit().putBoolean("movedToKhata", true).apply();
            } catch (Exception ignored) {
                // try again next time the app opens
            }
        });
    }

    /** The khata as BolKhata's backup file, kept in Downloads (Android 10+). BolKhata can import it. */
    private void exportKhata() {
        if (Build.VERSION.SDK_INT < 29) return;
        final String text;
        try { text = khataStore.exportJson(); } catch (Exception e) { return; }
        net.execute(() -> {
            try {
                ContentResolver cr = getContentResolver();
                String known = getPreferences(MODE_PRIVATE).getString("khataUriSB", null);
                if (known != null) {
                    try (OutputStream out = cr.openOutputStream(Uri.parse(known), "wt")) {
                        if (out != null) { out.write(text.getBytes(StandardCharsets.UTF_8)); return; }
                    } catch (Exception gone) {
                        // deleted: make a new one below
                    }
                }
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, "smart-book-khata.json");
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return;
                try (OutputStream out = cr.openOutputStream(uri, "wt")) { if (out != null) out.write(text.getBytes(StandardCharsets.UTF_8)); }
                getPreferences(MODE_PRIVATE).edit().putString("khataUriSB", uri.toString()).apply();
            } catch (Exception ignored) {
                // the khata itself is safe in the app
            }
        });
    }

    private void finish(JSONArray guesses, String call) {
        brain.evaluateJavascript(call, raw -> {
            if (!inCall) return;
            try { finishReply(new JSONObject((String) new JSONTokener(raw).nextValue())); } catch (Exception e) { fail(BRAIN_ERROR); }
        });
    }

    /** Say the phone brain's answer and do what it asks (backup, restore, hang up). */
    private void finishReply(JSONObject reply) {
        {
            try {
                // the teacher rewrote the sentence as a plain command: the khata carries it out
                String cmd = reply.optString("cmd");
                if (!cmd.isEmpty()) {
                    VoiceKhata.Reply k = null;
                    try { k = khata.reply(cmd, System.currentTimeMillis()); } catch (Exception ignored) { }
                    if (k != null) { speakKhata(cmd, k); return; }
                }
                String[] texts = texts(reply.getJSONObject("speak"));
                appendLog(reply.optString("line"));
                String db = reply.getString("db");
                if (!db.isEmpty() && !writeSaved(db)) texts = NOT_SAVED;
                String action = reply.getString("action");
                if ("backup".equals(action)) texts = exportBackup() ? BACKED_UP : BACKUP_FAILED;
                if ("restore".equals(action)) { say(PICK_FILE, this::pickBackup); return; }
                final boolean bye = "hangup".equals(action);
                say(texts, () -> { if (bye) hangUp(); else listen(); });
            } catch (Exception e) {
                fail(BRAIN_ERROR);
            }
        }
    }

    // ------------------------------------------------------------ teacher ---

    /** Ask Groq for its verdict. Returns the model's text, or null if it could not be reached. */
    private String askTeacher(String key, String system, String user) {
        Service svc = serviceFor(key);
        if (svc == ANTHROPIC) return askClaude(key, system, user);
        java.util.List<String> models = new java.util.ArrayList<>();
        String chosen = "", worked = settings.getString("worked_" + svc.name, "");
        if (!chosen.isEmpty()) models.add(chosen);
        if (!worked.isEmpty() && !models.contains(worked)) models.add(worked);       // the last model that answered goes first
        for (String m : (svc == OPENROUTER ? freeModels() : java.util.Arrays.asList(svc.models))) if (!models.contains(m)) models.add(m);
        if (models.size() > 10) models = models.subList(0, 10);
        int tried = 0;
        for (String model : models) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    JSONObject body = new JSONObject();
                    body.put("model", model);
                    body.put("temperature", 0);
                    body.put("max_tokens", 500);
                    JSONArray messages = new JSONArray();
                    messages.put(new JSONObject().put("role", "system").put("content", system));
                    messages.put(new JSONObject().put("role", "user").put("content", user));
                    body.put("messages", messages);
                    if (attempt == 0) body.put("response_format", new JSONObject().put("type", "json_object"));
                    if (attempt == 0) tried++;
                    Http r = http("POST", svc.url, "Bearer " + key, body.toString());
                    if (r.code == 200) {
                        lastProblem = "";
                        if (!model.equals(worked)) settings.edit().putString("worked_" + svc.name, model).apply();
                        if (badKeySaid) { badKeySaid = false; main.post(() -> problem.setText("")); }
                        return new JSONObject(r.body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content");
                    }
                    // a 403 about one model ("only available on ...") just means: try the next model
                    if (r.code == 403 && !blockedEverywhere(r)) {
                        lastProblem = explain(r, svc.name);
                        dropModel(model);
                        break;
                    }
                    if (r.code == 401 || r.code == 403) {
                        lastProblem = explain(r, svc.name);
                        if (announce && !badKeySaid) { badKeySaid = true; final String why = lastProblem; main.post(() -> problem.setText(why + "\nTab tak main khud jawab dungi.")); }
                        return null;
                    }
                    lastProblem = explain(r, svc.name);
                    if (r.code == 400 && attempt == 0) continue;     // some models refuse the JSON setting: try once without it
                    if (model.equals(worked)) settings.edit().remove("worked_" + svc.name).apply();
                    if (r.code == 404 || r.code == 402) dropModel(model);
                    break;                                          // this model is not available or busy: try the next
                } catch (Exception e) {
                    lastProblem = svc.name + " tak pohanch nahi saki (" + e.getClass().getSimpleName() + "). Internet check karein.";
                    return null;                                    // no internet or too slow: answer without the teacher
                }
            }
        }
        if (tried > 1 && !lastProblem.isEmpty()) lastProblem = tried + " models aazmaye, koi nahi chala. Aakhri ka jawab:\n" + lastProblem;
        return null;
    }

    /**
     * The teacher on Claude (Haiku unless another Claude model is typed in the settings), through
     * Anthropic's Messages API over plain HTTP: the app has no libraries, so no SDK.
     * The answer is the reply's text blocks; a refusal or any failure means "no teacher this time".
     */
    private String askClaude(String key, String system, String user) {
        String chosen = settings.getString("claudeModel", "").trim();
        String model = chosen.startsWith("claude-") ? chosen : ANTHROPIC.models[0];
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", 4000);                               // room for thinking and the short JSON verdict
            body.put("system", system + "\nReply with the JSON object only, no other text.");
            body.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", user)));
            body.put("output_config", new JSONObject().put("effort", "low"));   // a quick check, not deep work
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("x-api-key", key);
            headers.put("anthropic-version", "2026-10-21");
            Http r = http("POST", ANTHROPIC.url, null, body.toString(), headers);
            if (r.code != 200) {
                lastProblem = explain(r, ANTHROPIC.name);
                if (r.code == 401 || r.code == 403) {
                    if (announce && !badKeySaid) { badKeySaid = true; final String why = lastProblem; main.post(() -> problem.setText(why + "\nTab tak main khud jawab dungi.")); }
                }
                return null;
            }
            JSONObject reply = new JSONObject(r.body);
            if ("refusal".equals(reply.optString("stop_reason"))) { lastProblem = "Claude ne is baat ka jawab dene se mana kar diya."; return null; }
            StringBuilder text = new StringBuilder();
            JSONArray content = reply.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) text.append(block.optString("text"));
            }
            String out = text.toString().trim();
            // a JSON object, even if the model wrapped it in a code fence
            int a = out.indexOf('{'), b = out.lastIndexOf('}');
            if (a >= 0 && b > a) out = out.substring(a, b + 1);
            lastProblem = "";
            settings.edit().putString("worked_" + ANTHROPIC.name, model).apply();
            if (badKeySaid) { badKeySaid = false; main.post(() -> problem.setText("")); }
            return out;
        } catch (Exception e) {
            lastProblem = "Claude tak pohanch nahi saki (" + e.getClass().getSimpleName() + "). Internet check karein.";
            return null;
        }
    }

    /** Is a 403 about this phone's location or network, rather than about one model? */
    private static boolean blockedEverywhere(Http r) {
        String b = r.body == null ? "" : r.body.toLowerCase();
        return b.contains("access denied") || b.contains("network settings") || b.contains("region") || b.contains("country") || b.contains("location");
    }

    /** Forget a free model that refused, so it is not tried again until the list is next fetched. */
    private void dropModel(String model) {
        String cached = settings.getString("freeModels", "");
        if (cached.contains(model + ",")) settings.edit().putString("freeModels", cached.replace(model + ",", "")).apply();
    }

    private static class Http { int code; String body; }

    /**
     * OpenRouter's free models, newest list from OpenRouter itself (kept for a
     * day), the strongest-looking first. Free models come and go too often to
     * name them in the code.
     */
    private java.util.List<String> freeModels() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String cached = settings.getString("freeModels", "");
        long at = settings.getLong("freeModelsAt", 0);
        if (!cached.isEmpty() && System.currentTimeMillis() - at < 24L * 3600 * 1000) {
            for (String m : cached.split(",")) if (!m.isEmpty()) out.add(m);
            return out;
        }
        try {
            Http r = http("GET", "https://openrouter.ai/api/v1/models", null, null);
            if (r.code != 200) return out;
            JSONArray all = new JSONObject(r.body).getJSONArray("data");
            java.util.List<JSONObject> free = new java.util.ArrayList<>();
            for (int i = 0; i < all.length(); i++) {
                JSONObject m = all.getJSONObject(i), price = m.optJSONObject("pricing");
                String id = m.optString("id");
                boolean zero = price != null && "0".equals(price.optString("prompt")) && "0".equals(price.optString("completion"));
                JSONObject arch = m.optJSONObject("architecture");
                String outputs = arch == null ? "" : String.valueOf(arch.opt("output_modalities"));
                if ((id.endsWith(":free") || zero) && !id.startsWith("openrouter/") && (outputs.isEmpty() || outputs.contains("text"))) free.add(m);
            }
            // prefer big general models, then whatever has the most context
            final String[] liked = {"deepseek", "llama", "qwen", "gpt-oss", "glm", "gemini", "gemma", "mistral", "kimi"};
            java.util.Collections.sort(free, (a, b) -> {
                int ra = rank(a.optString("id"), liked), rb = rank(b.optString("id"), liked);
                if (ra != rb) return ra - rb;
                return Long.compare(b.optLong("context_length"), a.optLong("context_length"));
            });
            StringBuilder keep = new StringBuilder();
            for (JSONObject m : free) { out.add(m.optString("id")); keep.append(m.optString("id")).append(','); }
            settings.edit().putString("freeModels", keep.toString()).putLong("freeModelsAt", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
            // offline: the next request will try again
        }
        return out;
    }

    private static int rank(String id, String[] liked) {
        for (int i = 0; i < liked.length; i++) if (id.contains(liked[i])) return i;
        return liked.length;
    }

    /** Turn Groq's refusal into a sentence the user can act on, with Groq's own words attached. */
    private static String explain(Http r, String name) {
        String msg = "";
        try { msg = new JSONObject(r.body).getJSONObject("error").optString("message"); } catch (Exception ignored) { }
        if (msg.isEmpty()) {
            try { msg = new JSONArray(r.body).getJSONObject(0).getJSONObject("error").optString("message"); } catch (Exception ignored) { }
        }
        if (msg.isEmpty()) msg = r.body == null ? "" : r.body.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        if (msg.length() > 160) msg = msg.substring(0, 160) + "...";
        String head;
        if (r.code == 401 || (r.code == 400 && msg.toLowerCase().contains("api key"))) head = name + " ne key ghalat batayi. Website se key dobara copy kar ke paste karein.";
        else if (r.code == 403 && blockedEverywhere(r)) head = name + " ne darwaza band kiya (403). Key shayad theek hai, lekin " + name + " is mulk ya network se seedhi request nahi le raha. VPN on kar ke dekhein, ya doosri service ki key lagayein.";
        else if (r.code == 403) head = name + " ne yeh model is app ko nahi diya (403).";
        else if (r.code == 402) head = name + " ke account mein credit nahi, aur yeh model muft nahi. Settings mein model ka khana khali chhor dein.";
        else if (r.code == 404) head = "Yeh model " + name + " par maujood nahi. Settings mein model ka khana khali chhor dein.";
        else if (r.code == 429) head = name + " ki muft had poori ho gayi. Thori der baad chale ga.";
        else head = name + " ne jawab nahi diya (" + r.code + ").";
        return msg.isEmpty() ? head : head + "\n" + name + ": " + msg;
    }

    private Http http(String method, String address, String auth, String body) throws Exception {
        return http(method, address, auth, body, null);
    }

    private Http http(String method, String address, String auth, String body, java.util.Map<String, String> headers) throws Exception {
        lastCode = -1;
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        if (headers != null) for (java.util.Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "BoltaMunshi/3.1 (Android)");
        if (auth != null) c.setRequestProperty("Authorization", auth);
        if (address.contains("api.github.com")) c.setRequestProperty("Accept", "application/vnd.github+json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        Http r = new Http();
        r.code = c.getResponseCode();
        lastCode = r.code;
        InputStream in = r.code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            in.close();
        }
        r.body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        c.disconnect();
        return r;
    }

    // ----------------------------------------------------- shared lessons ---

    private String branch() {
        String b = settings.getString("branch", "").trim();
        return b.isEmpty() ? DEFAULT_BRANCH : b;
    }

    /** Fetch the lessons everyone shares from GitHub and add them to this phone's own. */
    private void pullShared() {
        final String url = "https://raw.githubusercontent.com/" + REPO + "/" + branch() + "/" + SHARED_PATH;
        net.execute(() -> {
            try {
                Http r = http("GET", url, null, null);
                if (r.code != 200) return;
                main.post(() -> brain.evaluateJavascript("absorb(" + JSONObject.quote(r.body) + ")", raw -> {
                    try {
                        String db = (String) new JSONTokener(raw).nextValue();
                        if (!db.isEmpty()) writeSaved(db);
                    } catch (Exception ignored) { }
                }));
            } catch (Exception ignored) {
                // offline: try again next time the app opens
            }
        });
    }

    /**
     * With a GitHub token in the settings, add this phone's shareable lessons
     * to the shared file on GitHub. Only shapes with names and numbers left
     * open are sent, never anyone's accounts.
     */
    private void pushShared() {
        final String token = settings.getString("githubToken", "").trim();
        if (token.isEmpty() || !brainReady) return;
        final String api = "https://api.github.com/repos/" + REPO + "/contents/" + SHARED_PATH, b = branch();
        net.execute(() -> {
            try {
                Http got = http("GET", api + "?ref=" + java.net.URLEncoder.encode(b, "UTF-8"), "Bearer " + token, null);
                String remote = "", sha = null;
                if (got.code == 200) {
                    JSONObject f = new JSONObject(got.body);
                    sha = f.optString("sha", null);
                    remote = new String(Base64.decode(f.optString("content", ""), Base64.DEFAULT), StandardCharsets.UTF_8);
                } else if (got.code != 404) return;
                final String remoteText = remote, remoteSha = sha;
                main.post(() -> brain.evaluateJavascript("shareFile(" + JSONObject.quote(remoteText) + ")", raw -> {
                    String merged;
                    try { merged = (String) new JSONTokener(raw).nextValue(); } catch (Exception e) { return; }
                    if (merged.isEmpty()) return;                   // nothing new to share
                    net.execute(() -> {
                        try {
                            JSONObject put = new JSONObject();
                            put.put("message", "Bolta Munshi: add shared lessons");
                            put.put("content", Base64.encodeToString(merged.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                            put.put("branch", b);
                            if (remoteSha != null) put.put("sha", remoteSha);
                            http("PUT", api, "Bearer " + token, put.toString());
                        } catch (Exception ignored) {
                            // the next call will try again
                        }
                    });
                }));
            } catch (Exception ignored) {
                // offline: the next call will try again
            }
        });
    }

    // ----------------------------------------------------------- settings ---

    private static final String[] BRAIN_LETTERS = {"C", "O", "G", "Q"};
    private static final int[] BRAIN_TINTS = {0xFFD9A679, 0xFF9AA6FF, 0xFF7FD4C1, 0xFFF0A3A3};
    private static final String[] BRAIN_HINTS = {"sk-ant-...", "sk-or-...", "AIza...", "gsk_..."};
    // "eleven_v4" is the id ElevenLabs puts in the names of files made with v4 on its website
    private static final String[] ELEVEN_IDS = {"eleven_turbo_v2_5", "eleven_flash_v2_5", "eleven_multilingual_v2", "eleven_v3", "eleven_v4"};
    private static final String[] ELEVEN_NAMES = {"Turbo v2.5", "Flash v2.5", "Multilingual v2", "v3", "v4"};
    private static final String[] ELEVEN_NOTES = {"Fast and good · works with Tara", "Fastest · fewest credits", "Clearest voice · a little slower", "Expressive · slower", "Newest · most emotional range"};

    private String elevenModelName() {
        String id = settings.getString("elevenModel", ElevenLabs.MODEL);
        for (int i = 0; i < ELEVEN_IDS.length; i++) if (ELEVEN_IDS[i].equals(id)) return ELEVEN_NAMES[i];
        return id;
    }

    /** Opened by three quick taps and then any stroke on the screen. */
    private void openSettings() {
        if (inCall) hangUp();
        Sheet.Page p = Sheet.INSTANCE.open(this, "Settings", null, null);
        LinearLayout g = Sheet.INSTANCE.group(p.getBody());
        int working = 0, keys = 0;
        for (String slot : KEY_SLOTS) {
            if (settings.getString(slot, "").trim().isEmpty() || !settings.getBoolean("on_" + slot, true)) continue;
            keys++;
            if ("ok".equals(settings.getString("st_" + slot, ""))) working++;
        }
        Sheet.INSTANCE.row(g, "AI", 0xFFD9A679, "Brain", keys == 0 ? "No AI key yet" : working + " of " + keys + " working", "›", 0xFF5D5D66, null,
                () -> { p.getDialog().dismiss(); openBrain(); });
        String voiceName = settings.getString("elevenVoiceName", "");
        Sheet.INSTANCE.row(g, "11", 0xFF9AA6FF, "ElevenLabs", (voiceName.isEmpty() ? "No voice chosen" : voiceName.split(" ")[0]) + " · " + elevenModelName()
                + (settings.getBoolean("liveVoice", true) ? "" : " · off"), "›", 0xFF5D5D66, null, () -> { p.getDialog().dismiss(); openElevenSettings(); });
        VoiceData vd = new VoiceData(this);
        Sheet.INSTANCE.row(g, "T", 0xFF7FD4C1, "Training", "Offline voice · " + vd.doneCount() + " / " + vd.getSentences().size() + " sentences", "›", 0xFF5D5D66, null,
                () -> { p.getDialog().dismiss(); openTraining(); });
        LinearLayout g2 = Sheet.INSTANCE.group(p.getBody());
        Sheet.INSTANCE.row(g2, "V", 0xFFF0A3A3, "Voice", "Word clips · " + clips.recorded().size() + " words", "›", 0xFF5D5D66, null, () -> {
            p.getDialog().dismiss();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, ASK_MIC_RECORD);
                return;
            }
            openRecorder();
        });
        final Sheet.Row[] phone = new Sheet.Row[1];
        phone[0] = Sheet.INSTANCE.row(g2, "Aa", 0xFFC7C7D1, "Phone voice", phoneVoiceLine(), "⇄", 0xFF8E8E99, null, () -> {
            settings.edit().putString("voiceLang", script == 2 ? "ur" : "hi").remove("voice").apply();
            if (ttsReady) chooseLanguage();
            phone[0].plain(phoneVoiceLine());
            say(new String[]{"Assalam o alaikum! Main Smart Book hoon.", "السلام علیکم! میں اسمارٹ بک ہوں۔", "अस्सलाम अलैकुम! मैं स्मार्ट बुक हूं।"}, null, true);
        });
        Sheet.INSTANCE.row(g2, "↻", 0xFFC7C7D1, "Try another phone voice", "Cycles the voices installed on the phone", null, 0, null, () -> nextVoice());
        if (urduState < TextToSpeech.LANG_AVAILABLE) {
            Sheet.INSTANCE.row(g2, "↓", 0xFFC7C7D1, "Get the Urdu voice", "Opens Google's voice download", "↗", 0xFF8E8E99, null, () -> installUrdu());
        }
        Sheet.INSTANCE.row(g2, "G", 0xFFC7C7D1, "GitHub sync", settings.getString("githubToken", "").isEmpty() ? "Shared lessons · not set" : "Shared lessons · on", "›", 0xFF5D5D66, null,
                () -> { p.getDialog().dismiss(); openGithub(); });
        Sheet.INSTANCE.primary(p.getBody(), "Done", () -> p.getDialog().dismiss());
        p.getDialog().show();
    }

    private String phoneVoiceLine() {
        return (script == 2 ? "Hindi voice" : script == 1 ? "Urdu voice" : "English voice") + " · used when offline";
    }

    private void openGithub() {
        Sheet.Page p = Sheet.INSTANCE.open(this, "GitHub sync", "Shares what the app learns · optional", this::openSettings);
        LinearLayout g = Sheet.INSTANCE.group(p.getBody());
        EditText token = Sheet.INSTANCE.field(this, "GitHub token", settings.getString("githubToken", ""), true);
        EditText branch = Sheet.INSTANCE.field(this, "Branch (empty = " + DEFAULT_BRANCH + ")", settings.getString("branch", ""), false);
        int pad = Sheet.INSTANCE.dp(this, 12f);
        g.setPadding(pad, pad, pad, pad);
        g.addView(token, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Sheet.INSTANCE.dp(this, 44f)));
        View space = new View(this);
        g.addView(space, new LinearLayout.LayoutParams(1, pad));
        g.addView(branch, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Sheet.INSTANCE.dp(this, 44f)));
        Sheet.INSTANCE.primary(p.getBody(), "Save", () -> {
            settings.edit().putString("githubToken", token.getText().toString().trim()).putString("branch", branch.getText().toString().trim()).apply();
            pullShared();
            p.getDialog().dismiss();
            openSettings();
        });
        p.getDialog().show();
    }

    /** One line per AI service: name, status, on/off; tap the name to enter its key. Tried top to bottom. */
    private void openBrain() {
        Sheet.Page p = Sheet.INSTANCE.open(this, "Brain", "Tried top to bottom; if one fails, the next takes over", this::openSettings);
        LinearLayout g = Sheet.INSTANCE.group(p.getBody());
        int firstEmpty = -1;
        for (int i = 0; i < KEY_SLOTS.length; i++) if (firstEmpty < 0 && settings.getString(KEY_SLOTS[i], "").trim().isEmpty()) firstEmpty = i;
        for (int i = 0; i < KEY_SLOTS.length; i++) {
            final int n = i;
            final String slot = KEY_SLOTS[i];
            final Service svc = KEY_SERVICES[i];
            Sheet.Toggle sw = new Sheet.Toggle(this, settings.getBoolean("on_" + slot, true));
            final LinearLayout[] keyLine = new LinearLayout[1];
            final Sheet.Row row = Sheet.INSTANCE.row(g, BRAIN_LETTERS[i], BRAIN_TINTS[i], svc.name, "", null, 0, sw,
                    () -> keyLine[0].setVisibility(keyLine[0].getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            Runnable paint = () -> { row.status(statusText(slot), statusColor(slot)); row.getView().setAlpha(settings.getBoolean("on_" + slot, true) ? 1f : 0.55f); };
            paint.run();
            keyLine[0] = Sheet.INSTANCE.keyLine(g, BRAIN_HINTS[i], settings.getString(slot, ""), true, true, text -> {
                SharedPreferences.Editor e = settings.edit().putString(slot, text).putBoolean("on_" + slot, true);
                if (text.isEmpty()) { e.remove("st_" + slot).apply(); sw.set(true); paint.run(); return; }
                if (serviceFor(text) != svc) { e.putString("st_" + slot, "wrong").apply(); sw.set(true); paint.run(); return; }
                e.putString("st_" + slot, "checking").apply();
                sw.set(true);
                restingUntil.remove(svc.name);
                paint.run();
                net.execute(() -> {
                    announce = false;
                    tryBrain(n, text, "Reply with JSON only: {\"verdict\": \"ok\"}", "Heard: salam\nDraft reply: Wa alaikum assalam");
                    announce = true;
                    main.post(paint);
                });
            });
            keyLine[0].setVisibility(i == (firstEmpty < 0 ? 0 : firstEmpty) ? View.VISIBLE : View.GONE);
            sw.setOnChange(on -> { settings.edit().putBoolean("on_" + slot, on).apply(); restingUntil.remove(svc.name); paint.run(); });
        }
        Sheet.INSTANCE.note(p.getBody(), "Tap a name to enter its key");
        Sheet.INSTANCE.primary(p.getBody(), "OK", () -> { p.getDialog().dismiss(); badKeySaid = false; problem.setText(""); openSettings(); });
        p.getDialog().show();
    }

    /** Kept so older calls still work: checks every key and shows the Brain screen. */
    private void checkKey() { openBrain(); }

    /** ElevenLabs: key with its status, the voice, one of four models, and whether replies use it live. */
    private void openElevenSettings() {
        Sheet.Page p = Sheet.INSTANCE.open(this, "ElevenLabs", "Natural voice while online", this::openSettings);
        LinearLayout g = Sheet.INSTANCE.group(p.getBody());
        final Sheet.Row[] st = new Sheet.Row[1];
        Sheet.INSTANCE.keyLine(g, "sk_...", settings.getString("elevenKey", ""), true, false, text -> {
            settings.edit().putString("elevenKey", text).apply();
            liveProblemShown = false;
            checkEleven(st[0]);
        });
        st[0] = Sheet.INSTANCE.statusLine(g);
        if (settings.getString("elevenKey", "").isEmpty()) st[0].status("No key", Sheet.DIM);
        else checkEleven(st[0]);
        String voiceName = settings.getString("elevenVoiceName", "");
        Sheet.INSTANCE.row(g, null, 0, "Voice", voiceName.isEmpty() ? "Not chosen" : voiceName, "Change", Sheet.AMBER, null, () -> {
            String key = settings.getString("elevenKey", "");
            if (key.isEmpty()) { st[0].status("Save a key first", Sheet.AMBER); return; }
            st[0].status("Loading voices…", Sheet.BLUE);
            new Thread(() -> {
                kotlin.Pair<java.util.List<ElevenLabs.Voice>, String> got = ElevenLabs.INSTANCE.voices(key);
                main.post(() -> {
                    java.util.List<ElevenLabs.Voice> list = got.getFirst();
                    if (list.isEmpty()) { st[0].status(got.getSecond().isEmpty() ? "No voices on this account" : got.getSecond(), Sheet.RED); return; }
                    p.getDialog().dismiss();
                    Sheet.Page pick = Sheet.INSTANCE.open(this, "Choose a voice", list.size() + " voices on this account", this::openElevenSettings);
                    LinearLayout vg = Sheet.INSTANCE.group(pick.getBody());
                    for (ElevenLabs.Voice v : list) {
                        Sheet.INSTANCE.row(vg, null, 0, v.getName(), null, null, 0, null, () -> {
                            settings.edit().putString("elevenVoice", v.getId()).putString("elevenVoiceName", v.getName()).apply();
                            pick.getDialog().dismiss();
                            openElevenSettings();
                            say(new String[]{"Assalam o alaikum! Main Smart Book hoon.", "السلام علیکم! میں اسمارٹ بک ہوں۔", "अस्सलाम अलैकुम! मैं स्मार्ट बुक हूं।"}, null);
                        });
                    }
                    pick.getDialog().show();
                });
            }).start();
        });
        Sheet.INSTANCE.label(p.getBody(), "MODEL");
        LinearLayout mg = Sheet.INSTANCE.group(p.getBody());
        String current = settings.getString("elevenModel", ElevenLabs.MODEL);
        final Sheet.Radio[] radios = new Sheet.Radio[ELEVEN_IDS.length];
        for (int i = 0; i < ELEVEN_IDS.length; i++) {
            final int n = i;
            radios[i] = new Sheet.Radio(this, ELEVEN_IDS[i].equals(current));
            Sheet.INSTANCE.row(mg, null, 0, ELEVEN_NAMES[i], ELEVEN_NOTES[i], null, 0, radios[i], () -> {
                settings.edit().putString("elevenModel", ELEVEN_IDS[n]).apply();
                for (int j = 0; j < radios.length; j++) radios[j].set(j == n);
                liveProblemShown = false;
            });
        }
        LinearLayout lg = Sheet.INSTANCE.group(p.getBody());
        Sheet.Toggle liveSw = new Sheet.Toggle(this, settings.getBoolean("liveVoice", true));
        liveSw.setOnChange(on -> settings.edit().putBoolean("liveVoice", on).apply());
        Sheet.INSTANCE.row(lg, null, 0, "Use in conversation", "Saved lines play offline too", null, 0, liveSw, null);
        Sheet.INSTANCE.primary(p.getBody(), "OK", () -> {
            p.getDialog().dismiss();
            openSettings();
            say(new String[]{"Assalam o alaikum! Main Smart Book hoon.", "السلام علیکم! میں اسمارٹ بک ہوں۔", "अस्सलाम अलैकुम! मैं स्मार्ट बुक हूं।"}, null);
        });
        p.getDialog().show();
    }

    /** The ElevenLabs key's status line: working with credits left, or what is wrong. */
    private void checkEleven(Sheet.Row line) {
        String key = settings.getString("elevenKey", "");
        if (key.isEmpty()) { line.status("No key", Sheet.DIM); return; }
        line.status("Checking…", Sheet.BLUE);
        new Thread(() -> {
            kotlin.Pair<Long, String> got = ElevenLabs.INSTANCE.credits(key);
            main.post(() -> {
                Long left = got.getFirst();
                if (left == null) line.status(got.getSecond(), got.getSecond().startsWith("No internet") ? Sheet.AMBER : Sheet.RED);
                else if (left < 0) line.status(got.getSecond(), Sheet.GREEN);
                else line.status(left <= 0 ? "No credits left this month" : "Working · " + String.format(java.util.Locale.US, "%,d", left) + " characters left", left <= 0 ? Sheet.AMBER : Sheet.GREEN);
            });
        }).start();
    }

    /** Training the offline voice in three steps. */
    private void openTraining() {
        Sheet.Page p = Sheet.INSTANCE.open(this, "Training", "Your own offline voice in 3 steps", this::openSettings);
        LinearLayout g = Sheet.INSTANCE.group(p.getBody());
        VoiceData vd = new VoiceData(this);
        int done = vd.doneCount(), all = vd.getSentences().size();
        boolean full = done >= all && all > 0;
        Sheet.INSTANCE.row(g, full ? "✓" : "1", full ? Sheet.GREEN : Sheet.AMBER, "1. Make sentences", done + " / " + all + " done with ElevenLabs", "›", 0xFF5D5D66, null,
                () -> { p.getDialog().dismiss(); openVoiceData(); });
        final Sheet.Row[] zip = new Sheet.Row[1];
        zip[0] = Sheet.INSTANCE.row(g, "2", done > 0 ? Sheet.AMBER : Sheet.OFF, "2. Export zip", "Put it in Google Drive › SmartBook", "Export", Sheet.AMBER, null, () -> {
            if (vd.doneCount() == 0) { zip[0].status("Make some sentences first", Sheet.AMBER); return; }
            zip[0].status("Making the zip…", Sheet.BLUE);
            new Thread(() -> {
                String where;
                try { where = vd.export(); } catch (Exception e) { where = null; }
                final String w = where;
                main.post(() -> { if (w == null) zip[0].status("Could not make the zip · check storage", Sheet.RED); else zip[0].status("Saved · " + w, Sheet.GREEN); });
            }).start();
        });
        Sheet.INSTANCE.row(g, "3", Sheet.OFF, "3. Train in Colab", "Opens the notebook", "↗", 0xFF8E8E99, null, () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://colab.research.google.com/github/abrar1499/AI-GAMES/blob/claude/new-session-fb21rr/bolta-munshi/voice-model/smart-book-train.ipynb"))); }
            catch (Exception e) { zip[0].status("No browser found", Sheet.RED); }
        });
        Sheet.INSTANCE.primary(p.getBody(), "OK", () -> { p.getDialog().dismiss(); openSettings(); });
        p.getDialog().show();
    }

    private void showNote(String text) {
        new AlertDialog.Builder(this).setTitle("ElevenLabs").setMessage(text).setPositiveButton("Theek hai", null).show();
    }

    private void showResult(String text) {
        new AlertDialog.Builder(this).setTitle("AI ustad").setMessage(text).setPositiveButton("Theek hai", null).show();
    }

    /** The voice part of the settings: which voice speaks now, a way to get the Urdu one, and a way to try others. */
    private void addVoiceRows(LinearLayout box, float dp) {
        TextView now = new TextView(this);
        now.setPadding(0, (int) (16 * dp), 0, (int) (6 * dp));
        now.setText(voiceLine());
        box.addView(now);
        if (urduState < TextToSpeech.LANG_AVAILABLE) {
            android.widget.Button install = new android.widget.Button(this);
            install.setText(urduState == TextToSpeech.LANG_MISSING_DATA ? "Urdu awaaz download karein" : "Google ki awaaz (Urdu ke liye) lein");
            install.setOnClickListener(v -> installUrdu());
            box.addView(install);
        }
        // Urdu or Hindi voice: they speak the same language, and on many phones the Hindi voice sounds better
        android.widget.Button lang = new android.widget.Button(this);
        lang.setText(script == 2 ? "Urdu awaaz istemal karein" : "Hindi awaaz istemal karein");
        lang.setOnClickListener(v -> {
            settings.edit().putString("voiceLang", script == 2 ? "ur" : "hi").remove("voice").apply();
            chooseLanguage();
            lang.setText(script == 2 ? "Urdu awaaz istemal karein" : "Hindi awaaz istemal karein");
            now.setText(voiceLine());
            say(new String[]{"Assalam o alaikum! Main Smart Book hoon.", "السلام علیکم! میں اسمارٹ بک ہوں۔", "अस्सलाम अलैकुम! मैं स्मार्ट बुक हूं।"}, null);
        });
        box.addView(lang);
        android.widget.Button other = new android.widget.Button(this);
        other.setText("Doosri awaaz sunein");
        other.setOnClickListener(v -> { nextVoice(); now.setText(voiceLine()); });
        box.addView(other);
        android.widget.Button liveBtn = new android.widget.Button(this);
        liveBtn.setText(settings.getBoolean("liveVoice", true) ? "ElevenLabs awaaz (internet): CHALU" : "ElevenLabs awaaz (internet): BAND");
        liveBtn.setOnClickListener(v -> {
            boolean on = !settings.getBoolean("liveVoice", true);
            settings.edit().putBoolean("liveVoice", on).apply();
            liveBtn.setText(on ? "ElevenLabs awaaz (internet): CHALU" : "ElevenLabs awaaz (internet): BAND");
            if (on && settings.getString("elevenKey", "").isEmpty()) showNote("Pehle Apni awaaz → ElevenLabs se awaaz banwayein mein key aur awaaz save karein.");
            else say(new String[]{"Assalam o alaikum! Main Smart Book hoon.", "السلام علیکم! میں اسمارٹ بک ہوں۔", "अस्सलाम अलैकुम! मैं स्मार्ट बुक हूं।"}, null);
        });
        box.addView(liveBtn);
        android.widget.Button mine = new android.widget.Button(this);
        int have = clips.mine().size();
        mine.setText(have == 0 ? "Apni awaaz record karein" : "Apni awaaz (" + have + " record)");
        mine.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, ASK_MIC_RECORD);
                return;
            }
            openRecorder();
        });
        box.addView(mine);
    }

    /**
     * The recording screen: one word or phrase at a time, in big letters. Record stops by itself
     * when the user goes quiet, plays it back, and goes on to the next one until Ruk is pressed.
     * Words the munshi needed but had no recording for come first.
     */
    private void openRecorder() {
        stopSpeaking();
        float dp = getResources().getDisplayMetrics().density;
        final java.util.List<String> list = new java.util.ArrayList<>();
        try {
            JSONObject counts = new JSONObject(settings.getString("missingWords", "{}"));
            java.util.List<String> needed = new java.util.ArrayList<>();
            for (java.util.Iterator<String> it = counts.keys(); it.hasNext(); ) {
                String w = it.next();
                if (!clips.has(w) && !MyVoice.INSTANCE.getPHRASES().contains(w)) needed.add(w);
            }
            java.util.Collections.sort(needed, (a, b) -> counts.optInt(b) - counts.optInt(a));
            list.addAll(needed.subList(0, Math.min(30, needed.size())));
        } catch (Exception ignored) { }
        list.addAll(MyVoice.INSTANCE.getPHRASES());
        final int[] at = {0};
        while (at[0] < list.size() - 1 && clips.has(list.get(at[0]))) at[0]++;
        final boolean[] going = {false};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView count = new TextView(this);
        box.addView(count);
        TextView word = new TextView(this);
        word.setTextSize(32);
        word.setGravity(Gravity.CENTER);
        word.setPadding(0, (int) (24 * dp), 0, (int) (8 * dp));
        box.addView(word);
        TextView hint = new TextView(this);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, (int) (16 * dp));
        box.addView(hint);
        LinearLayout row1 = new LinearLayout(this), row2 = new LinearLayout(this);
        android.widget.Button rec = new android.widget.Button(this), play = new android.widget.Button(this);
        android.widget.Button back = new android.widget.Button(this), next = new android.widget.Button(this);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row1.addView(rec, half); row1.addView(play, new LinearLayout.LayoutParams(half));
        row2.addView(back, new LinearLayout.LayoutParams(half)); row2.addView(next, new LinearLayout.LayoutParams(half));
        box.addView(row1); box.addView(row2);
        android.widget.Button onOff = new android.widget.Button(this), test = new android.widget.Button(this), wipe = new android.widget.Button(this);
        android.widget.Button eleven = new android.widget.Button(this);
        eleven.setText("ElevenLabs se awaaz banwayein");
        android.widget.Button data = new android.widget.Button(this);
        data.setText("Offline awaaz ke liye data (ElevenLabs)");
        data.setOnClickListener(v -> { if (!going[0] && !clips.getRecording()) openVoiceData(); });
        android.widget.Button share = new android.widget.Button(this);
        share.setText("Saare lafz ki file banayein (app mein pakka karne ke liye)");
        share.setOnClickListener(v -> {
            if (going[0] || clips.getRecording()) return;
            hint.setText("File ban rahi hai...");
            new Thread(() -> {
                String where;
                try { where = clips.export(); } catch (Exception e) { where = null; }
                final String w2 = where;
                main.post(() -> { hint.setText(""); showNote(w2 == null ? "File nahi ban saki." : "File ban gayi: " + w2 + "\nYe file bhej dein, saare lafz app ke andar pakke daal diye jayenge."); });
            }).start();
        });
        box.addView(test); box.addView(eleven); box.addView(data); box.addView(share); box.addView(onOff); box.addView(wipe);
        back.setText("◀ Pichla"); next.setText("Agla ▶"); play.setText("▶ Sunein");
        test.setText("Ek jumla meri awaaz mein sunein");
        wipe.setText("Saari recording mitayein");

        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            String w = list.get(at[0]);
            boolean has = clips.has(w);
            word.setText(w);
            word.setTextColor(has ? 0xFF4CAF50 : 0xFFFFFFFF);
            int fromEleven = 0;
            try {
                JSONObject made = new JSONObject(settings.getString("elevenClips", "{}"));
                for (String k : clips.mine()) if (made.has(k)) fromEleven++;
            } catch (Exception ignored) { }
            int own = clips.mine().size() - fromEleven;
            count.setText("Aap ki apni recording: " + own + "   ElevenLabs se: " + fromEleven + "   (kul lafz " + list.size() + ")\nYeh " + (at[0] + 1) + " number"
                    + (has ? (made(w) ? " · ElevenLabs ki awaaz" : " · aap ki recording") : clips.recorded().contains(w) ? " · app ki awaaz" : " · abhi koi awaaz nahi"));
            if (!clips.getRecording()) hint.setText(has ? (made(w) ? "✓ ElevenLabs ne bana diya. Apni awaaz chahiye to Record dabayein." : "✓ Record ho chuka. Dobara karna ho to Record dabayein.") : "Record dabayein aur yeh lafz apne andaaz mein bolein. Chup hone par khud ruk jayegi.");
            rec.setText(going[0] || clips.getRecording() ? "■ Ruk jayein" : "● Record");
            play.setEnabled(clips.recorded().contains(w));
            onOff.setText(settings.getBoolean("myVoice", true) ? "Apni awaaz: CHALU (band karein)" : "Apni awaaz: BAND (chalu karein)");
        };
        final Runnable[] recordOne = new Runnable[1];
        recordOne[0] = () -> {
            final String w = list.get(at[0]);
            clips.record(pcm -> {
                if (pcm.length == 0) {
                    going[0] = false;
                    refresh[0].run();
                    hint.setText("Awaaz nahi aayi. Phone mooh ke qareeb rakh kar dobara Record dabayein.");
                    return kotlin.Unit.INSTANCE;
                }
                clips.save(w, pcm);
                try {   // the user's own now, not ElevenLabs'
                    JSONObject made = new JSONObject(settings.getString("elevenClips", "{}"));
                    made.remove(w);
                    settings.edit().putString("elevenClips", made.toString()).apply();
                } catch (Exception ignored) { }
                refresh[0].run();
                clips.play(java.util.Collections.singletonList(new MyVoice.Part.Clip(w)), () -> {
                    if (!going[0]) return;
                    if (at[0] < list.size() - 1) { at[0]++; recordOne[0].run(); } else { going[0] = false; refresh[0].run(); }
                });
                return kotlin.Unit.INSTANCE;
            });
            refresh[0].run();
            hint.setText("🎙 Bolein: " + w);
        };
        rec.setOnClickListener(v -> {
            if (going[0] || clips.getRecording()) { going[0] = false; clips.stopRecording(); clips.stopPlaying(); refresh[0].run(); return; }
            going[0] = true;
            recordOne[0].run();
        });
        play.setOnClickListener(v -> clips.play(java.util.Collections.singletonList(new MyVoice.Part.Clip(list.get(at[0]))), () -> { }));
        back.setOnClickListener(v -> { if (going[0] || clips.getRecording()) return; if (at[0] > 0) at[0]--; refresh[0].run(); });
        next.setOnClickListener(v -> { if (going[0] || clips.getRecording()) return; if (at[0] < list.size() - 1) at[0]++; refresh[0].run(); });
        onOff.setOnClickListener(v -> { settings.edit().putBoolean("myVoice", !settings.getBoolean("myVoice", true)).apply(); refresh[0].run(); });
        test.setOnClickListener(v -> {
            if (going[0] || clips.getRecording()) return;
            String[] line = {"Petrol ke 1,500 rupay likh liye. Aur kuch?", "پیٹرول کے 1500 روپے لکھ لیے۔ اور کچھ؟", "पेट्रोल के 1500 रुपये लिख लिए। और कुछ?"};
            MyVoice.Plan plan = MyVoice.INSTANCE.plan(line[0], clips.recorded());
            if (!plan.getAnyClip()) { hint.setText("Is jumle ka abhi koi lafz record nahi hua, is liye phone ki awaaz aayegi."); }
            else if (plan.getMissing().isEmpty()) { hint.setText("Poora jumla aap ki awaaz mein."); }
            else {
                hint.setText("Aap ki awaaz mein, magar yeh lafz abhi record nahi, yeh phone ki awaaz mein aayenge: "
                        + android.text.TextUtils.join(", ", plan.getMissing())
                        + (plan.getUsable() ? "" : "\nBaat cheet mein app aap ki awaaz tab istemal karti hai jab jumle ke kam az kam aadhe lafz record hon."));
            }
            say(line, null, true);
        });
        eleven.setOnClickListener(v -> { if (!going[0] && !clips.getRecording()) openElevenLabs(refresh[0]); });
        wipe.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Saari recording mita doon? Phir app apni banai hui awaaz mein bolegi.")
                .setPositiveButton("Haan, mita dein", (d, w) -> { clips.deleteAll(); at[0] = 0; refresh[0].run(); })
                .setNegativeButton("Nahi", null).show());
        refresh[0].run();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Apni awaaz")
                .setView(scroll)
                .setPositiveButton("Ho gaya", null)
                .setOnDismissListener(d -> { going[0] = false; clips.stopRecording(); clips.stopPlaying(); })
                .show();
    }

    /** Whether ElevenLabs made this word (rather than the user recording it). */
    private boolean made(String word) {
        try { return new JSONObject(settings.getString("elevenClips", "{}")).has(word); } catch (Exception e) { return false; }
    }

    /** ElevenLabs makes every word on the list once, in the voice picked there; they are kept like recordings. */
    private void openElevenLabs(Runnable refresh) {
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView about = new TextView(this);
        about.setText("ElevenLabs list ke saare lafz ek dafa bana dega. Phir app internet ke baghair unhi se bolegi. Key sirf yahan likhein, kisi ko na bhejein.");
        box.addView(about);
        EditText key = field(box, "ElevenLabs key", settings.getString("elevenKey", ""), true);
        EditText voice = field(box, "Voice ID (neeche button se chunein)", settings.getString("elevenVoice", ""), false);
        EditText model = field(box, "Model: eleven_turbo_v2_5 (tez), eleven_flash_v2_5 (sab se tez, sasta), eleven_multilingual_v2 (behtareen)", settings.getString("elevenModel", ElevenLabs.MODEL), false);
        TextView voiceName = new TextView(this);
        voiceName.setText(settings.getString("elevenVoiceName", "").isEmpty() ? "Abhi koi awaaz nahi chuni." : "Awaaz: " + settings.getString("elevenVoiceName", ""));
        box.addView(voiceName);
        android.widget.Button pick = new android.widget.Button(this);
        pick.setText("ElevenLabs ki awaazein dikhayein");
        box.addView(pick);
        android.widget.CheckBox keep = new android.widget.CheckBox(this);
        keep.setText("Meri apni recordings na badlein");
        keep.setChecked(true);
        box.addView(keep);
        android.widget.CheckBox again = new android.widget.CheckBox(this);
        again.setText("Pehle se bane ElevenLabs lafz bhi dobara banayein (sirf awaaz badalni ho to)");
        again.setChecked(false);
        box.addView(again);
        pick.setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            if (k.isEmpty()) { voiceName.setText("Pehle key likhein."); return; }
            settings.edit().putString("elevenKey", k).apply();
            voiceName.setText("Awaazein laa raha hun...");
            new Thread(() -> {
                kotlin.Pair<java.util.List<ElevenLabs.Voice>, String> got = ElevenLabs.INSTANCE.voices(k);
                main.post(() -> {
                    java.util.List<ElevenLabs.Voice> list = got.getFirst();
                    if (list.isEmpty()) { voiceName.setText(got.getSecond().isEmpty() ? "Koi awaaz nahi mili. ElevenLabs par Voice ID copy kar ke upar likhein." : got.getSecond()); return; }
                    String[] names = new String[list.size()];
                    for (int i = 0; i < names.length; i++) names[i] = list.get(i).getName();
                    new AlertDialog.Builder(this).setTitle("Awaaz chunein").setItems(names, (d, w) -> {
                        voice.setText(list.get(w).getId());
                        settings.edit().putString("elevenVoiceName", names[w]).apply();
                        voiceName.setText("Awaaz: " + names[w]);
                    }).show();
                });
            }).start();
        });
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("ElevenLabs")
                .setView(scroll)
                .setPositiveButton("Banwana shuru karein", (d, w) -> {
                    String k = key.getText().toString().trim(), vid = voice.getText().toString().trim();
                    settings.edit().putString("elevenKey", k).putString("elevenVoice", vid).putString("elevenModel", model.getText().toString().trim()).apply();
                    if (k.isEmpty() || vid.isEmpty()) { showNote("Key aur awaaz dono chahiye."); return; }
                    makeWithElevenLabs(k, vid, keep.isChecked(), again.isChecked(), refresh);
                })
                .setNeutralButton("Sirf save karein", (d, w) -> {
                    // a new key or account: keep it for the training data, without making any word again
                    settings.edit().putString("elevenKey", key.getText().toString().trim()).putString("elevenVoice", voice.getText().toString().trim())
                            .putString("elevenModel", model.getText().toString().trim()).apply();
                    liveProblemShown = false;
                    showNote("Key, awaaz aur model save ho gaye. Koi lafz dobara nahi bana.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Training data for the offline voice: ElevenLabs says every training sentence once, in the voice
     * picked for the munshi, and the app packs them into one zip in Downloads for the training.
     */
    private void openVoiceData() {
        VoiceData vd = new VoiceData(this);
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView about = new TextView(this);
        int done = vd.doneCount(), all = vd.getSentences().size();
        int letters = 0;
        for (int i = 0; i < all; i++) if (!vd.done(i)) letters += vd.text(i).length();
        String voiceName = settings.getString("elevenVoiceName", "");
        about.setText("Offline awaaz sikhane ke liye " + all + " jumle chahiye.\nBan chuke: " + done + " / " + all
                + "\nBaqi jumlon ke takreeban " + letters + " huroof ElevenLabs se kharch honge."
                + "\nAwaaz: " + (voiceName.isEmpty() ? "(ElevenLabs wale button se pehle awaaz chunein)" : voiceName)
                + "\n\nRok dein to agli dafa wahin se shuru hoga. Sab ban jaye to \"Zip banayein\" dabayein.");
        box.addView(about);
        EditText model = field(box, "ElevenLabs model (jaise eleven_multilingual_v2 ya eleven_turbo_v2_5)", settings.getString("elevenModel", ElevenLabs.MODEL), false);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Offline awaaz ka data")
                .setView(scroll)
                .setPositiveButton(done < all ? "Banana shuru karein" : "Sab ban gaye", (d, w) -> {
                    settings.edit().putString("elevenModel", model.getText().toString().trim()).apply();
                    String key = settings.getString("elevenKey", ""), voice = settings.getString("elevenVoice", "");
                    if (key.isEmpty() || voice.isEmpty()) { showNote("Pehle \"ElevenLabs se awaaz banwayein\" mein key aur awaaz chunein."); return; }
                    if (vd.doneCount() < all) makeVoiceData(vd, key, voice, model.getText().toString().trim());
                })
                .setNeutralButton("Zip banayein", (d, w) -> {
                    if (vd.doneCount() == 0) { showNote("Abhi koi jumla nahi bana."); return; }
                    problem.setText("Zip ban rahi hai...");
                    new Thread(() -> {
                        String where;
                        try { where = vd.export(); } catch (Exception e) { where = null; }
                        final String w2 = where;
                        main.post(() -> { problem.setText(""); showNote(w2 == null ? "Zip nahi ban saki. Phone ki storage check karein."
                                : "Zip ban gayi: " + w2 + "\n" + vd.doneCount() + " jumle. Ye file training ke liye bhejni hai."); });
                    }).start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void makeVoiceData(VoiceData vd, String key, String voice, String model) {
        final boolean[] stop = {false};
        TextView progress = new TextView(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        progress.setPadding(pad, pad, pad, pad);
        progress.setText("Shuru ho raha hai...");
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("ElevenLabs se jumle ban rahe hain").setView(progress)
                .setNegativeButton("Rok dein", (d, w) -> stop[0] = true).setCancelable(false).show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            int made = 0, failed = 0;
            String why = "";
            for (int i = 0; i < vd.getSentences().size() && !stop[0]; i++) {
                if (vd.done(i)) continue;
                final int n = i;
                final String line = vd.getSentences().get(i);
                main.post(() -> progress.setText((n + 1) + " / " + vd.getSentences().size() + "\n" + line));
                ElevenLabs.Said said = ElevenLabs.INSTANCE.sentence(key, voice, model, vd.text(i));
                if (said.getPcm() == null) {
                    why = said.getProblem();
                    if (said.getFatal()) break;
                    if (++failed >= 5) break;                   // the same problem over and over: stop and say it
                    continue;
                }
                try { vd.save(i, said.getPcm()); made++; } catch (Exception e) { why = "Phone mein jagah nahi."; break; }
            }
            final int madeF = made;
            final String whyF = why;
            main.post(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                dlg.dismiss();
                String msg = madeF + " jumle bane. Kul: " + vd.doneCount() + " / " + vd.getSentences().size() + ".";
                if (!whyF.isEmpty()) msg += "\n\n" + whyF;
                if (vd.doneCount() == vd.getSentences().size()) msg += "\n\nSab ban gaye! Ab \"Zip banayein\" dabayein.";
                showNote(msg);
            });
        }).start();
    }

    /** Goes through the list in the background, showing how far it has got; stops on a problem that would repeat. */
    private void makeWithElevenLabs(String key, String voiceId, boolean keepMine, boolean remake, Runnable refresh) {
        final java.util.List<String> list = new java.util.ArrayList<>();
        try {
            JSONObject counts = new JSONObject(settings.getString("missingWords", "{}"));
            for (java.util.Iterator<String> it = counts.keys(); it.hasNext(); ) {
                String w = it.next();
                if (!MyVoice.INSTANCE.getPHRASES().contains(w)) list.add(w);
            }
        } catch (Exception ignored) { }
        list.addAll(MyVoice.INSTANCE.getPHRASES());
        JSONObject madeBy;
        try { madeBy = new JSONObject(settings.getString("elevenClips", "{}")); } catch (Exception e) { madeBy = new JSONObject(); }
        final JSONObject made = madeBy;
        final java.util.List<String> todo = new java.util.ArrayList<>();
        for (String w : list) {
            if (voiceId.equals(made.optString(w))) continue;                          // already made in this voice
            if (!remake && made.has(w) && clips.has(w)) continue;                       // made before (another key or account): kept
            if (keepMine && clips.has(w) && !made.has(w)) continue;                   // the user's own recording
            todo.add(w);
        }
        if (todo.isEmpty()) { showNote("Saare lafz pehle hi ban chuke hain."); return; }
        final boolean[] stop = {false};
        TextView progress = new TextView(this);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        progress.setPadding(pad, pad, pad, pad);
        progress.setText("Shuru ho raha hai... (" + todo.size() + " lafz)");
        AlertDialog dlg = new AlertDialog.Builder(this).setTitle("ElevenLabs se ban raha hai").setView(progress)
                .setNegativeButton("Rok dein", (d, w) -> stop[0] = true).setCancelable(false).show();
        new Thread(() -> {
            int ok = 0, failed = 0;
            String problemText = "";
            for (int i = 0; i < todo.size() && !stop[0]; i++) {
                final String w = todo.get(i);
                final int n = i + 1;
                main.post(() -> progress.setText(n + " / " + todo.size() + "\n" + w));
                ElevenLabs.Said said = ElevenLabs.INSTANCE.say(key, voiceId, w);
                if (said.getPcm() == null) {
                    if (said.getFatal()) { problemText = said.getProblem(); break; }
                    failed++; problemText = said.getProblem(); continue;
                }
                short[] pcm = MyVoice.INSTANCE.clean(said.getPcm(), VoiceClips.RATE);
                if (pcm.length == 0) { failed++; continue; }
                ok++;
                main.post(() -> {
                    clips.save(w, pcm);
                    try { made.put(w, voiceId); } catch (Exception ignored) { }
                    settings.edit().putString("elevenClips", made.toString()).apply();
                });
            }
            final int okF = ok, failedF = failed;
            final String why = problemText;
            main.post(() -> {
                dlg.dismiss();
                refresh.run();
                String msg = okF + " lafz ElevenLabs ki awaaz mein ban gaye." + (failedF > 0 ? " " + failedF + " nahi bane." : "");
                if (!why.isEmpty()) msg += "\n\n" + why;
                msg += "\n\nAb \"Ek jumla meri awaaz mein sunein\" daba kar sunein.";
                showNote(msg);
            });
        }).start();
    }

    private String voiceLine() {
        if (!ttsReady) return "Awaaz abhi tayyar nahi.";
        Voice v = null;
        try { v = tts.getVoice(); } catch (Exception ignored) { }
        String which = v == null ? "?" : v.getLocale().getDisplayName(Locale.ENGLISH) + " · " + v.getName();
        String head = script == 1 ? "Urdu awaaz chal rahi hai." : script == 2
                ? ("hi".equals(settings.getString("voiceLang", "")) ? "Hindi awaaz chal rahi hai (aap ne chuni)." : "Urdu awaaz nahi mili, Hindi awaaz chal rahi hai.")
                : "Urdu awaaz nahi mili, English awaaz chal rahi hai, is liye lehja Urdu nahi lagta.";
        return head + "\nAwaaz: " + which;
    }

    /** Opens the phone's own screen for getting voices: Google's speech engine, or its Urdu voice download. */
    private void installUrdu() {
        try {
            if (urduState == -2 && getPackageManager().getLaunchIntentForPackage(GOOGLE_TTS) == null) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + GOOGLE_TTS)));
                return;
            }
            Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
            i.setPackage(GOOGLE_TTS);
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); } catch (Exception ignored) { }
        }
    }

    /** Moves to the next installed voice of the language in use, keeps it, and says a line in it. */
    private void nextVoice() {
        if (!ttsReady) return;
        try {
            String lang = script == 1 ? "ur" : script == 2 ? "hi" : "en";
            java.util.List<Voice> list = new java.util.ArrayList<>();
            for (Voice v : tts.getVoices()) {
                if (!lang.equals(v.getLocale().getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                list.add(v);
            }
            if (list.isEmpty()) return;
            java.util.Collections.sort(list, (a, b) -> a.getName().compareTo(b.getName()));
            Voice cur = tts.getVoice();
            int at = 0;
            for (int i = 0; i < list.size(); i++) if (cur != null && list.get(i).getName().equals(cur.getName())) at = (i + 1) % list.size();
            Voice next = list.get(at);
            tts.setVoice(next);
            settings.edit().putString("voice", next.getName()).apply();
            say(new String[]{"Assalam o alaikum! Main Smart Book hoon.", "السلام علیکم! میں اسمارٹ بک ہوں۔", "अस्सलाम अलैकुम! मैं स्मार्ट बुक हूं।"}, null);
        } catch (Exception ignored) {
            // some engines cannot list voices
        }
    }

    private EditText field(LinearLayout box, String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        box.addView(e);
        return e;
    }

    private static String[] texts(JSONObject speak) throws Exception {
        return new String[]{speak.getString("ro"), speak.getString("ur"), speak.getString("hi")};
    }

    private String readSaved() {
        File f = new File(getFilesDir(), SAVE_FILE);
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Write to a side file first so a crash mid-write cannot damage the hisaab. */
    private boolean writeSaved(String json) {
        File tmp = new File(getFilesDir(), SAVE_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            return false;
        }
        if (!tmp.renameTo(new File(getFilesDir(), SAVE_FILE))) return false;
        savedJson = json;
        exportBackup();                    // keep the copy outside the app current
        return true;
    }

    // ------------------------------------------------------------ backup ---

    /**
     * Copies the hisaab to the phone's Downloads folder, where it outlives the
     * app being uninstalled. Android 10+ lets an app do this without asking
     * for any storage permission; older phones are skipped.
     */
    private boolean exportBackup() {
        if (Build.VERSION.SDK_INT < 29 || savedJson.isEmpty()) return false;
        try {
            ContentResolver cr = getContentResolver();
            String known = getPreferences(MODE_PRIVATE).getString("backupUriSB", null);
            if (known != null && writeTo(cr, Uri.parse(known))) return true;
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_NAME);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null || !writeTo(cr, uri)) return false;
            getPreferences(MODE_PRIVATE).edit().putString("backupUriSB", uri.toString()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean writeTo(ContentResolver cr, Uri uri) {
        try (OutputStream out = cr.openOutputStream(uri, "wt")) {
            if (out == null) return false;
            out.write(savedJson.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;                  // e.g. the user deleted the file: a new one is made
        }
    }

    /**
     * The full record of the conversation: one line per exchange, added to
     * the end of a text file and never trimmed. It is kept in the app's own
     * storage and, on Android 10+, mirrored to Downloads so it can be shared.
     */
    private void appendLog(String line) {
        if (line == null || line.isEmpty()) return;
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        File own = new File(getFilesDir(), LOG_FILE);
        try (FileOutputStream out = new FileOutputStream(own, true)) {
            out.write(bytes);
        } catch (Exception e) {
            return;
        }
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            ContentResolver cr = getContentResolver();
            String known = getPreferences(MODE_PRIVATE).getString("logUriSB", null);
            if (known != null) {
                try (OutputStream out = cr.openOutputStream(Uri.parse(known), "wa")) {
                    if (out != null) { out.write(bytes); return; }
                } catch (Exception gone) {
                    // the file was deleted or cannot be added to: start a fresh copy below
                }
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, LOG_NAME);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return;
            try (OutputStream out = cr.openOutputStream(uri, "wt"); FileInputStream in = new FileInputStream(own)) {
                if (out == null) return;
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);      // everything so far, not just this line
            }
            getPreferences(MODE_PRIVATE).edit().putString("logUriSB", uri.toString()).apply();
        } catch (Exception ignored) {
            // the record inside the app is still complete
        }
    }

    /** "backup wapas lao": let the user point at a backup file with the system picker. */
    private void pickBackup() {
        hangUp();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, PICK_BACKUP); } catch (Exception e) { fail(BAD_FILE); }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_BACKUP || result != RESULT_OK || data == null || data.getData() == null) return;
        String json;
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < 5_000_000) buf.write(chunk, 0, n);
            json = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            say(BAD_FILE, null);
            return;
        }
        // the brain checks the file really is a hisaab before anything is replaced
        brain.evaluateJavascript("restore(" + JSONObject.quote(json) + ")", raw -> {
            String restored = "";
            try { restored = (String) new JSONTokener(raw).nextValue(); } catch (Exception ignored) { }
            if (restored.isEmpty()) say(BAD_FILE, null);
            else say(writeSaved(restored) ? RESTORED : NOT_SAVED, null);
        });
    }

    // --------------------------------------------------------- lifecycle ---

    @Override
    protected void onPause() {
        super.onPause();
        if (inCall) hangUp();
        brain.onPause();                   // stop drawing while the app is not on screen
    }

    @Override
    protected void onResume() {
        super.onResume();
        brain.onResume();
    }

    @Override
    protected void onDestroy() {
        net.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (recogniser != null) recogniser.destroy();
        if (tts != null) tts.shutdown();
        if (brain != null) brain.destroy();
        super.onDestroy();
    }
}
