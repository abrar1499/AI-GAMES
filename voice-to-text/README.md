# Voice to Text (Android)

A small Android library that turns speech into text, plus a demo app that uses it. It uses the speech recognition built into Android, so there is no server, no API key and no fee.

- `voicetext/` – the library. Add it to any Android app.
- `demo/` – a dictation pad: tap the microphone, speak, and the words appear. Copy, share, 18 languages (Urdu, English, Hindi, Arabic, ...), and a switch to write everything in Roman letters.

## Use it in your app

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

## What it needs

- Android 6.0 (API 23) or newer.
- A speech recognition service on the phone. On most phones this is the Google app. Without it `VoiceToText.isAvailable()` is false.
- Usually an internet connection. Which languages work, and which work offline, depends on the phone.

## Build

Open the folder in Android Studio, or:

```
./gradlew :demo:assembleDebug        # APK in demo/build/outputs/apk/debug/
./gradlew :voicetext:testDebugUnitTest
```

## How it is tested

The rules for what to do with each recognizer event (restart in continuous mode, retry when busy, stay quiet about errors after stop) live in `SessionController`, which has no Android code and is covered by unit tests, and so is `Romanizer`. The recognizer itself can only be tried on a real phone.
