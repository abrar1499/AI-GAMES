/*
 * Bolta Munshi - the page: microphone in, voice out, and the saved hisaab.
 * Listening and speaking use the browser's own speech features, so there is
 * no API key. All understanding happens in brain.js.
 */
(function () {
  'use strict';

  var STORE = 'bolta-munshi-v1';
  var PREFS = 'bolta-munshi-prefs';
  var $ = function (id) { return document.getElementById(id); };

  // ------------------------------------------------------------ storage ---

  function load(key, fallback) {
    try { var raw = localStorage.getItem(key); return raw ? JSON.parse(raw) : fallback; }
    catch (e) { return fallback; }
  }
  function save(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); return true; }
    catch (e) { return false; }
  }
  function validDb(d) { return d && Array.isArray(d.entries) && Array.isArray(d.notes); }

  var db = load(STORE, null);
  if (!validDb(db)) db = Munshi.emptyDb();
  var prefs = load(PREFS, {}) || {};

  // --------------------------------------------------------------- chat ---

  var chat = $('chat');
  function showEmpty() {
    var p = document.createElement('p');
    p.className = 'empty'; p.id = 'empty';
    p.innerHTML = 'Bol kar batayein, jaise <b>"aaj 500 ka petrol dala"</b>.<br>Phir poochein: <b>"is mahine kitna kharcha hua?"</b>';
    chat.appendChild(p);
  }
  function addMsg(who, text) {
    var empty = $('empty'); if (empty) empty.remove();
    var div = document.createElement('div');
    div.className = 'msg ' + who;
    div.textContent = text;
    chat.appendChild(div);
    chat.scrollTop = chat.scrollHeight;
  }
  showEmpty();

  function notice(text) {
    var n = $('notice');
    n.textContent = text || ''; n.hidden = !text;
  }

  // -------------------------------------------------------------- voice ---

  var SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  var synth = window.speechSynthesis;
  var MODES = {
    ur: { rec: 'ur-PK', voice: /^ur/i, script: 'ur' },
    hi: { rec: 'hi-IN', voice: /^hi/i, script: 'hi' },
    en: { rec: 'en-IN', voice: /^en/i, script: 'ro' }
  };
  var voices = [];
  function refreshVoices() { voices = synth ? synth.getVoices() : []; describeVoice(); }
  function voiceFor(mode) {
    return voices.filter(function (v) { return MODES[mode].voice.test(v.lang); })[0] || null;
  }
  /** Urdu if this device can speak it, else Hindi (same spoken language), else English. */
  function activeMode() {
    if (prefs.mode && prefs.mode !== 'auto') return prefs.mode;
    if (voiceFor('ur')) return 'ur';
    if (voiceFor('hi')) return 'hi';
    return 'ur';
  }
  function describeVoice() {
    var el = $('voiceInfo'); if (!el) return;
    var mode = activeMode(), v = voiceFor(mode);
    if (!synth) el.textContent = 'Is browser mein bolne wali awaaz nahi hai. Jawab sirf likh kar aaye ga.';
    else if (v) el.textContent = 'Awaaz: ' + v.name + ' (' + v.lang + ')';
    else el.textContent = 'Is zabaan ki awaaz is device par nahi mili. Jawab English awaaz mein Roman Urdu parh kar sunaya jaye ga.';
  }
  if (synth) { refreshVoices(); synth.addEventListener('voiceschanged', refreshVoices); }

  var orb = $('orb'), statusEl = $('status'), interimEl = $('interim');
  var inCall = false, state = 'idle', rec = null, speakTimer = null;

  var LABELS = {
    idle: ['Call shuru karein', 'Button dabayein aur bolna shuru karein.'],
    listening: ['Sun raha hoon', 'Boliye, main sun raha hoon. Call band karne ke liye button dabayein.'],
    thinking: ['Soch raha hoon', ''],
    speaking: ['Bol raha hoon', 'Jawab de raha hoon. Rokne ke liye button dabayein.']
  };
  function setState(s) {
    state = s;
    orb.dataset.state = s;
    $('orbLabel').textContent = LABELS[s][0];
    statusEl.textContent = LABELS[s][1];
    orb.setAttribute('aria-label', s === 'idle' ? 'Call shuru karein' : 'Call band karein');
  }

  function speak(reply, done) {
    var finished = false;
    function end() { if (finished) return; finished = true; clearTimeout(speakTimer); done && done(); }
    if (!synth) return end();
    var mode = activeMode(), voice = voiceFor(mode);
    var text = voice ? reply.speak[MODES[mode].script] : reply.speak.ro;
    var u = new SpeechSynthesisUtterance(text);
    if (voice) { u.voice = voice; u.lang = voice.lang; } else { u.lang = 'en-IN'; }
    u.rate = Number(prefs.rate) || 1;
    u.onend = end; u.onerror = end;
    synth.cancel();
    synth.speak(u);
    // some browsers never fire "end"; do not let the call hang on that
    speakTimer = setTimeout(end, 4000 + text.length * 120);
  }

  function listen() {
    if (!inCall || !SR) return;
    setState('listening');
    interimEl.textContent = '';
    rec = new SR();
    rec.lang = MODES[activeMode()].rec;
    rec.interimResults = true;
    rec.continuous = false;
    var heard = '', fatal = false;
    rec.onresult = function (ev) {
      var interim = '';
      for (var i = ev.resultIndex; i < ev.results.length; i++) {
        if (ev.results[i].isFinal) heard += ev.results[i][0].transcript;
        else interim += ev.results[i][0].transcript;
      }
      interimEl.textContent = heard || interim;
    };
    rec.onerror = function (ev) {
      if (ev.error === 'not-allowed' || ev.error === 'service-not-allowed') {
        fatal = true; notice('Mic ki ijazat nahi mili. Browser ki settings mein is page ke liye microphone allow karein, phir dobara call shuru karein.');
      } else if (ev.error === 'network') {
        fatal = true; notice('Awaaz samajhne ke liye internet chahiye aur abhi rabta nahi ho raha. Internet check karein, ya neeche likh kar batayein.');
      } else if (ev.error === 'language-not-supported') {
        fatal = true; notice('Yeh browser is zabaan mein sun nahi sakta. Settings mein doosri zabaan chunein.');
      } else if (ev.error === 'audio-capture') {
        fatal = true; notice('Koi microphone nahi mila.');
      }
    };
    rec.onend = function () {
      rec = null;
      if (!inCall) return;
      if (fatal) return hangUp();
      heard = heard.trim();
      if (heard) respond(heard, true); else listen();   // silence: keep the line open
    };
    try { rec.start(); } catch (e) { setTimeout(listen, 400); }
  }

  function stopListening() {
    if (rec) { rec.onend = null; rec.onresult = null; try { rec.abort(); } catch (e) {} rec = null; }
  }

  function respond(text, spoken) {
    interimEl.textContent = '';
    addMsg('me', text);
    if (inCall) setState('thinking');
    var reply = Munshi.handle(text, db, new Date());
    if (reply.changed && !save(STORE, db)) notice('Hisaab save nahi ho saka. Browser ki storage band ya bhari hui hai.');
    addMsg('bot', reply.show);
    if (!inCall && !spoken) { if (prefs.readTyped !== false) speak(reply); return; }
    stopListening();
    setState('speaking');
    speak(reply, function () {
      if (!inCall) return;
      if (reply.action === 'hangup') hangUp(); else listen();
    });
  }

  function startCall() {
    notice('');
    if (!SR) {
      notice('Yeh browser mic se sun nahi sakta. Chrome ya Edge mein kholein, ya neeche likh kar batayein.');
      return;
    }
    inCall = true;
    setState('speaking');
    var hello = { speak: { ro: 'Assalam o alaikum! Bataiye.', ur: 'السلام علیکم! بتائیے۔', hi: 'अस्सलाम अलैकुम! बताइए।' } };
    addMsg('bot', hello.speak.ro);
    speak(hello, function () { if (inCall) listen(); });
  }
  function hangUp() {
    inCall = false;
    stopListening();
    if (synth) synth.cancel();
    clearTimeout(speakTimer);
    interimEl.textContent = '';
    setState('idle');
  }

  orb.addEventListener('click', function () {
    if (!inCall) return startCall();
    if (state === 'speaking') { if (synth) synth.cancel(); clearTimeout(speakTimer); listen(); return; }   // cut in
    hangUp();
  });
  document.addEventListener('visibilitychange', function () { if (document.hidden && inCall) hangUp(); });

  $('typeForm').addEventListener('submit', function (ev) {
    ev.preventDefault();
    var box = $('typeBox'), text = box.value.trim();
    if (!text) return;
    box.value = '';
    respond(text, false);
  });

  // ------------------------------------------------------------- hisaab ---

  function fmt(n) { return (Math.round(n * 100) / 100).toLocaleString('en-US'); }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  function removeEntry(id) {
    db.entries = db.entries.filter(function (e) { return e.id !== id; });
    db.notes = db.notes.filter(function (n) { return n.id !== id; });
    save(STORE, db); renderBook();
  }
  function row(what, when, amount, id) {
    var li = el('li');
    var w = el('span', 'what', what);
    if (when) w.appendChild(el('span', 'when', when));
    li.appendChild(w);
    if (amount != null) li.appendChild(el('span', 'amt', amount));
    var b = el('button', 'ghost', 'Mitayein');
    b.type = 'button';
    b.setAttribute('aria-label', what + ' mitayein');
    b.addEventListener('click', function () { removeEntry(id); });
    li.appendChild(b);
    return li;
  }
  function section(body, heading, items) {
    if (!items.length) return;
    body.appendChild(el('h3', null, heading));
    var ul = el('ul', 'rows');
    items.forEach(function (li) { ul.appendChild(li); });
    body.appendChild(ul);
  }

  function renderBook() {
    var body = $('bookBody');
    body.textContent = '';
    var month = Munshi.iso(new Date()).slice(0, 7);
    var spent = 0, earned = 0;
    db.entries.forEach(function (e) {
      if (e.date.slice(0, 7) !== month) return;
      if (e.type === 'expense') spent += e.amount;
      if (e.type === 'income') earned += e.amount;
    });
    var totals = el('div', 'totals');
    [['Is mahine kharcha', spent], ['Is mahine aamdani', earned], ['Bachat', earned - spent]].forEach(function (t) {
      var tile = el('div', 'tile');
      tile.appendChild(el('span', null, t[0]));
      tile.appendChild(el('b', null, 'Rs ' + fmt(t[1])));
      totals.appendChild(tile);
    });
    body.appendChild(totals);

    var newest = db.entries.slice().reverse();
    section(body, 'Kharche', newest.filter(function (e) { return e.type === 'expense'; }).map(function (e) {
      return row(e.item || Munshi.labelOf(e).ro, e.date, 'Rs ' + fmt(e.amount), e.id);
    }));
    section(body, 'Aamdani', newest.filter(function (e) { return e.type === 'income'; }).map(function (e) {
      return row(e.item || 'Aamdani', e.date, 'Rs ' + fmt(e.amount), e.id);
    }));
    section(body, 'Udhar', newest.filter(function (e) { return e.type === 'loan'; }).map(function (e) {
      return row(e.person + (e.delta > 0 ? ' ke zimme barha' : ' ke zimme ghata'), e.date, 'Rs ' + fmt(e.amount), e.id);
    }));
    section(body, 'Yaad rakhi hui baatein', db.notes.slice().reverse().map(function (n) {
      return row(n.text, new Date(n.ts).toLocaleDateString('en-GB'), null, n.id);
    }));
    if (!db.entries.length && !db.notes.length) body.appendChild(el('p', 'hint', 'Abhi tak kuch nahi likha.'));
  }

  // ------------------------------------------------------------ dialogs ---

  $('openBook').addEventListener('click', function () { renderBook(); $('book').showModal(); });
  $('openSettings').addEventListener('click', function () {
    $('mode').value = prefs.mode || 'auto';
    $('rate').value = prefs.rate || 1;
    describeVoice();
    $('settings').showModal();
  });
  document.querySelectorAll('[data-close]').forEach(function (b) {
    b.addEventListener('click', function () { b.closest('dialog').close(); });
  });
  $('mode').addEventListener('change', function () { prefs.mode = this.value; save(PREFS, prefs); describeVoice(); });
  $('rate').addEventListener('change', function () { prefs.rate = Number(this.value); save(PREFS, prefs); });

  $('exportBtn').addEventListener('click', function () {
    var blob = new Blob([JSON.stringify(db, null, 2)], { type: 'application/json' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'bolta-munshi-backup-' + Munshi.iso(new Date()) + '.json';
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
  });
  $('importBtn').addEventListener('click', function () { $('importFile').click(); });
  $('importFile').addEventListener('change', function () {
    var file = this.files[0]; this.value = '';
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function () {
      var data = null;
      try { data = JSON.parse(reader.result); } catch (e) {}
      if (!validDb(data)) return alert('Yeh file Bolta Munshi ka backup nahi lagti.');
      if (!confirm('Is backup se maujooda hisaab badal jaye ga. Jari rakhein?')) return;
      db = data; save(STORE, db);
      alert('Backup wapas aa gaya.');
    };
    reader.readAsText(file);
  });
  $('clearBtn').addEventListener('click', function () {
    if (!confirm('Sara hisaab aur sari baatein hamesha ke liye mit jayein gi. Pakka?')) return;
    db = Munshi.emptyDb(); save(STORE, db);
    chat.textContent = ''; showEmpty();
    $('settings').close();
  });

  if (!SR) notice('Yeh browser mic se sun nahi sakta. Chrome ya Edge mein kholein, ya neeche likh kar batayein.');
})();
