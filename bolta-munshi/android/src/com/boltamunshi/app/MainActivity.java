package com.boltamunshi.app;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Bolta Munshi: a black screen you talk to. Tap once to start the call, tap
 * again (or say "khuda hafiz") to end it. The phone's own speech recogniser
 * listens and its text-to-speech answers; the understanding is brain.js,
 * running in an off-screen WebView.
 */
public class MainActivity extends Activity implements RecognitionListener, TextToSpeech.OnInitListener {

    private static final int IDLE = 0, LISTENING = 1, SPEAKING = 2;
    private static final int ASK_MIC = 1;
    private static final String SAVE_FILE = "hisaab.json";

    // every line the app itself says: {Roman Urdu, Urdu, Hindi}
    private static final String[] HELLO = {"Assalam o alaikum! Bataiye.", "السلام علیکم! بتائیے۔", "अस्सलाम अलैकुम! बताइए।"};
    private static final String[] NOT_SAVED = {"Yeh save nahi ho saka. Phone ki storage bhari hui hai.", "یہ محفوظ نہیں ہو سکا۔ فون کی اسٹوریج بھری ہوئی ہے۔", "यह सेव नहीं हो सका। फ़ोन की स्टोरेज भरी हुई है।"};
    private static final String[] NO_MIC_PERMISSION = {"Mic ki ijazat nahi mili. Phone ki settings mein is app ko microphone ki ijazat dein.", "مائیک کی اجازت نہیں ملی۔ فون کی سیٹنگز میں اس ایپ کو مائیکروفون کی اجازت دیں۔", "माइक की इजाज़त नहीं मिली। फ़ोन की सेटिंग्स में इस ऐप को माइक्रोफ़ोन की इजाज़त दें।"};
    private static final String[] NO_NETWORK = {"Awaaz samajhne wali service se rabta nahi ho raha. Internet check karein.", "آواز سمجھنے والی سروس سے رابطہ نہیں ہو رہا۔ انٹرنیٹ چیک کریں۔", "आवाज़ समझने वाली सर्विस से संपर्क नहीं हो रहा। इंटरनेट चेक करें।"};
    private static final String[] MIC_BUSY = {"Mic se awaaz nahi aa rahi. Shayad koi aur app mic use kar rahi hai.", "مائیک سے آواز نہیں آ رہی۔ شاید کوئی اور ایپ مائیک استعمال کر رہی ہے۔", "माइक से आवाज़ नहीं आ रही। शायद कोई और ऐप माइक इस्तेमाल कर रही है।"};
    private static final String[] NO_LANGUAGE = {"Is phone mein Urdu sunne ki suhulat nahi hai. Google app mein Urdu zabaan download karein.", "اس فون میں اردو سننے کی سہولت نہیں ہے۔ گوگل ایپ میں اردو زبان ڈاؤن لوڈ کریں۔", "इस फ़ोन में उर्दू सुनने की सुविधा नहीं है। गूगल ऐप में उर्दू भाषा डाउनलोड करें।"};
    private static final String[] NO_RECOGNISER = {"Is phone mein awaaz samajhne wali service nahi hai. Google app install karein.", "اس فون میں آواز سمجھنے والی سروس نہیں ہے۔ گوگل ایپ انسٹال کریں۔", "इस फ़ोन में आवाज़ समझने वाली सर्विस नहीं है। गूगल ऐप इंस्टॉल करें।"};
    private static final String[] BRAIN_ERROR = {"Maaf kijiye, andar koi masla aa gaya. App band kar ke dobara kholein.", "معاف کیجیے، اندر کوئی مسئلہ آ گیا۔ ایپ بند کر کے دوبارہ کھولیں۔", "माफ़ कीजिए, अंदर कोई समस्या आ गई। ऐप बंद करके दोबारा खोलें।"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private View dot;
    private TextView problem;
    private ValueAnimator pulse;

    private WebView brain;
    private boolean brainReady;

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
        root.setContentDescription("Call shuru ya band karein");
        root.setOnClickListener(v -> onTap());

        dot = new View(this);
        int size = (int) (14 * dp);
        root.addView(dot, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));

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

        startBrain();
        tts = new TextToSpeech(this, this);
    }

    /** Dark dot: idle. Green, slow pulse: listening. Amber, fast pulse: speaking. */
    private void show(int newState) {
        state = newState;
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(newState == LISTENING ? 0xFF35C28B : newState == SPEAKING ? 0xFFF0B24A : 0xFF2A2F36);
        dot.setBackground(circle);

        if (pulse != null) pulse.cancel();
        dot.setScaleX(1f); dot.setScaleY(1f); dot.setAlpha(1f);
        if (newState != IDLE) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(dot,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.9f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.9f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f));
            pulse.setDuration(newState == LISTENING ? 700 : 300);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.start();
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
        say(HELLO, this::listen);
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
            tts.setLanguage(new Locale("ur", "PK")); script = 1; listenLanguage = "ur-PK";
        } else if (tts.isLanguageAvailable(new Locale("hi", "IN")) >= TextToSpeech.LANG_AVAILABLE) {
            tts.setLanguage(new Locale("hi", "IN")); script = 2; listenLanguage = "hi-IN";
        } else {
            tts.setLanguage(new Locale("en", "IN")); script = 0; listenLanguage = "ur-PK";
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { main.post(() -> spoken(id)); }
            @Override public void onError(String id) { main.post(() -> spoken(id)); }
        });
        ttsReady = true;
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
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recogniser.startListening(i);
    }

    @Override
    public void onResults(Bundle results) {
        if (!inCall) return;
        softErrors = 0;
        ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = heard == null || heard.isEmpty() ? "" : heard.get(0).trim();
        if (text.isEmpty()) listen(); else respond(text);
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
    @Override public void onRmsChanged(float rms) { }
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
                brain.evaluateJavascript("init(" + JSONObject.quote(readSaved()) + ")", r -> brainReady = r != null && r.contains("ready"));
            }
        });
        brain.loadUrl("file:///android_asset/brain.html");
    }

    private void respond(String text) {
        show(SPEAKING);
        brain.evaluateJavascript("ask(" + JSONObject.quote(text) + ")", raw -> {
            if (!inCall) return;
            try {
                JSONObject reply = new JSONObject((String) new JSONTokener(raw).nextValue());
                JSONObject speak = reply.getJSONObject("speak");
                String[] texts = {speak.getString("ro"), speak.getString("ur"), speak.getString("hi")};
                String db = reply.getString("db");
                if (!db.isEmpty() && !writeSaved(db)) texts = NOT_SAVED;
                final boolean bye = "hangup".equals(reply.getString("action"));
                say(texts, () -> { if (bye) hangUp(); else listen(); });
            } catch (Exception e) {
                fail(BRAIN_ERROR);
            }
        });
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
        return tmp.renameTo(new File(getFilesDir(), SAVE_FILE));
    }

    // --------------------------------------------------------- lifecycle ---

    @Override
    protected void onPause() {
        super.onPause();
        if (inCall) hangUp();
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
