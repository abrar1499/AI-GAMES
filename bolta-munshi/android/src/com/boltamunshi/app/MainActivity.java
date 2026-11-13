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
 * Bolta Munshi: a brain on a black screen that you talk to. Tap once to start
 * the call, tap again (or say "khuda hafiz") to end it. The phone's own speech
 * recogniser listens and its text-to-speech answers. One full-screen WebView
 * does the rest: brain.js understands, visual.js draws the brain.
 */
public class MainActivity extends Activity implements RecognitionListener, TextToSpeech.OnInitListener {

    private static final int IDLE = 0, LISTENING = 1, SPEAKING = 2;
    private static final int ASK_MIC = 1, PICK_BACKUP = 2;
    private static final String BACKUP_NAME = "bolta-munshi-backup.json";
    private static final String LOG_FILE = "baatein.txt", LOG_NAME = "bolta-munshi-baatein.txt";
    private static final String SAVE_FILE = "hisaab.json";

    // every line the app itself says: {Roman Urdu, Urdu, Hindi}
    private static final String[] HELLO = {"Assalam o alaikum! Bataiye.", "السلام علیکم! بتائیے۔", "अस्सलाम अलैकुम! बताइए।"};
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

    // The teacher and the shared lessons. The keys are typed in by the user, never kept in the code.
    // Any service that speaks the OpenAI chat format will do; the key's prefix says which one it is.
    private static class Service {
        final String name, url; final String[] models;
        Service(String name, String url, String... models) { this.name = name; this.url = url; this.models = models; }
    }
    private static final Service GROQ = new Service("Groq", "https://api.groq.com/openai/v1/chat/completions", "llama-3.3-70b-versatile", "openai/gpt-oss-120b");
    private static final Service GEMINI = new Service("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.5-flash", "gemini-2.0-flash");
    private static final Service OPENROUTER = new Service("OpenRouter", "https://openrouter.ai/api/v1/chat/completions");   // its free models change often: the list is fetched (see freeModels)
    private static Service serviceFor(String key) {
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
    private long levelSentAt;

    private WebView brain;
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
        brain.setContentDescription("Call shuru ya band karein");
        // the whole screen is the only control; a WebView swallows clicks, so watch touches instead
        // a tap starts or ends the call; holding a finger down opens the settings
        brain.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longPressed = false;
                    longPress = () -> { longPressed = true; openSettings(); };
                    main.postDelayed(longPress, 900);
                    break;
                case MotionEvent.ACTION_UP:
                    main.removeCallbacks(longPress);
                    if (!longPressed) onTap();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
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

        setContentView(root);
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        show(IDLE);
        tts = new TextToSpeech(this, this);
    }

    /** Tell the brain on screen what the call is doing: grey and quiet, green and listening, amber and speaking. */
    private void show(int newState) {
        state = newState;
        if (!brainReady) return;           // onPageFinished repeats this once the page exists
        String name = newState == LISTENING ? "listening" : newState == SPEAKING ? "speaking" : "idle";
        brain.evaluateJavascript("show('" + name + "')", null);
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
        if (code != ASK_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCall();
        else fail(NO_MIC_PERMISSION);
    }

    // -------------------------------------------------------------- call ---

    private void startCall() {
        problem.setText("");
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { fail(NO_RECOGNISER); return; }
        if (!brainReady) { fail(BRAIN_ERROR); return; }
        if (recogniser == null) {
            recogniser = SpeechRecognizer.createSpeechRecognizer(this);
            recogniser.setRecognitionListener(this);
        }
        inCall = true;
        softErrors = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // the brain greets by name once it has been told one
        brain.evaluateJavascript("greet()", raw -> {
            if (!inCall) return;
            String[] hello = HELLO;
            try { hello = texts(new JSONObject((String) new JSONTokener(raw).nextValue())); } catch (Exception ignored) { }
            say(hello, this::listen);
        });
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

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) return;
        // Urdu if the phone can speak it, else Hindi (the same spoken language),
        // else an English voice reading Roman Urdu.
        if (tts.isLanguageAvailable(new Locale("ur", "PK")) >= TextToSpeech.LANG_AVAILABLE) {
            tts.setLanguage(new Locale("ur", "PK")); script = 1; listenLanguage = "ur-PK"; useBestVoice("ur");
        } else if (tts.isLanguageAvailable(new Locale("hi", "IN")) >= TextToSpeech.LANG_AVAILABLE) {
            tts.setLanguage(new Locale("hi", "IN")); script = 2; listenLanguage = "hi-IN"; useBestVoice("hi");
        } else {
            tts.setLanguage(new Locale("en", "IN")); script = 0; listenLanguage = "ur-PK"; useBestVoice("en");
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { main.post(() -> spoken(id)); }
            @Override public void onError(String id) { main.post(() -> spoken(id)); }
        });
        ttsReady = true;
    }

    /** Phones ship several voices per language; the highest-quality one sounds the least robotic. */
    private void useBestVoice(String language) {
        try {
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

    private void say(String[] texts, Runnable then) {
        show(SPEAKING);
        afterSpeech = then;
        final String id = String.valueOf(++utterance);
        if (!ttsReady) { main.postDelayed(() -> spoken(id), 300); return; }   // no voice: carry on silently
        tts.speak(texts[script], TextToSpeech.QUEUE_FLUSH, null, id);
    }

    private void spoken(String id) {
        if (!id.equals(String.valueOf(utterance))) return;                    // an older, interrupted line
        Runnable next = afterSpeech;
        afterSpeech = null;
        if (next != null) next.run(); else if (!inCall) show(IDLE);
    }

    private void stopSpeaking() {
        utterance++;                       // whatever was playing no longer counts
        afterSpeech = null;
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
        if (guesses.length() == 0) listen(); else respond(guesses);
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
                    pullShared();
                });
            }
        });
        brain.loadUrl("file:///android_asset/brain.html");
    }

    /**
     * Answer what was heard. With a Groq key, the sentence and the app's own
     * draft reply go to the teacher first, unless the app already trusts this
     * kind of sentence; the brain then answers with the teacher's verdict.
     */
    private void respond(JSONArray guesses) {
        show(SPEAKING);
        String key = settings.getString("groqKey", "").trim();
        if (key.isEmpty()) { finish(guesses, "ask(" + guesses + ")"); return; }
        brain.evaluateJavascript("plan(" + guesses + ")", raw -> {
            if (!inCall) return;
            JSONObject plan;
            try { plan = new JSONObject((String) new JSONTokener(raw).nextValue()); } catch (Exception e) { plan = null; }
            if (plan == null || !plan.optBoolean("ask")) { finish(guesses, "ask(" + guesses + ")"); return; }
            final String system = plan.optString("system"), user = plan.optString("user");
            net.execute(() -> {
                String verdict = askTeacher(key, system, user);
                main.post(() -> {
                    if (!inCall) return;
                    finish(guesses, "taught(" + guesses + "," + JSONObject.quote(verdict == null ? "" : verdict) + ")");
                });
            });
        });
    }

    private void finish(JSONArray guesses, String call) {
        brain.evaluateJavascript(call, raw -> {
            if (!inCall) return;
            try {
                JSONObject reply = new JSONObject((String) new JSONTokener(raw).nextValue());
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
        });
    }

    // ------------------------------------------------------------ teacher ---

    /** Ask Groq for its verdict. Returns the model's text, or null if it could not be reached. */
    private String askTeacher(String key, String system, String user) {
        Service svc = serviceFor(key);
        java.util.List<String> models = new java.util.ArrayList<>();
        String chosen = settings.getString("groqModel", "").trim(), worked = settings.getString("worked_" + svc.name, "");
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
                        if (!badKeySaid) { badKeySaid = true; final String why = lastProblem; main.post(() -> problem.setText(why + "\nTab tak main khud jawab dungi.")); }
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
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
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

    /** Opened by holding a finger on the screen: the Groq key and model, and the GitHub token and branch. */
    private void openSettings() {
        if (inCall) hangUp();
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText groqKey = field(box, "AI key: Groq (gsk_...), Gemini (AIza...) ya OpenRouter (sk-or-...)", settings.getString("groqKey", ""), true);
        EditText groqModel = field(box, "Model (khali chhor dein, app khud chun le gi)", settings.getString("groqModel", ""), false);
        EditText ghToken = field(box, "GitHub token (sirf aap ke phone ke liye, khali bhi chal jaye ga)", settings.getString("githubToken", ""), true);
        EditText ghBranch = field(box, "GitHub branch (khali chhor dein to " + DEFAULT_BRANCH + ")", settings.getString("branch", ""), false);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(scroll)
                .setPositiveButton("Save", (d, w) -> {
                    settings.edit()
                            .putString("groqKey", groqKey.getText().toString().trim())
                            .putString("groqModel", groqModel.getText().toString().trim())
                            .putString("githubToken", ghToken.getText().toString().trim())
                            .putString("branch", ghBranch.getText().toString().trim())
                            .apply();
                    badKeySaid = false;
                    problem.setText("");
                    pullShared();
                })
                .setNeutralButton("Key check karein", (d, w) -> {
                    settings.edit()
                            .putString("groqKey", groqKey.getText().toString().trim())
                            .putString("groqModel", groqModel.getText().toString().trim())
                            .apply();
                    checkKey();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** One small request to Groq, with the result shown in plain words. */
    private void checkKey() {
        final String key = settings.getString("groqKey", "").trim();
        if (key.isEmpty()) { showResult("Pehle key paste karein."); return; }
        final String name = serviceFor(key).name;
        problem.setText(name + " se rabta kar rahi hoon...");
        net.execute(() -> {
            lastProblem = "";
            String got = askTeacher(key, "Reply with JSON only: {\"verdict\": \"ok\"}", "Heard: salam\nDraft reply: Wa alaikum assalam");
            final String result = got != null
                    ? name + " theek chal raha hai. Key qabool ho gayi.\nModel: " + settings.getString("worked_" + name, "?")
                    : (lastProblem.isEmpty() ? name + " ne koi jawab nahi diya." : lastProblem);
            main.post(() -> { problem.setText(""); showResult(result + "\n\nKey ke shuru ke huroof: " + key.substring(0, Math.min(4, key.length())) + "... (" + key.length() + " huroof)"); });
        });
    }

    private void showResult(String text) {
        new AlertDialog.Builder(this).setTitle("AI ustad").setMessage(text).setPositiveButton("Theek hai", null).show();
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
            String known = getPreferences(MODE_PRIVATE).getString("backupUri", null);
            if (known != null && writeTo(cr, Uri.parse(known))) return true;
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_NAME);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null || !writeTo(cr, uri)) return false;
            getPreferences(MODE_PRIVATE).edit().putString("backupUri", uri.toString()).apply();
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
            String known = getPreferences(MODE_PRIVATE).getString("logUri", null);
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
            getPreferences(MODE_PRIVATE).edit().putString("logUri", uri.toString()).apply();
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
