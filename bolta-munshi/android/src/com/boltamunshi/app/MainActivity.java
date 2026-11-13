package com.boltamunshi.app;

import android.Manifest;
import android.app.Activity;
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

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
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
        brain.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_UP) onTap();
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
                });
            }
        });
        brain.loadUrl("file:///android_asset/brain.html");
    }

    private void respond(JSONArray guesses) {
        show(SPEAKING);
        brain.evaluateJavascript("ask(" + guesses.toString() + ")", raw -> {
            if (!inCall) return;
            try {
                JSONObject reply = new JSONObject((String) new JSONTokener(raw).nextValue());
                String[] texts = texts(reply.getJSONObject("speak"));
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
        main.removeCallbacksAndMessages(null);
        if (recogniser != null) recogniser.destroy();
        if (tts != null) tts.shutdown();
        if (brain != null) brain.destroy();
        super.onDestroy();
    }
}
