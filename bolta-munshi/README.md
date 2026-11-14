**The app is now called Smart Book** (it was Bolta Munshi; the folder, package name and file names keep the old name so updates install over the old app).

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

The Android app also keeps the whole conversation with no limit: one line per exchange is added to `smart-book-baatein.txt` in Downloads (Android 10+), and nothing is ever removed from it. Like the backup, it is plain text.

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

`node bolta-munshi/tests/replay.js <smart-book-baatein.txt>` replays a real conversation file through the current brain, showing the old reply and the new one for each sentence.

```
node --test bolta-munshi/tests/brain.test.js
```

## Smart Book 8.0: rebuilt from scratch

The Android app was rebuilt in 8.0 around two parts only:

- **One brain, an AI** (`Brain.kt`). Every spoken or typed sentence goes to the AI with the memory book and its exact totals. It answers in a few words of Roman Urdu and returns JSON: `reply`, `urdu`, `add`, `remove`, `change`, `report`. Keys for Claude, OpenRouter, Gemini and Groq go in Settings › Brain; they are tried top to bottom, and a failing one rests three minutes. No canned answers: without a key the app only says to add one.
- **The memory book** (`Memory.kt`). Plain records: money (expense, income, gave, took, got back, paid back, sale, purchase) and anything else (notes, tasks). The app, not the AI, adds up the totals (today, yesterday, this week, this month, last month, this year, all time, loans per person) and hands them to the AI, so numbers are never made up. It is saved after every change and copied to Downloads as `smart-book-memory.json`; Settings › Memory book restores a copy, makes a PDF report (`Report.kt`) or forgets everything. The old hisaab (BolKhata ledger) is moved in once on the first start of 8.0.

**8.1.** What was just written shows above the chat box ("✓ Petrol · 500") with **Undo** for nine seconds. When no AI can answer (no internet, limit reached), the sentence is kept and written down the next time an AI answers, or quietly when the app opens. Older records that match the words said (a name, an item) are sent with the newest 150, and earlier months have their own totals, so the AI can answer about anything in the book. **Reminders**: "kal subah 9 baje bill yaad dilana" saves a task with a time and rings a notification then (`Remind.kt`; set again after a restart). The **memory book page** (Settings › Memory book, or say "memory book dikhao") shows today's and this month's spending, search, filters (Money, Loans, Notes, To do) and every record by day; tap a record twice to delete it. A call ends by itself after about half a minute of silence, and money without an amount is never answered with "likh liya".

Voices are unchanged: Google Cloud TTS → ElevenLabs → the phone's own voice. The old app (rule brain, BolKhata khata, voice clips) is kept in `android/legacy/` for reference and is not built. Tests: `android/tests/run.sh`.

## Android app

`android/` is a small native app around the same `brain.js`. It is needed because a web page inside an Android app cannot use the microphone for speech recognition; the app uses the phone's own speech recogniser and text-to-speech instead, with one full-screen WebView running `brain.js` and `visual.js`. The screen and the way you use it are the same: tap to start, tap to end.

- Permissions: the microphone, and the internet for the teacher and the shared lessons.
- The hisaab is saved in the app's private storage (`hisaab.json`). On Android 10 and newer a copy, `smart-book-backup.json`, is kept up to date in the phone's Downloads folder, which survives uninstalling the app; "backup wapas lao" opens the file picker to load it back. That copy is plain text, readable by anyone who can open the phone's files.
- It speaks with Google's speech engine when the phone has it (Urdu is one of its voices), and picks the best Urdu voice installed. Without an Urdu voice it falls back to Hindi or English, and says once how to get Urdu. The settings show which voice speaks, open the Urdu voice download, and step through the installed voices ("Doosri awaaz sunein"); the one chosen is kept. "Hindi awaaz istemal karein" switches to the Hindi voice even when Urdu is installed, since spoken Hindi and Urdu are nearly the same and on many phones the Hindi voice sounds better; listening stays in Urdu. Replies are then spoken from Hindi letters: `Devanagari.kt` turns any Roman Urdu into Devanagari, from a word list and by sound for names.

**Your own voice.** Settings → "Apni awaaz record karein" opens a recording screen that shows one word or phrase at a time (the munshi's common lines, things money goes on, the numbers 1–99 with sau/hazaar/lakh/crore, the months). Press Record and say it; recording stops by itself when you go quiet, plays back, and moves to the next one until you press Ruk. Words the munshi needed but had no recording for are asked for first. Replies are then put together from your recordings, longest phrase first, with numbers spoken as Urdu words (`MyVoice.kt`), and played as one sound (`VoiceClips.kt`). Words not recorded yet are said by the phone's voice; if less than half of a line is recorded, the phone's voice says all of it. The test line on the recording screen always uses every recorded word and names the words still missing; its words come first in the list. Recordings are joined smoothly: each is cut tight to its words, brought to the same loudness, and blended into the next over 25 ms.

**ElevenLabs instead of recording.** On the recording screen, "ElevenLabs se awaaz banwayein" takes an ElevenLabs key and a voice (listed from the account, or a pasted Voice ID, such as the user's own cloned voice) and makes every word on the list once (`ElevenLabs.kt`, 16 kHz sound, sent as Hindi letters so the accent is right, with words around it so it sounds mid-sentence). They are kept with the recordings, so after that no internet is needed. The user's own recordings are kept unless they choose otherwise; stopping halfway and starting again carries on where it stopped.

**The voice that comes with the app.** `voice-script/` holds the word list for the ElevenLabs website (`elevenlabs-alfaaz.txt`, `phrases.tsv`) and the words cut from the audio made there (`clips/`, one 16 kHz file per word, packed into the app's assets by `build.sh`). The app speaks from them out of the box; a word the user records themselves takes the place of the built-in one. The current words were made with a 1-second break after each (`elevenlabs-1-second.txt`, a model that honours breaks) and cut by `split_pauses.py`, which matches the pieces between pauses to the list and reports words ElevenLabs skipped. For audio without pauses, `split.py` lines up a speech recogniser's word times (faster-whisper, medium, run once on a computer) with the list and cuts in the pause nearest each boundary; `doubtful.txt` lists the words whose cut could not be confirmed, and `sab-alfaaz-sunein.mp3` plays every word in list order for checking by ear. Recordings stay on the phone only.

Build it with a JDK, the Android SDK (`platforms;android-34`, `build-tools;35.0.0`) and the Kotlin compiler 2.0.x:

```
ANDROID_HOME=/path/to/android-sdk KOTLIN_HOME=/path/to/kotlinc bolta-munshi/android/build.sh
```

The APK lands in `android/build/smart-book.apk`. It is signed with `android/debug.keystore`, a throwaway key kept in the repo so that every build can update the installed app without wiping its data.

**Training data for an offline voice.** `voice-model/make_sentences.py` writes `sentences.txt`: 1139 sentences (about an hour of speech) of what the munshi says, with every number 1–99 and amounts of every size written as words, plus chat lines and everyday Urdu. In the app, Apni awaaz → "Offline awaaz ke liye data (ElevenLabs)" has ElevenLabs say every sentence once (in Hindi letters, 22 kHz, steady voice settings; `VoiceData.kt`), carrying on where it stopped, and "Zip banayein" packs them into `smart-book-awaaz-data.zip` in Downloads (`wavs/0001.wav`, `metadata.csv` with `id|Hindi text`, `roman.csv`), the form voice training expects. `Devanagari.kt` now has written-out spellings for every word in those sentences.

**Training the offline voice.** `voice-model/smart-book-train.ipynb` is a Google Colab notebook: it reads `smart-book-awaaz-data.zip` from a Drive folder `SmartBook`, installs Piper (`OHF-voice/piper1-gpl`), fine-tunes from Piper's Hindi `hi_IN rohan medium` checkpoint with espeak `hi` (the sentences are in Hindi letters), copies the latest checkpoint to Drive every 10 minutes so a closed Colab session can carry on, and exports `smart-book-medium.onnx` with its `.onnx.json`. Piper's guide suggests about 2,000 epochs for under 5 hours of audio; the notebook starts at 1,000 to fit free Colab.

**Live ElevenLabs voice.** With an ElevenLabs key and voice saved (Apni awaaz → ElevenLabs), every reply is first asked of ElevenLabs as a whole sentence (numbers as words, Hindi letters, `LiveVoice.kt`) and played; each line is kept on the phone (about 40 MB at most), so a line said before plays again without internet or credits. With no answer in 6 seconds, no internet or no credits, the reply comes from the built-in words or the phone's voice instead. Settings → "ElevenLabs awaaz (internet)" turns it on and off.

**Claude as the teacher.** A key starting `sk-ant-` in Settings → AI key makes Claude the teacher: Anthropic's Messages API over plain HTTP (`x-api-key`, `anthropic-version: 2026-10-21`; the app has no libraries, so no SDK), model `claude-haiku-5-5` unless a `claude-...` model is typed in the model box, effort `low`, and the reply's text blocks read as the JSON verdict. A refusal or any error means the app answers on its own, as with the other services.

**Settings, in three parts.** 🧠 Brain: a key box for each AI service (Claude, OpenRouter, Gemini, Groq), tried in that order until one answers; a service that fails rests five minutes, and a problem is shown only when none answers. The old single key box is moved into the right one on first start. 🔊 ElevenLabs: key, voice, one of five models (Turbo v2.5, Flash v2.5, Multilingual v2, v3, v4) and whether replies use it live. 🎓 Training: making the training sentences and zip, opening the Colab notebook, and the word recordings.

**New look (6.0).** The home screen shows "Smart Book", a status line (Ready / Listening… / Speaking) and the last thing heard or said. Settings open with three quick taps and then any stroke on the screen within four seconds; one tap still talks. The settings are dark rounded sheets in English (`Sheet.kt`): Settings (Brain, ElevenLabs, Training, Voice, Phone voice, GitHub sync), Brain (one line per AI with its status: Working, Limit reached, Invalid key, Blocked, No internet, Off; a switch; tap the name to enter and save the key, which is checked at once), ElevenLabs (key with credits left, voice, five models, use in conversation) and Training (make sentences, export zip, open Colab). Keys that are switched off are skipped; each answer or failure updates that key's status.

**Short and natural (6.2), to save voice credits.** A call starts with just "Ji?". A change to the khata is confirmed in a word or two ("Likh liya.", "Ho gaya.", "Kar diya.", "Mita diya.") while the screen shows the full line. With an AI key, anything that is not a khata command is answered by the AI as a short natural reply in Roman Urdu (usually 1 to 6 words, longer only when asked to explain), with the last few lines of the conversation for context; if it hears a money command the khata didn't catch, it hands the khata a plain command instead. If the AI is slow (8 s) or fails, the phone's own brain answers. "Report do" / "PDF de do" says "Ek minute, de rahi hoon." and saves `smart-book-report-<date>.pdf` in Downloads (`ReportPdf.kt`: this month's money in and out, spending by category, udhar, latest entries). The reply's text now appears on screen when its sound starts.

**Voice screens in the new look (6.3).** Voice: counts (yours, ElevenLabs, words), one big word with its state, Record (stops by itself and moves on), play, test sentence, use word clips on/off, make words, export clips, delete my recordings. Make words: keep my recordings, remake all, and how many words and characters that will take before Start. Training sentences: how many are made and the characters left, Start, Export zip. Work in progress shows on a sheet with a count, a bar and the current line. On the home screen the heard or said line is a small soft chip above the bottom, hidden when empty.

**Updates inside the app (6.4).** On start the app reads `bolta-munshi/releases/latest.json` from this branch; when it names a newer version, a small card appears under the status line ("Update available · 6.5", what's new, Update, ×). Update downloads `releases/smart-book.apk` with Android's download manager and opens the installer (Android asks once to allow installs from Smart Book). The APK is signed with the same key, so it installs over the app and keeps all data. × hides that version's card. To publish a release: bump the version in `android/build.sh`, build, run `android/release.sh "what's new"`, then commit and push `releases/`.

**6.6.** ElevenLabs replies stream: the sound plays as it arrives (`optimize_streaming_latency=3`), so speech starts in a moment; kept lines play at once. The screen shows exactly what is spoken, and no longer what the user said. The header is centred. A spending question with a word nothing in the khata was ever called (usually a misheard "kharcha", as in "aaj achha kitna hua") now gets the total for that period instead of "kuch nahi mila".

**6.7, a soft voice.** The recogniser waits longer before ending a sentence (2 s of silence, at least 2.5 s of listening) and reports words as it hears them; if it then gives up on a quiet voice, the words it did hear are answered instead of being dropped. Settings › Headset microphone routes the Bluetooth headset's mic (SCO) during a call.

**Google voice (7.2).** Settings › Google voice takes a Google Cloud API key (Text-to-Speech API enabled), lists that key's Urdu and Hindi voices with the most natural kinds first (Chirp 3 HD, Neural2, WaveNet, Standard), and speaks replies with the one chosen (`GoogleVoice.kt`: `text:synthesize`, LINEAR16 at 16 kHz, each line kept on the phone). An Urdu voice is sent the Urdu-script line, a Hindi one the line in Hindi letters. Replies go Google → ElevenLabs → the phone ("Before ElevenLabs" swaps the first two); whichever fails says why on screen and the next one speaks.

**Phone voice at its best (7.3).** The phone's own voice picks the installed voice with the highest quality (online voices first when there is internet, then the quickest), speaks a touch slower than the engine's default (0.95), and reads one sentence at a time with a short breath (180 ms) between them. Settings › Phone voice lists the language (Urdu or Hindi), the voices best first, speed (Slow, Natural, Fast) and pitch (Deeper, Normal, Higher); each choice is heard at once.

**Chat box (7.5).** A rounded box at the bottom of the home screen takes typed messages; they go the same way as spoken ones (khata, phone brain, AI) and the reply is shown above the box and spoken. Typing during a call stops the listening for that message; with no call on, the app answers and goes back to Ready.

