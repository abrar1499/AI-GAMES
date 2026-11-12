# Bolta Munshi

A voice assistant that keeps your hisaab. Talk to it like a phone call: tell it what you spent, earned or lent, or anything you want remembered, and ask about it later. No API key and no server; it is a plain web page with nothing on it. The screen is black and everything happens by voice.

## Running it

Speech recognition only works on `https://` or `localhost`, so serve the folder instead of opening the file directly:

```
cd bolta-munshi
python3 -m http.server 8000
```

Then open `http://localhost:8000` in Chrome or Edge and tap anywhere on the screen. It greets you and starts listening; tap again or say "khuda hafiz" to end the call. Tapping while it is talking cuts it off and makes it listen. The small dot in the middle is green while it listens and amber while it speaks.

The one tap is required: browsers do not let a page turn on the microphone by itself. To use it on a phone, host the folder on any HTTPS static host (GitHub Pages works).

## What to say

| You say | It does |
|---|---|
| "aaj 500 ka petrol dala", "chai 50", "dhai hazar ka rashan liya" | books an expense |
| "300 ka petrol aur 150 ki chai" | books both |
| "50000 tankhwah mili" | books income |
| "Ali ko 1000 udhar diye", "Ali ne 400 wapas kiye" | tracks a loan per person |
| "yaad rakho ke meri gaari ka number LEA 1234 hai" | remembers a fact |
| "aaj kitna kharcha hua", "is mahine petrol par kitna laga", "sab se zyada kharcha kis par hua" | answers from the saved hisaab |
| "is mahine kitni bachat hui", "udhar ka hisaab batao", "gaari ka number kya hai" | same |
| "aakhri wala mita do" | removes the last thing added |
| "khuda hafiz" | ends the call |

It also keeps up a conversation and learns from you:

| You say | It does |
|---|---|
| "petrol dalwaya" | asks "Kitne rupay?" and books it when you answer "500" |
| "nahi, 600 the" | corrects the amount it just booked |
| "aaj kitna kharcha hua", then "aur kal?", "aur petrol par?" | answers the follow-up without the whole question again |
| a sentence with money it cannot place | asks "kharcha tha ya aamdani?" and remembers those words for next time |
| "nahi, yeh aamdani thi" | moves the entry and remembers that too |
| "chips ko khanay mein gino", "tel ka matlab petrol hai" | learns the word, and re-files old entries |
| a sentence it does not understand | asks whether to remember it as a note |
| "mera naam Abrar hai" | greets you by name from then on |
| "backup bana do", "backup wapas lao" | saves or restores a backup file |

Words that are slightly misheard or misspelt ("pertol") are still matched, but only for harmless words; nothing fuzzy can delete an entry, end the call or change an amount.

Roman Urdu, Urdu script and Devanagari are all understood.

## How it works

- `brain.js` is the understanding: a word list in three scripts, a number-word parser ("saadhe teen hazar" is 3500) and rules that turn a sentence into an entry or an answer. It has no browser dependencies.
- `app.js` is the call: the browser's speech recognition listens, the brain replies, the browser's speech synthesis speaks, then it listens again.
- Data is kept in the browser's `localStorage`, together with everything it has learned. Clearing the browser's site data erases it, so say "backup bana do" now and then; it downloads a file that "backup wapas lao" can load again.

## Limits

- It follows rules, not a language model. It handles the sentence shapes above and their variations, and learns new words when you teach or correct it; it cannot hold an open conversation or learn new kinds of sentence by itself.
- Chrome and Edge send microphone audio to their own speech service to turn it into text, so listening needs internet. Firefox has no speech recognition, so it does not work there.
- Voice quality is whatever the device provides. If there is no Urdu voice it uses a Hindi voice, and failing that an English voice reading Roman Urdu.

## Tests

```
node --test bolta-munshi/tests/brain.test.js
```

## Android app

`android/` is a small native app around the same `brain.js`. It is needed because a web page inside an Android app cannot use the microphone for speech recognition; the app uses the phone's own speech recogniser and text-to-speech instead, and runs `brain.js` in an off-screen WebView. The screen and the way you use it are the same: black, tap to start, tap to end.

- The only permission is the microphone. The app has no internet permission; the phone's speech service does its own networking.
- The hisaab is saved in the app's private storage (`hisaab.json`). On Android 10 and newer a copy, `bolta-munshi-backup.json`, is kept up to date in the phone's Downloads folder, which survives uninstalling the app; "backup wapas lao" opens the file picker to load it back. That copy is plain text, readable by anyone who can open the phone's files.
- It picks the highest-quality voice the phone has for the language.

Build it with a JDK and the Android SDK (`platforms;android-34`, `build-tools;35.0.0`):

```
ANDROID_HOME=/path/to/android-sdk bolta-munshi/android/build.sh
```

The APK lands in `android/build/bolta-munshi.apk`. It is signed with `android/debug.keystore`, a throwaway key kept in the repo so that every build can update the installed app without wiping its data.
