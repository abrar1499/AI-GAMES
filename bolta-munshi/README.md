# Bolta Munshi

A voice assistant that keeps your hisaab. She speaks in the feminine, since that is how she is addressed. Talk to it like a phone call: tell it what you spent, earned or lent, or anything you want remembered, and ask about it later. No API key and no server; it is a plain web page with no text or buttons. The screen shows a brain drawn as a network of points and lines, and everything happens by voice.

## Running it

Speech recognition only works on `https://` or `localhost`, so serve the folder instead of opening the file directly:

```
cd bolta-munshi
python3 -m http.server 8000
```

Then open `http://localhost:8000` in Chrome or Edge and tap anywhere on the screen. It greets you and starts listening; tap again or say "khuda hafiz" to end the call. Tapping while it is talking cuts it off and makes it listen. The brain is grey and quiet when idle, green while it listens and amber while it speaks.

The one tap is required: browsers do not let a page turn on the microphone by itself. To use it on a phone, host the folder on any HTTPS static host (GitHub Pages works).

## What to say

| You say | It does |
|---|---|
| "aaj 500 ka petrol dala", "chai 50", "dhai hazar ka rashan liya" | books an expense |
| "300 ka petrol aur 150 ki chai" | books both |
| "50000 tankhwah mili" | books income |
| "Ali ko 1000 udhar diye", "Ali ne 400 wapas kiye" | tracks a loan per person |
| "Ahmed ke naam 500 likh do", "Ahmed ke naam kitna likha hai" | the same account, in khata wording |
| "5 tareekh ko 500 ka petrol dala", "10 November ko ...", "3 din pehle ...", "peer ko ..." | books it on that earlier date; "5 tareekh ko kitna kharcha hua" asks about it |
| "Ahmed ke naam se 200 kam kar do", "Ahmed ka hisaab saaf kar do" | takes money off a name, or wipes that name |
| "Ashraf ne 25 hazar kachi eent dali hai, stock mein likh do" | records goods in stock, counted in pieces, not rupees |
| "stock mein kitni eent hai", "Ashraf ne kitni eent dali" | reads the stock back |
| "yaad rakho ke meri gaari ka number LEA 1234 hai" | remembers a fact, and answers with a plain "theek hai" |
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
| things you mention in passing ("kal mujhe Lahore jana hai") | replies like conversation and quietly remembers them, so "Lahore kab jana hai" works later; it never announces that it has remembered |
| "mera naam Abrar hai" | greets you by name from then on |
| "backup bana do", "backup wapas lao" | saves or restores a backup file |

And it talks about more than hisaab:

| You say | It does |
|---|---|
| "25 guna 4", "500 ka 10 percent", "1000 mein se 250 nikalo", "15 + 30" | does the sum |
| "aaj kaun sa din hai", "aaj kya tareekh hai", "time kya hua hai" | day, date and time |
| "subah bakhair", "kya haal hai", "tumhara naam kya hai", "main thak gaya hoon", "eid mubarak" | small talk and moods |
| "lateefa sunao", "paheli poocho", "koi naseehat karo" | a joke, a riddle it waits for you to answer, a saying |
| "Pakistan ka darul hukumat kya hai", "ek kilo mein kitne gram hote hain" | a small set of built-in facts |
| a question it has no answer to | says so; say "jawab hai ..." and it remembers the answer |
| "jab main kahun chai to kehna abhi laya sahab" | says exactly that whenever you say "chai" |

The last two are how it grows: there is no list of everything in the world inside it, but anything you tell it once it will answer from then on.

Words that are slightly misspelt ("petroll") are still matched, but only for harmless words; nothing fuzzy can delete an entry, end the call or change an amount.

### The conversation record

The saved data includes a `log` of the last 400 exchanges: what the speech service heard (every guess it offered), which guess was used, what was said back, and whether that was a real answer, a filler, or "not understood". Saying "yeh jawab ghalat tha" marks the last exchange as `bad`. The log is part of the backup file, so sending that file shows exactly which sentences were misheard or answered badly.

It also learns from that record, by rule rather than by any kind of training:

- A question that went unanswered before is not shrugged off a second time. It says so, asks for the answer, and keeps it. "Pata nahi" stops it asking about that question.
- A remark made for the third time with nothing to say back makes it ask what it should say, and it says that from then on.
- A sentence that got no real answer, followed within half a minute by nearly the same sentence that did, shows which word was misheard. Seen twice, that word is learned (with the same safety limits as the accent learning below).

The Android app also keeps the whole conversation with no limit: one line per exchange is added to `bolta-munshi-baatein.txt` in Downloads (Android 10+), and nothing is ever removed from it. Like the backup, it is plain text.

### The phrase bank

`data/bank.json` holds ways people say things: hisaab questions in many wordings, each mapped to a command the rules understand, and gup-shup (feelings, everyday remarks, stories, sayings, general facts, units such as mann, tola and marla) with replies. It is generated by `data/make_bank.py` from short patterns, so a few lines give many phrasings; to add knowledge, add an intent there and run the script.

It also holds shop work with a name and an amount in it: a customer taking goods on credit or paying it off, buying stock on credit from a supplier and paying the bill, the day's takings, cash purchases and wages. In these patterns `@` stands for a name and a number for any amount.

A sentence is compared with every phrasing word by word, by meaning where the lexicon knows the word (so "paisa", "pese" and "پیسے" count as one), rare words counting most. A word counts as a name only where a name stands, right before ne, se, ko, ka or par. The closest phrasing decides the answer if it is close enough. A shop sentence that matches closely is answered from the bank even before the rules, because the rules read some of them the wrong way round ("Ahmed ne saman udhar liya" means Ahmed owes the shop); everything else uses the bank only when the rules did not understand. Either way the bank only produces a plain command, which the rules then carry out and confirm aloud, and it needs exactly one name and one amount to fill one in.

### Learning from a teacher (Groq)

The Android app can use an online model on Groq as a teacher while it learns. Hold a finger on the screen to open the settings and paste a Groq API key there; without a key the app works on its rules alone, as before.

For each sentence the app works out its own reply first, then sends the sentence, its draft reply and the names of people with accounts to Groq. The teacher answers `ok`, or rewrites the request as one of the plain commands the rules understand, or gives a reply to say instead. The app uses the teacher's answer and stores the lesson against the sentence's shape: its words, with numbers and names left open, so "Ashraf ki taraf se 500 eent aayi hai" also teaches "Bilal ki taraf se 900 eent aayi hai". A shape the teacher has agreed with three times is trusted and no longer sent, so the teacher is needed less and less, and everything learned keeps working after the key is removed. "Ustad ki kitni zaroorat pari" says how often it was still asked in the last 100 sentences.

The teacher can never make the app delete anything, wipe a name or end the call, and a lesson the rules cannot carry out is ignored. "Yeh jawab ghalat tha" withdraws whatever lesson produced the last answer, and the teacher is asked again next time.

Groq turns away requests from some countries ("Access denied. Please check your network settings."). The key box also takes a Google Gemini key (`AIza...`) or an OpenRouter key (`sk-or-...`); the app picks the service from the key's prefix, since all three use the same request format. On OpenRouter it asks OpenRouter for its current list of free models (they change often), tries the strongest-looking ones in turn, and remembers the one that answered. Free models need no credit but allow only a small number of requests a day. "Key check karein" in the settings sends one small request and shows the service's own answer.

While a key is set, what you say is sent to that service.

Lessons that hold nothing private are shared through `learned/shared.json` in this repository. Every app (and the web page) downloads that file when it starts. A phone with a GitHub token in its settings adds its own shareable lessons to it after each call; the token is typed into the settings and never stored in the code. Only shapes go into the file, with names and numbers left open, and sentences the teacher marked as private are never included.

### Getting used to your accent

The app never hears your voice; the phone's or browser's speech service turns it into text, and only that text reaches `brain.js`. So it cannot learn an accent from the sound. What it does instead: the speech service returns several guesses for each sentence, and when its top guess has a word that means nothing while another guess has a similar-sounding word that fits, the brain uses that one. If the same word is misheard the same way twice, it is learned, and from then on the mistaken spelling is understood directly. It cannot change how the app itself speaks.

Roman Urdu, Urdu script and Devanagari are all understood.

## How it works

- `brain.js` is the understanding: a word list in three scripts, a number-word parser ("saadhe teen hazar" is 3500) and rules that turn a sentence into an entry or an answer. It has no browser dependencies.
- `visual.js` draws the screen: about 240 points joined by straight lines inside the outline of a brain, with signals travelling from point to point. No line crosses the brain's main folds, so the lobes read as separate, and points sit at different depths so the nearer ones are larger and brighter. The brain floats, breathes and turns slightly, and a band of light crosses it every few seconds. How busy it is follows the call, and in the Android app the microphone's loudness while listening.
- `app.js` is the call: the browser's speech recognition listens, the brain replies, the browser's speech synthesis speaks, then it listens again.
- Data is kept in the browser's `localStorage`, together with everything it has learned. Clearing the browser's site data erases it, so say "backup bana do" now and then; it downloads a file that "backup wapas lao" can load again.

## Limits

- It follows rules, not a language model. It handles the sentence shapes above and their variations, and learns new words when you teach or correct it; it cannot hold an open conversation or learn new kinds of sentence by itself.
- Chrome and Edge send microphone audio to their own speech service to turn it into text, so listening needs internet. Firefox has no speech recognition, so it does not work there.
- Voice quality is whatever the device provides. If there is no Urdu voice it uses a Hindi voice, and failing that an English voice reading Roman Urdu.

## Tests

`node bolta-munshi/tests/replay.js <bolta-munshi-baatein.txt>` replays a real conversation file through the current brain, showing the old reply and the new one for each sentence.

```
node --test bolta-munshi/tests/brain.test.js
```

## BolKhata's khata inside the Android app

The Android app keeps the hisaab with the logic of [BolKhata](https://github.com/abrar1499/BolKhata), the same code, not a rewrite. `android/bolkhata/src` holds BolKhata's logic files (its parser, chat engine, questions, queries, edits, commands, shared bills, accounts and stock), copied by `android/bolkhata/sync.sh` from a BolKhata checkout; the commit it came from is in `android/bolkhata/BOLKHATA_VERSION`. The script leaves out BolKhata's screens and its AI service files, and copies the few small helpers the logic borrows from the screen files into `Helpers.kt`.

`android/kotlin/com/boltamunshi/app/VoiceKhata.kt` runs that logic by voice, in the order the BolKhata chat does: its engine reads the sentence; new entries go through its reader (names, time, shared bills, payments, commands, edits, then the parser); questions, changes and deletions are carried out on a BolKhata ledger kept by BolKhata's own `Store`. Where BolKhata shows a card, the app asks aloud: "mita doon? haan ya nahi?", "kitne rupay?", "kaun sa: Ali ya Alia?". Every reply is spoken in Urdu, naming things with the word the person said ("chai", not "Tea & Coffee"), taken from BolKhata's own parser rules.

Anything that is not about the khata (greetings, small talk, notes, sums, the date) goes to `brain.js`, which in the app only talks: it no longer writes money down or answers about money. A command the teacher (Groq) writes is handed to the khata. Bolta Munshi's old money records are moved into the ledger once, and the ledger is kept in Downloads as `bolta-munshi-khata.json`, in BolKhata's backup format.

To update: `android/bolkhata/sync.sh /path/to/BolKhata`, then `android/tests/run.sh /path/to/BolKhata`, which runs this app's khata tests and BolKhata's own tests against the copied logic (needs `KOTLIN_HOME` and `ANDROID_HOME`). The web page still keeps its own simpler hisaab.

## Android app

`android/` is a small native app around the same `brain.js`. It is needed because a web page inside an Android app cannot use the microphone for speech recognition; the app uses the phone's own speech recogniser and text-to-speech instead, with one full-screen WebView running `brain.js` and `visual.js`. The screen and the way you use it are the same: tap to start, tap to end.

- Permissions: the microphone, and the internet for the teacher and the shared lessons.
- The hisaab is saved in the app's private storage (`hisaab.json`). On Android 10 and newer a copy, `bolta-munshi-backup.json`, is kept up to date in the phone's Downloads folder, which survives uninstalling the app; "backup wapas lao" opens the file picker to load it back. That copy is plain text, readable by anyone who can open the phone's files.
- It picks the highest-quality voice the phone has for the language.

Build it with a JDK, the Android SDK (`platforms;android-34`, `build-tools;35.0.0`) and the Kotlin compiler 2.0.x:

```
ANDROID_HOME=/path/to/android-sdk KOTLIN_HOME=/path/to/kotlinc bolta-munshi/android/build.sh
```

The APK lands in `android/build/bolta-munshi.apk`. It is signed with `android/debug.keystore`, a throwaway key kept in the repo so that every build can update the installed app without wiping its data.
