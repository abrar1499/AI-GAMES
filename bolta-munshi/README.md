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

Roman Urdu, Urdu script and Devanagari are all understood.

## How it works

- `brain.js` is the understanding: a word list in three scripts, a number-word parser ("saadhe teen hazar" is 3500) and rules that turn a sentence into an entry or an answer. It has no browser dependencies.
- `app.js` is the call: the browser's speech recognition listens, the brain replies, the browser's speech synthesis speaks, then it listens again.
- Data is kept in the browser's `localStorage`. There is no screen for it, so clearing the browser's site data erases it.

## Limits

- It follows rules, not a language model. It handles the sentence shapes above and their variations; it cannot hold an open conversation.
- Chrome and Edge send microphone audio to their own speech service to turn it into text, so listening needs internet. Firefox has no speech recognition, so it does not work there.
- Voice quality is whatever the device provides. If there is no Urdu voice it uses a Hindi voice, and failing that an English voice reading Roman Urdu.

## Tests

```
node --test bolta-munshi/tests/brain.test.js
```
