/*
 * Bolta Munshi - the call. Nothing to read or press: tap the screen once to
 * start, talk, and tap again (or say "khuda hafiz") to end.
 * Listening and speaking use the browser's own speech features, so there is
 * no API key. All understanding happens in brain.js.
 */
(function () {
  'use strict';

  var STORE = 'bolta-munshi-v1';

  function loadDb() {
    try {
      var d = JSON.parse(localStorage.getItem(STORE));
      if (d && Array.isArray(d.entries) && Array.isArray(d.notes)) return d;
    } catch (e) {}
    return Munshi.emptyDb();
  }
  function saveDb() {
    try { localStorage.setItem(STORE, JSON.stringify(db)); return true; } catch (e) { return false; }
  }
  var db = loadDb();

  var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  var synth = window.speechSynthesis;
  var button = document.getElementById('call'), problemEl = document.getElementById('problem');

  // recogniser language and the script its voice can read
  var MODES = {
    ur: { rec: 'ur-PK', voice: /^ur/i, script: 'ur' },
    hi: { rec: 'hi-IN', voice: /^hi/i, script: 'hi' }
  };
  function voiceFor(mode) {
    var all = synth ? synth.getVoices() : [];
    return all.filter(function (v) { return MODES[mode].voice.test(v.lang); })[0] || null;
  }
  /** Urdu if this device can speak it, else Hindi (the same spoken language). */
  function mode() { return voiceFor('ur') || !voiceFor('hi') ? 'ur' : 'hi'; }
  if (synth) synth.getVoices();          // voices load lazily; ask early

  var MSG = {
    hello: { ro: 'Assalam o alaikum! Bataiye.', ur: 'السلام علیکم! بتائیے۔', hi: 'अस्सलाम अलैकुम! बताइए।' },
    notSaved: { ro: 'Yeh save nahi ho saka. Browser ki storage band ya bhari hui hai.', ur: 'یہ محفوظ نہیں ہو سکا۔ براؤزر کی اسٹوریج بند یا بھری ہوئی ہے۔', hi: 'यह सेव नहीं हो सका। ब्राउज़र की स्टोरेज बंद या भरी हुई है।' },
    mic: { ro: 'Mic ki ijazat nahi mili. Browser mein is page ke liye microphone allow karein.', ur: 'مائیک کی اجازت نہیں ملی۔ براؤزر میں اس صفحے کے لیے مائیکروفون کی اجازت دیں۔', hi: 'माइक की इजाज़त नहीं मिली। ब्राउज़र में इस पेज के लिए माइक्रोफ़ोन की इजाज़त दें।' },
    net: { ro: 'Awaaz samajhne ke liye internet chahiye, aur abhi rabta nahi ho raha.', ur: 'آواز سمجھنے کے لیے انٹرنیٹ چاہیے، اور ابھی رابطہ نہیں ہو رہا۔', hi: 'आवाज़ समझने के लिए इंटरनेट चाहिए, और अभी संपर्क नहीं हो रहा।' },
    noMic: { ro: 'Koi microphone nahi mila.', ur: 'کوئی مائیکروفون نہیں ملا۔', hi: 'कोई माइक्रोफ़ोन नहीं मिला।' },
    lang: { ro: 'Yeh browser Urdu mein sun nahi sakta.', ur: 'یہ براؤزر اردو میں سن نہیں سکتا۔', hi: 'यह ब्राउज़र उर्दू में सुन नहीं सकता।' },
    noSr: { ro: 'Yeh browser mic se sun nahi sakta. Chrome ya Edge mein kholein.', ur: 'یہ براؤزر مائیک سے سن نہیں سکتا۔ کروم یا ایج میں کھولیں۔', hi: 'यह ब्राउज़र माइक से सुन नहीं सकता। क्रोम या एज में खोलें।' }
  };

  var inCall = false, rec = null, speakTimer = null;

  function setState(s) {
    button.dataset.state = s;
    button.setAttribute('aria-label', s === 'idle' ? 'Call shuru karein' : 'Call band karein');
  }

  function speak(texts, done) {
    var finished = false;
    function end() { if (finished) return; finished = true; clearTimeout(speakTimer); if (done) done(); }
    if (!synth) return end();
    var m = mode(), voice = voiceFor(m);
    var text = voice ? texts[MODES[m].script] : texts.ro;
    var u = new SpeechSynthesisUtterance(text);
    if (voice) { u.voice = voice; u.lang = voice.lang; } else { u.lang = 'en-IN'; }
    u.onend = end; u.onerror = end;
    synth.cancel();
    synth.speak(u);
    // some browsers never fire "end"; do not let the call hang on that
    speakTimer = setTimeout(end, 4000 + text.length * 120);
  }

  /** A problem ends the call; it is spoken, and written in case speech is the problem. */
  function fail(texts) {
    hangUp();
    problemEl.textContent = texts.ro;
    speak(texts);
  }

  function listen() {
    if (!inCall) return;
    setState('listening');
    rec = new SR();
    rec.lang = MODES[mode()].rec;
    rec.interimResults = false;
    rec.continuous = false;
    var heard = '', error = null;
    rec.onresult = function (ev) {
      for (var i = ev.resultIndex; i < ev.results.length; i++) {
        if (ev.results[i].isFinal) heard += ev.results[i][0].transcript;
      }
    };
    rec.onerror = function (ev) {
      if (ev.error === 'not-allowed' || ev.error === 'service-not-allowed') error = MSG.mic;
      else if (ev.error === 'network') error = MSG.net;
      else if (ev.error === 'audio-capture') error = MSG.noMic;
      else if (ev.error === 'language-not-supported') error = MSG.lang;
    };
    rec.onend = function () {
      rec = null;
      if (!inCall) return;
      if (error) return fail(error);
      heard = heard.trim();
      if (heard) respond(heard); else listen();          // silence: keep the line open
    };
    try { rec.start(); } catch (e) { setTimeout(listen, 400); }
  }

  function respond(text) {
    var reply = Munshi.handle(text, db, new Date());
    var texts = reply.changed && !saveDb() ? MSG.notSaved : reply.speak;
    setState('speaking');
    speak(texts, function () {
      if (!inCall) return;
      if (reply.action === 'hangup') hangUp(); else listen();
    });
  }

  function startCall() {
    problemEl.textContent = '';
    if (!SR) return fail(MSG.noSr);
    inCall = true;
    setState('speaking');
    speak(MSG.hello, listen);
  }

  function hangUp() {
    inCall = false;
    if (rec) { rec.onend = null; rec.onresult = null; try { rec.abort(); } catch (e) {} rec = null; }
    if (synth) synth.cancel();
    clearTimeout(speakTimer);
    setState('idle');
  }

  button.addEventListener('click', function () {
    if (!inCall) return startCall();
    if (button.dataset.state === 'speaking') {            // cut in while it is talking
      if (synth) synth.cancel();
      clearTimeout(speakTimer);
      return listen();
    }
    hangUp();
  });
  document.addEventListener('visibilitychange', function () { if (document.hidden && inCall) hangUp(); });
})();
