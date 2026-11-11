# Voice to Text (Android)

Android libraries that turn speech into text, plus a demo app that uses them. Nothing here needs a server, an API key or a fee.

There are two ways to get text:

| | Live | Record |
|---|---|---|
| Module | `voicetext/` | `whisper/` |
| How | Android's own speech recognizer, while you speak | Record first; a Whisper model on the phone turns the recording into text |
| Speed | Instant | Roughly as long as the recording itself on a good phone, longer on a slow one |
| Internet | Usually needed | Only once, to download the model (60 or 190 MB) |
| App size | Tiny | Adds about 5 MB per CPU type |

- `voicetext/` – live recognition and the Roman-letter converter. Add it to any Android app.
- `whisper/` – recorder, model download and on-device recognition of a recording.
- `demo/` – a dictation pad with a Live and a Record mode: tap the microphone, speak, and the words appear. Copy, share, 18 languages (Urdu, English, Hindi, Arabic, ...), and a switch to write everything in Roman letters.

## Live: use it in your app

1. Copy the `voicetext` folder into your project and add it:

   ```kotlin
   // settings.gradle.kts
   include(":voicetext")

   // app/build.gradle.kts
   dependencies {
       implementation(project(":voicetext"))
   }
   ```

   The library's manifest already asks for the microphone permission.

2. Ask the user for the microphone, then listen:

   ```kotlin
   val voice = VoiceToText(this)
   voice.language = "ur-PK"                      // or "en-US", "hi-IN", ...
   voice.roman = true                            // write "aap kaise hain", not "آپ کیسے ہیں"
   voice.continuous = true                       // keep listening until stop()
   voice.onPartial = { text -> preview.text = text }       // while speaking
   voice.onFinal = { text -> box.append("$text ") }        // a finished sentence
   voice.onError = { error -> status.text = error.message }
   voice.onListeningChanged = { on -> micButton.isActivated = on }

   if (VoiceToText.hasMicPermission(this)) voice.start()
   else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)

   // later
   voice.stop()      // finish the sentence being spoken
   voice.release()   // in onDestroy
   ```

| | |
|---|---|
| `language` | Tag such as `ur-PK`. `VoiceLanguages.COMMON` is a ready list for a picker. |
| `roman` | Write the text in Roman (English) letters whatever the language. See below. |
| `continuous` | `false`: one sentence, then stop. `true`: sentence after sentence until `stop()`. |
| `preferOffline` | Use on-device recognition when the language is downloaded on the phone. |
| `start()` / `stop()` / `cancel()` | `stop` still delivers the sentence being spoken; `cancel` throws it away. |
| `onLevel` | Loudness from 0 to 1, for a microphone animation. |
| `VoiceError` | Why listening ended: no permission, nothing heard, no internet, language not supported, ... Each has a `message` to show. |

## Roman letters

With `roman = true` the text comes back in Roman letters, the way people type on a phone: "mera naam ali hai".

- **Urdu and Hindi** are done by rule in `Romanizer` and read naturally. Urdu script leaves out most vowels, so Urdu is listened to as Hindi (they sound the same) and the Hindi letters are converted. A few very Urdu words may come out with a Hindi flavour.
- **Other languages** (Arabic, Bengali, Russian, Chinese, ...) go through Android's own transliterator, on Android 10 and newer. That is a letter-by-letter conversion and is rougher.

`Romanizer.toRoman(text)` can also be used on its own for any Hindi text.

## Record: turn a recording into text

Add the `whisper` module the same way (`include(":whisper")`, `implementation(project(":whisper"))`). Everything slow must run off the main thread.

```kotlin
val store = ModelStore(context)
val model = WhisperModel.ACCURATE                 // or FAST
if (!store.isDownloaded(model)) store.download(model) { done -> /* 0..1 */ }

val recorder = AudioRecorder()
recorder.start()                                   // needs the microphone permission
// ... the user speaks ...
val samples = recorder.stop()                      // 16 kHz mono, -1..1

val engine = WhisperEngine(store.file(model))      // loads the model; keep it around
val text = engine.transcribe(samples, "ur")        // "ur", "hi", "en", ...
engine.close()
```

| Model | Download | Notes |
|---|---|---|
| `FAST` | 60 MB | Quick. Fine for clear, simple speech. |
| `ACCURATE` | 190 MB | About 3 times slower, clearly better, and the one to use for Roman Urdu. |

For Roman Urdu, transcribe with language `"hi"` on the `ACCURATE` model and pass the result to `VoiceToText.romanize(text)`; the demo does this when its Roman switch is on.

The recognition is done by [whisper.cpp](https://github.com/ggerganov/whisper.cpp) (MIT licence), which the build downloads at a fixed version, with models from its Hugging Face page. Building needs the Android NDK and CMake (Android Studio offers to install them).

**How good is it?** Tried here on Urdu test recordings: everyday sentences come out well ("aap ... dekh sakte hain aur ... pehle ke sukoon mein bhi dekh sakte hain"); fast, formal news-style sentences with names and numbers come out with many mistakes, and the model sometimes repeats a word. Live mode is usually more accurate for Urdu when there is internet.

## What it needs

- Android 6.0 (API 23) or newer.
- A speech recognition service on the phone. On most phones this is the Google app. Without it `VoiceToText.isAvailable()` is false.
- Usually an internet connection. Which languages work, and which work offline, depends on the phone.

## Build

Open the folder in Android Studio, or:

```
./gradlew :demo:assembleDebug        # APK in demo/build/outputs/apk/debug/
./gradlew :voicetext:testDebugUnitTest :whisper:testDebugUnitTest
```

## How it is tested

The rules for what to do with each recognizer event (restart in continuous mode, retry when busy, stay quiet about errors after stop) live in `SessionController`, which has no Android code and is covered by unit tests, and so is `Romanizer`. The Whisper models were run on a computer against real Urdu recordings, but recording and recognition inside the app can only be tried on a real phone.
