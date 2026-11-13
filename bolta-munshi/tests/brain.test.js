// Run with: node --test bolta-munshi/tests/brain.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const M = require('../brain.js');

const NOW = new Date(2026, 10, 12, 15, 30);          // Thursday 12 Nov 2026
function talk(lines, db) {
  db = db || M.emptyDb();
  const replies = lines.map((l) => M.handle(l, db, NOW));
  return { db, replies, last: replies[replies.length - 1] };
}
const amounts = (db, type) => db.entries.filter((e) => e.type === type).map((e) => e.amount);

test('books an expense with category, amount and date', () => {
  const { db, last } = talk(['aaj 500 ka petrol dala']);
  assert.deepEqual(db.entries.map((e) => [e.type, e.amount, e.cat, e.date]), [['expense', 500, 'petrol', '2026-11-12']]);
  assert.match(last.show, /petrol ke 500 rupay likh liye/);
  assert.equal(last.changed, true);
});

test('reads amounts written as words, fractions and with commas', () => {
  const { db } = talk(['dhai hazar ka rashan liya', 'saadhe teen sau ki dawai li', 'ek hazar do sau ka kiraya diya',
    'kal 1,200 bijli ka bill diya', 'dedh lakh ki gaari', 'pachees rupay ki chai']);
  assert.deepEqual(amounts(db, 'expense'), [2500, 350, 1200, 1200, 150000, 25]);
  assert.equal(db.entries[3].date, '2026-11-11');
});

test('"do" and "ek" are not numbers unless a unit follows', () => {
  const { db, last } = talk(['chai 50', 'mita do']);
  assert.equal(db.entries.length, 0);
  assert.match(last.show, /mita di/);
});

test('one sentence can hold two expenses', () => {
  const { db, last } = talk(['300 ka petrol aur 150 ki chai']);
  assert.deepEqual(db.entries.map((e) => [e.amount, e.cat]), [[300, 'petrol'], [150, 'khana']]);
  assert.match(last.show, /kul kharcha 450/);
});

test('answers totals by day, month, category and item', () => {
  const { replies } = talk(['aaj 500 ka petrol dala', 'kal 200 ka petrol dala', 'cycle 3000 ki li',
    'aaj kitna kharcha hua', 'is mahine kitna kharcha hua', 'petrol par kitna laga', 'cycle par kitna kharch hua',
    'sab se zyada kharcha kis par hua', 'pichle mahine kitna kharcha hua']);
  assert.match(replies[3].show, /Aaj kul kharcha 3,500/);
  assert.match(replies[4].show, /Is mahine kul kharcha 3,700/);
  assert.match(replies[5].show, /petrol par 700/);
  assert.match(replies[6].show, /cycle par 3,000/);
  assert.match(replies[7].show, /zyada kharcha cycle par/);
  assert.match(replies[8].show, /Pichle mahine koi kharcha nahi/);
});

test('questions never change the saved data', () => {
  const { db, last } = talk(['petrol 500', 'petrol par kitna laga']);
  assert.equal(db.entries.length, 1);
  assert.equal(last.changed, false);
});

test('income and savings', () => {
  const { db, last } = talk(['50000 tankhwah mili', 'petrol 500', 'is mahine kitni bachat hui']);
  assert.deepEqual(amounts(db, 'income'), [50000]);
  assert.match(last.show, /aamdani 50,000, kharcha 500, bachat 49,500/);
});

test('loans keep a running balance per person', () => {
  const { replies } = talk(['Ali ko 1000 udhar diye', 'Ali ne 400 wapas kiye', 'Ahmed se 2000 udhar liye',
    'Ali ka kitna udhar hai', 'udhar ka hisaab batao']);
  assert.match(replies[3].show, /Ali ne aap ko 600 rupay dene hain/);
  assert.match(replies[4].show, /Aap ne Ahmed ko 2,000 rupay dene hain/);
});

test('remembers facts and recalls them, without booking them as money', () => {
  const { db, replies } = talk(['yaad rakho ke meri gaari ka number LEA 1234 hai', 'meri ammi ki salgirah 5 March ko hai',
    'meri salary 50000 hai', 'gaari ka number kya hai', 'ammi ki salgirah kab hai', 'mera passport kahan hai']);
  assert.equal(db.entries.length, 0);
  assert.equal(db.notes[0].text, 'meri gaari ka number LEA 1234 hai');
  assert.match(replies[3].show, /LEA 1234/);
  assert.match(replies[4].show, /5 March/);
  assert.match(replies[5].show, /mujhe nahi pata/);
});

test('"yaad rakho" with a payment still books the payment', () => {
  const { db } = talk(['yaad rakho aaj 700 ka petrol dala']);
  assert.deepEqual(amounts(db, 'expense'), [700]);
  assert.equal(db.notes.length, 0);
});

test('undo removes whatever was added last', () => {
  const { db } = talk(['petrol 500', 'yaad rakho meri gaari ka rang safed hai', 'aakhri wala mita do']);
  assert.equal(db.notes.length, 0);
  assert.equal(db.entries.length, 1);
  talk(['ghalat hai'], db);
  assert.equal(db.entries.length, 0);
});

test('understands Urdu script, including the two meanings of "کیا"', () => {
  const { db, replies } = talk(['آج 500 کا پیٹرول ڈالا', 'پانچ سو روپے کھانے پر خرچ کیے', 'میں نے ۳۰۰ خرچ کیا',
    'آج کتنا خرچہ ہوا', 'یاد رکھو کہ میری گاڑی کا نمبر 1234 ہے', 'گاڑی کا نمبر کیا ہے']);
  assert.deepEqual(amounts(db, 'expense'), [500, 500, 300]);
  assert.match(replies[3].speak.ur, /آج ٹوٹل خرچہ 1300 روپے ہے/);
  assert.match(replies[5].speak.ur, /1234/);
});

test('understands Devanagari', () => {
  const { db, replies } = talk(['आज 500 का पेट्रोल डाला', 'दो सौ की चाय', 'आज कितना खर्चा हुआ']);
  assert.deepEqual(amounts(db, 'expense'), [500, 200]);
  assert.match(replies[2].speak.hi, /आज कुल खर्चा 700 रुपये है/);
});

test('small talk, hanging up and nonsense', () => {
  const { db, replies } = talk(['salam', 'tum kaun ho', 'khuda hafiz', 'asdf qwer', '']);
  assert.match(replies[0].show, /Wa alaikum assalam/);
  assert.match(replies[1].show, /munshi/i);
  assert.equal(replies[2].action, 'hangup');
  assert.match(replies[3].show, /samajh nahi aaya/);
  assert.match(replies[4].show, /samajh nahi aaya/);
  assert.equal(db.entries.length + db.notes.length, 0);
});

test('asks for a missing amount and uses the answer', () => {
  const { db, replies } = talk(['petrol dalwaya', '500']);
  assert.match(replies[0].show, /Kitne rupay/);
  assert.equal(replies[0].changed, false);
  assert.deepEqual(db.entries.map((e) => [e.amount, e.cat]), [[500, 'petrol']]);
});

test('"nahi, 600 the" corrects the entry just booked', () => {
  const { db, last } = talk(['petrol 500', 'nahi 600 the']);
  assert.deepEqual(amounts(db, 'expense'), [600]);
  assert.match(last.show, /500 ki jagah 600/);
  assert.equal(last.changed, true);
});

test('follow-up questions reuse the previous question', () => {
  const { replies } = talk(['petrol 500', 'kal 200 ki chai', 'aaj kitna kharcha hua', 'aur kal?', 'aur petrol par?', 'aur is mahine']);
  assert.match(replies[2].show, /Aaj kul kharcha 500/);
  assert.match(replies[3].show, /Kal kul kharcha 200/);
  assert.match(replies[4].show, /Kal petrol par koi kharcha nahi/);
  assert.match(replies[5].show, /Is mahine petrol par 500/);
});

test('asks which way unclear money went, then learns the words', () => {
  const { db, replies } = talk(['dukaan se 8000 aaj ki sale hui wahan par', 'aamdani', 'dukaan se 9000 ki sale']);
  assert.match(replies[0].show, /kharcha tha ya aamdani/);
  assert.deepEqual(amounts(db, 'income'), [8000, 9000]);
  // an explicit payment still wins over the learned word
  talk(['dukaan ka kiraya 5000 diya'], db);
  assert.deepEqual(amounts(db, 'expense'), [5000]);
});

test('can be taught a category and a word, and applies them to old entries', () => {
  const { db, replies } = talk(['chips 50', 'chips ko khanay mein gino', 'chips 30', 'khanay par kitna laga',
    'tel ka matlab petrol hai', 'tel 300', 'petrol par kitna laga']);
  assert.deepEqual(db.entries.map((e) => e.cat), ['khana', 'khana', 'petrol']);
  assert.match(replies[3].show, /khanay par 80/);
  assert.match(replies[6].show, /petrol par 300/);
});

test('"nahi, yeh aamdani thi" moves the entry and is remembered', () => {
  const { db } = talk(['committee 3000', 'nahi yeh aamdani thi', 'committee 4000']);
  assert.deepEqual(amounts(db, 'income'), [3000, 4000]);
  assert.deepEqual(amounts(db, 'expense'), []);
});

test('remembers what it is told in passing, without announcing it', () => {
  const { db, replies } = talk(['aaj Ali se mulaqat hui thi', 'kal mujhe Lahore jana hai', 'meri gaari ka number LEA 1234 hai',
    'yaad rakho dawai raat ko khani hai', 'Ali se kab mulaqat hui thi', 'Lahore kab jana hai', 'gaari ka number kya hai']);
  replies.forEach((r) => assert.doesNotMatch(r.show, /yaad rakh|baatein yaad/i));     // never "I have remembered"
  assert.equal(db.notes.length, 4);
  assert.notEqual(replies[0].show, replies[1].show);                                  // and it does not repeat itself
  assert.match(replies[4].show, /aaj Ali se mulaqat hui thi/i);
  assert.match(replies[5].show, /Lahore jana hai/);
  assert.match(replies[6].show, /LEA 1234/);
});

test('chit-chat and one-word remarks are not kept; the same thing is not kept twice', () => {
  const { db } = talk(['aaj bohat garmi hai', 'acha chalo chhoro', 'main ghar aa gaya hoon', 'aaj Ali se mulaqat hui thi', 'aaj Ali se mulaqat hui thi']);
  assert.deepEqual(db.notes.map((n) => n.text), ['aaj Ali se mulaqat hui thi']);
});

test('"mita do" removes what was asked for, not what was overheard', () => {
  const { db } = talk(['petrol 500', 'aaj Ali se mulaqat hui thi', 'mita do']);
  assert.equal(db.entries.length, 0);
  assert.equal(db.notes.length, 1);
});

test('"tumhe kya yaad hai" tells the last few things without counting them', () => {
  const { last } = talk(['yaad rakho dawai raat ko khani hai', 'yaad rakho kal bijli ka bill bharna hai', 'tumhe kya yaad hai']);
  assert.match(last.show, /bijli ka bill bharna hai.*dawai raat ko khani hai/);
  assert.doesNotMatch(last.show, /\d/);
});

test('knows the user by name and reads notes back as "aap ki"', () => {
  const { db, replies } = talk(['mera naam Abrar hai', 'mera naam kya hai', 'meri gaari ka number LEA 1234 hai', 'gaari ka number kya hai']);
  assert.match(replies[1].show, /Aap ka naam Abrar hai/);
  assert.match(replies[3].show, /^Aap ki gaari ka number LEA 1234 hai/);
  assert.match(M.greet(db).ro, /Assalam o alaikum Abrar/);
});

test('near-miss spellings of safe words are understood; names are left alone', () => {
  const { db } = talk(['petroll 100', 'dawat 5000', 'Kamal ko 500 udhar diye', 'Hafiz sahab ko 200 diye']);
  assert.equal(db.entries[0].cat, 'petrol');
  assert.equal(db.entries[1].cat, null);                    // "dawat" is not "dawai"
  assert.equal(db.entries[2].person, 'Kamal');
  assert.equal(db.entries[3].item, 'Hafiz');
});

test('older save files and odd words do not break it', () => {
  const old = { seq: 1, entries: [{ id: 1, type: 'expense', amount: 500, cat: 'petrol', item: 'petrol', date: '2026-11-12', ts: 1 }], notes: [] };
  const { last, db } = talk(['aaj kitna kharcha hua', 'constructor toString 5'], old);
  assert.equal(db.entries.length, 2);
  assert.match(last.show, /constructor toString ke 5/);
  assert.equal(M.isDb(old), true);
  assert.equal(M.isDb({ entries: 1 }), false);
});

test('backup commands are passed to the app', () => {
  const { replies } = talk(['backup bana do', 'backup wapas lao']);
  assert.deepEqual(replies.map((r) => r.action), ['backup', 'restore']);
});

test('repairs a misheard word from the other guesses and learns it the second time', () => {
  const db = M.emptyDb();
  talk(['petrol 500'], db);
  const first = M.handleBest(['pitrool par kitna laga', 'petrol par kitna laga'], db, NOW);
  assert.match(first.show, /petrol par 500/);
  assert.equal(db.learned.alias.pitrool, undefined);          // once is not enough
  M.handleBest(['aaj pitrool par kitna laga', 'aaj petrol par kitna laga'], db, NOW);
  assert.equal(db.learned.alias.pitrool.cat, 'petrol');
  // now the recogniser's mistake is understood with no second guess to help
  assert.match(M.handle('pitrool par kitna laga', db, NOW).show, /petrol par 500/);
});

test('a word the user really uses is not "repaired" into another', () => {
  const db = M.emptyDb();
  talk(['chawla 300'], db);                                   // a shop the user buys from
  const r = M.handleBest(['chawla par kitna laga', 'chawal par kitna laga'], db, NOW);
  assert.match(r.show, /chawla par 300/);
});

test('falls back to another guess when the top one makes no sense', () => {
  const db = M.emptyDb();
  M.handleBest(['zzz qqq', 'chai 50'], db, NOW);
  assert.deepEqual(amounts(db, 'expense'), [50]);
});

test('a guess can never be learned as an amount, a delete or a hang-up', () => {
  const db = M.emptyDb();
  talk(['petrol 500'], db);
  for (let i = 0; i < 3; i++) M.handleBest(['zorp do', 'mita do'], db, NOW);
  assert.equal(db.learned.alias.zorp, undefined);
  for (let i = 0; i < 3; i++) M.handleBest(['petrol flarn', 'petrol pachas'], db, NOW);
  assert.equal(db.learned.alias.flarn, undefined);
});

test('the top guess is kept when it already makes sense', () => {
  const db = M.emptyDb();
  const r = M.handleBest(['chai 50', 'chai 15'], db, NOW);
  assert.deepEqual(amounts(db, 'expense'), [50]);
  assert.equal(r.changed, true);
});

test('does sums by voice without booking them', () => {
  const { db, replies } = talk(['25 guna 4 kitna hota hai', '500 ka 10 percent kitna hai', '1000 mein se 250 nikalo',
    'teen guna do', '15 + 30', '100 - 45', '12 taqseem 0', 'dhai sau jama dedh sau', 'پچیس ضرب چار']);
  assert.deepEqual(replies.map((r) => r.show), ['Jawab 100 hai.', 'Jawab 50 hai.', 'Jawab 750 hai.', 'Jawab 6 hai.',
    'Jawab 45 hai.', 'Jawab 55 hai.', 'Sifar se taqseem nahi ho sakta.', 'Jawab 400 hai.', 'Jawab 100 hai.']);
  assert.equal(db.entries.length, 0);
});

test('small talk, moods, jokes and facts', () => {
  const { db, replies } = talk(['subah bakhair', 'tumhara naam kya hai', 'main bohat thak gaya hoon', 'lateefa sunao',
    'Pakistan ka darul hukumat kya hai', 'hafte mein kitne din hote hain', 'aaj kaun sa din hai', 'kal kaun sa din hoga',
    'aaj mausam kaisa hai', 'تمہارا نام کیا ہے']);
  assert.match(replies[0].show, /Subah bakhair/);
  assert.match(replies[1].show, /Munshi/);
  assert.match(replies[2].show, /aaram/);
  assert.ok(replies[3].show.length > 40);
  assert.match(replies[4].show, /Islamabad/);
  assert.match(replies[5].show, /7 din/);
  assert.equal(replies[6].show, 'Aaj Jumerat hai.');
  assert.equal(replies[7].show, 'Kal Juma ho ga.');
  assert.match(replies[8].show, /internet nahi/);
  assert.match(replies[9].speak.ur, /منشی/);
  assert.equal(db.entries.length + db.notes.length, 0);
});

test('chit-chat never steals hisaab or notes about other people', () => {
  const { db, replies } = talk(['bimar ammi ki dawai 300 ki li', 'mera dost Ali bimar hai aur hospital mein hai',
    'is hafte kitna kharcha hua', 'meri umar 25 saal hai', 'meri umar kitni hai']);
  assert.deepEqual(amounts(db, 'expense'), [300]);
  assert.equal(db.notes.length, 2);                              // both kept quietly
  assert.doesNotMatch(replies[1].show, /shifa|yaad rakh/);      // not a get-well wish for the speaker, and no announcement
  assert.match(replies[2].show, /Is hafte kul kharcha 300/);
  assert.match(replies[4].show, /umar 25 saal/);
});

test('riddles wait for an answer', () => {
  const a = talk(['paheli poocho']);
  const i = a.db.ctx.pending.i;
  const answers = ['tauliya', 'kanghi', 'sitare', 'kal'];
  assert.match(talk([answers[i]], a.db).last.show, /Bilkul sahi/);
  const b = talk(['paheli poocho', 'pata nahi']);
  assert.match(b.last.show, /Jawab hai/);
});

test('learns an answer only when told it is one', () => {
  const { db, replies } = talk(['mera passport kahan hai', 'acha chalo chhoro', 'mera passport kahan hai',
    'jawab hai almari ke upar wale khane mein', 'passport kahan rakha hai', 'sab se lamba darya kaun sa hai', 'shabash']);
  assert.match(replies[0].show, /nahi pata/);
  assert.equal(replies[1].changed, false);                        // the next thing said is not taken as the answer
  assert.match(replies[3].show, /shukriya/);
  assert.match(replies[4].show, /Almari ke upar/);
  assert.match(replies[6].show, /Shukriya! Aap ki meharbani/);
  assert.equal(db.notes.length, 1);
});

test('replies can be scripted by the user, but never over hisaab', () => {
  const { db, replies } = talk(['jab main kahun chai to kehna abhi laya sahab', 'chai', 'chai 50']);
  assert.equal(replies[1].show, 'Abhi laya sahab');
  assert.deepEqual(amounts(db, 'expense'), [50]);
});

test('keeps a record of each exchange, and marks the ones called wrong', () => {
  const db = M.emptyDb();
  M.handleBest(['petrol 500'], db, NOW);
  M.handleBest(['chand par sab se pehle kaun gaya tha'], db, NOW);
  M.handleBest(['zzz qqq', 'aaj kitna kharcha hua'], db, NOW);
  const marked = M.handleBest(['yeh jawab ghalat tha'], db, NOW);
  assert.equal(db.log.length, 3);                                   // the complaint is not itself an exchange
  assert.deepEqual(db.log.map((e) => e.heard), ['petrol 500', 'chand par sab se pehle kaun gaya tha', 'aaj kitna kharcha hua']);
  assert.match(db.log[0].said, /petrol ke 500/);
  assert.equal(db.log[1].result, 'no real answer');
  assert.deepEqual(db.log[2].guesses, ['zzz qqq', 'aaj kitna kharcha hua']);
  assert.equal(db.log[2].picked, 1);
  assert.equal(db.log[2].bad, true);
  assert.match(marked.show, /ghalti ho gayi/);
  assert.equal(db.entries.length, 1);                               // and nothing was deleted
  assert.match(M.lasting(db), /"log"/);                             // it is part of the saved file
});

test('the record does not grow without limit', () => {
  const db = M.emptyDb();
  for (let i = 0; i < 450; i++) M.handleBest(['salam'], db, NOW);
  assert.equal(db.log.length, 400);
});

test('"jawab ghalat tha" right after an unanswered question is a complaint, not the answer', () => {
  const db = M.emptyDb();
  M.handleBest(['دنیا کا سب سے بڑا ملک کون سا ہے'], db, NOW);
  const r = M.handleBest(['یہ جواب غلط تھا'], db, NOW);
  assert.match(r.show, /ghalti ho gayi/);
  assert.equal(db.notes.length, 0);
  assert.equal(db.log.length, 1);
  assert.equal(db.log[0].bad, true);
});

const later = (sec) => new Date(NOW.getTime() + sec * 1000);

test('a question left unanswered before is asked back, and its answer learned', () => {
  const db = M.emptyDb();
  const first = M.handleBest(['chand par sab se pehle kaun gaya tha'], db, later(0));
  assert.match(first.show, /nahi pata/);
  M.handleBest(['petrol 500'], db, later(60));
  const second = M.handleBest(['chand par sab se pehle kaun gaya tha'], db, later(600));
  assert.match(second.show, /pehle bhi pooch chuke hain/);
  assert.match(M.handleBest(['Russia'], db, later(605)).show, /shukriya/);
  assert.match(M.handleBest(['chand par sab se pehle kaun gaya tha'], db, later(900)).show, /Russia/);
});

test('saying "pata nahi" stops it asking about that question again', () => {
  const db = M.emptyDb();
  const q = 'chand par pehla insaan kaun gaya tha';
  M.handleBest([q], db, later(0));
  M.handleBest([q], db, later(300));
  M.handleBest(['pata nahi'], db, later(305));
  assert.doesNotMatch(M.handleBest([q], db, later(900)).show, /pehle bhi/);
  assert.equal(db.notes.filter((n) => !n.quiet).length, 0);
});

test('a remark made for the third time is given a reply by the user', () => {
  const db = M.emptyDb();
  const remark = 'chalo bhai dukaan kholne ka waqt ho gaya';
  M.handleBest([remark], db, later(0));
  M.handleBest([remark], db, later(100));
  assert.match(M.handleBest([remark], db, later(200)).show, /main kya kaha karoon/);
  assert.match(M.handleBest(['bismillah karein'], db, later(205)).show, /aainda yahi kahoon gi/);
  assert.match(M.handleBest([remark], db, later(900)).show, /bismillah karein/i);
});

test('a sentence repeated with one word changed teaches it that word', () => {
  const db = M.emptyDb();
  talk(['petrol 500'], db);
  for (let i = 0; i < 2; i++) {                                        // misheard, then said again clearly, twice
    assert.equal(M.handleBest(['pitrool dalwaya'], db, later(i * 100)).weak, true);
    assert.match(M.handleBest(['petrol dalwaya'], db, later(i * 100 + 8)).show, /Kitne rupay/);
  }
  assert.equal(db.learned.alias.pitrool.cat, 'petrol');
  assert.match(M.handle('pitrool par kitna laga', db, NOW).show, /petrol par 500/);
});

// The sentences below are taken from a real conversation record, in the words the phone heard.
test('record: greeting with "kya haal", and "kuch nahi likhna"', () => {
  const { db, replies } = talk(['وعلیکم السلام کیا حال ہیں', 'کچھ نہیں لکھنا کیا کر رہی ہو', 'کیا کر رہی ہو', 'کچھ نہیں لکھنا']);
  assert.match(replies[0].show, /Wa alaikum assalam! Allah ka shukar hai, main theek hoon/);
  assert.match(replies[1].show, /baatein kar rahi hoon/);
  assert.match(replies[2].show, /baatein kar rahi hoon/);
  assert.match(replies[3].show, /koi baat nahi/);
  assert.equal(db.notes.length, 0);                                   // "likhna" was being stored as a note
});

test('record: "kidhar ja rahi ho", sums and complaints', () => {
  const { db, replies } = talk(['کدھر جا رہی ہو تم', 'میں نے پوچھا کدھر جا رہی ہو', 'دو پلس دو کتنا ہوتا ہے',
    'احمد کی اینٹری ایڈ نہیں کی تم نے', 'یہاں ساری باتیں غلط بتا رہی ہو']);
  assert.match(replies[0].show, /Kahin nahi ja rahi/);
  assert.match(replies[1].show, /Kahin nahi ja rahi/);
  assert.equal(replies[2].show, 'Jawab 4 hai.');
  assert.match(replies[3].show, /ke naam 500 rupay likh do/);
  assert.match(replies[4].show, /ghalti ho gayi/);
  assert.equal(db.entries.length + db.notes.length, 0);
});

test('record: money written against a name ("ke naam")', () => {
  const { db, replies } = talk(['کے نام لکھ دو', 'احمد کے نام 500 روپے لکھ دو', 'احمد کا نام کتنا لکھا', 'ایمن کے نام کتنا لکھا ہے',
    'کتنے پیسے ہیں ان کے نام لکھے ہیں تم نے', 'احمد کے نام کتنا ہے', 'اور احمد کے نام کتنا ہے', 'احمد کے نام 200 اور لکھ دو',
    'احمد کا کتنا ادھار ہے', 'ایمن کے نام لکھ دو', '300']);
  assert.match(replies[0].show, /Kis ke naam likhoon/);
  assert.match(replies[1].speak.ur, /احمد کے نام 500 روپے لکھ دیے/);
  assert.match(replies[2].speak.ur, /احمد کے نام 500 روپے لکھے ہیں/);
  assert.match(replies[3].speak.ur, /ایمن کے نام کچھ نہیں لکھا/);
  assert.match(replies[4].speak.ur, /ایمن کے نام کچھ نہیں لکھا/);              // "un ke naam" is whoever was just mentioned
  assert.match(replies[5].speak.ur, /احمد کے نام 500 روپے لکھے ہیں/);
  assert.match(replies[6].speak.ur, /احمد کے نام 500 روپے لکھے ہیں/);
  assert.match(replies[7].speak.ur, /اب ٹوٹل 700 روپے ہیں/);
  assert.match(replies[8].speak.ur, /احمد نے آپ کو 700 روپے دینے ہیں/);          // the udhar wording reads the same account
  assert.match(replies[9].show, /Kitne rupay/);
  assert.match(replies[10].speak.ur, /ایمن کے نام 300 روپے لکھ دیے/);
  assert.equal(db.notes.length, 0);
  assert.deepEqual(db.entries.map((e) => [e.type, e.amount]), [['loan', 500], ['loan', 200], ['loan', 300]]);
});

test('"ke naam" does not swallow sentences about what something is called', () => {
  const { db, replies } = talk(['mera naam Abrar hai', 'tumhara naam kya hai', 'Roman: Ahmed ke naam 500 likh do', 'Ahmed ke naam kitna likha hai']);
  assert.match(replies[1].show, /Munshi/);
  assert.match(replies[3].show, /Ahmed ke naam 500 rupay likhe hain/);
  assert.equal(db.profile.name, 'Abrar');
});

// Second conversation record: stock, people asked about without "ke naam", the day question, complaints.
test('record 2: bricks go into stock, not into rupees', () => {
  const { db, replies } = talk(['10 ہزار اشرف کے نام لکھ دو',
    'اچھا اشرف نے نا 25 ہزار کچی اینٹ ڈالی ہے وہ سٹاک میں لکھ دو اس کے نام پر',
    '25 ہزار روپے اشرف کے نام اس کی اینٹ بھی لکھنی ہے کہ اس نے 25 ہزار اینٹ ڈالی ہے',
    'اشرف کے نام کتنا لکھا ہے', 'سٹاک میں کتنی اینٹ ہے', 'اشرف نے کتنی اینٹ ڈالی ہے', 'اشرف کے نام سے 25 ہزار روپے کم کر دو']);
  assert.match(replies[1].speak.ur, /اشرف کے نام 25000 کچی اینٹ اسٹاک میں لکھ دی/);
  assert.match(replies[2].speak.ur, /اشرف کے نام 25000 روپے لکھ دیے، اب ٹوٹل 35000 روپے ہیں/);
  assert.match(replies[2].speak.ur, /25000 اینٹ اسٹاک میں لکھ دی/);
  assert.match(replies[3].speak.ur, /اشرف کے نام 35000 روپے لکھے ہیں/);
  assert.match(replies[4].speak.ur, /اسٹاک میں 25000 کچی اینٹ ہے/);
  assert.match(replies[5].speak.ur, /اشرف کے نام 25000 کچی اینٹ لکھی ہے/);
  assert.match(replies[6].speak.ur, /کم کر دیے۔ اب ٹوٹل 10000 روپے ہیں/);
  assert.deepEqual(db.entries.map((e) => [e.type, e.amount]), [['loan', 10000], ['stock', 25000], ['loan', 25000], ['stock', 25000], ['loan', 25000]]);
  assert.equal(amounts(db, 'expense').length, 0);
});

test('record 2: someone with an account is recognised without "ke naam", even one letter off', () => {
  const { replies } = talk(['ابھی نہ تم احمد کے نام 15 ہزار روپیہ لکھ دو', '10 ہزار اشرف کے نام لکھ دو', 'ابھی ٹوٹل کتنا ہو گیا',
    'اشرف کو کتنا کتنا لکھا ہے اشرف کا', 'اشرف کا کتنا لکھا ہے', 'اشرف کو کتنا دیا تھا', 'اور احمد کے نام کتنے ہیں',
    'شرف کے نام کتنا لکھا ہے', 'اشرف کے نام', 'اشرف کے نام کی طرح لکھا ہے', 'اور اشرف کو کتنا دیا تھا', 'اج کتنا لکھا ہے', 'لکھ دو']);
  assert.match(replies[2].speak.ur, /لوگوں کے نام ٹوٹل 25000 روپے لکھے ہیں/);
  [3, 4, 5, 7, 8, 9, 10].forEach((i) => assert.match(replies[i].speak.ur, /اشرف کے نام 10000 روپے لکھے ہیں/, 'line ' + i));
  assert.match(replies[6].speak.ur, /احمد کے نام 15000 روپے لکھے ہیں/);
  assert.match(replies[11].speak.ur, /لوگوں کے نام ٹوٹل 25000/);
  assert.match(replies[12].show, /Kya likhoon/);
});

test('record 2: the day, the time, complaints and small talk', () => {
  const { db, replies } = talk(['کا دن ہے', 'اج کون سا دن ہے', 'کل ہفتہ ہوگا اج کیا دن ہے', 'یار کل کیا ڈے ہے مطلب کل کون سا دن ہوگا',
    'میں ٹھیک ہوں کیا ہو رہا ہے', 'کچھ نہیں میں لیٹا ہوا ہوں', 'اچھا ایک کام کرو', 'تمہیں بالکل سمجھ ہی نہیں اتی یار',
    'کچھ نہیں لکھنا یار تمہیں سمجھ ہی نہیں اتی میری بات کی', 'تمہیں اینٹ کی سمجھ نہیں ارہی']);
  assert.equal(db.notes.length, 0);                                   // the fragment is not kept
  assert.equal(replies[1].show, 'Aaj Jumerat hai.');
  assert.equal(replies[2].show, 'Aaj Jumerat hai.');                  // not "a week has 7 days"
  assert.equal(replies[3].show, 'Kal Juma ho ga.');
  assert.match(replies[4].show, /baatein ho rahi hain/);
  assert.match(replies[5].show, /aaram kijiye/);
  assert.match(replies[6].show, /kya karna hai/);
  [7, 8, 9].forEach((i) => assert.match(replies[i].show, /abhi seekh rahi hoon/, 'line ' + i));
});

test('totals are spoken as "ٹوٹل", which the voice cannot misread as "kal"', () => {
  const { replies } = talk(['petrol 500', 'aaj kitna kharcha hua', 'is mahine kitna kharcha hua']);
  replies.forEach((r) => assert.doesNotMatch(r.speak.ur, /کل خرچہ/));
  assert.match(replies[1].speak.ur, /آج ٹوٹل خرچہ 500 روپے ہے/);
});

test('one person\'s account can be wiped, and stock amounts corrected', () => {
  const { db, replies } = talk(['Ahmed ke naam 500 likh do', 'Ashraf ke naam 300 likh do', 'Ahmed ka hisaab saaf kar do', 'Ashraf ne 200 bori cement dali hai', 'nahi 250']);
  assert.match(replies[2].show, /Ahmed ke naam jo kuch likha tha, sab mita diya/);
  assert.deepEqual(db.entries.map((e) => [e.type, e.person, e.qty || e.amount]), [['loan', 'Ashraf', 300], ['stock', 'Ashraf', 250]]);
});

// ---------------------------------------------------------------- teacher ---
// A stand-in for Groq: given the app's draft, it returns a fixed verdict.
const at = (sec) => new Date(NOW.getTime() + sec * 1000);
function lesson(db, heard, verdict, sec) {
  const plan = M.prepare([heard], db, at(sec));
  return { plan, reply: plan.ask ? M.answer([heard], JSON.stringify(verdict), db, at(sec)) : M.handleBest([heard], db, at(sec)) };
}

test('teacher: a wrong draft is replaced, and the lesson works with other names and numbers', () => {
  const db = M.emptyDb();
  const fix = { verdict: 'command', command: 'Ashraf ne 25000 kachi eent dali hai stock mein likh do', general: true };
  const first = lesson(db, 'Ashraf ki taraf se 25000 kachi eent aayi hai', fix, 0);
  assert.equal(first.plan.ask, true);
  assert.match(first.plan.user, /Draft reply: /);
  assert.match(first.reply.show, /25,000 kachi eent stock mein likh di/);
  // a different supplier and amount, same way of speaking: understood with no teacher at all
  const later = M.handleBest(['Bilal ki taraf se 900 kachi eent aayi hai'], db, at(60));
  assert.match(later.show, /Bilal ke naam 900 kachi eent stock mein likh di/);
  assert.deepEqual(db.entries.map((e) => [e.type, e.person, e.qty]), [['stock', 'Ashraf', 25000], ['stock', 'Bilal', 900]]);
});

test('teacher: agreeing three times makes a shape trusted, and then it is not asked', () => {
  const db = M.emptyDb();
  for (let i = 0; i < 3; i++) assert.equal(lesson(db, 'aaj kitna kharcha hua', { verdict: 'ok', general: true }, i * 10).plan.ask, true);
  assert.equal(M.prepare(['aaj kitna kharcha hua'], db, at(100)).ask, false);
  assert.equal(M.teacherStats(db).trusted, 1);
});

test('teacher: a general reply is kept when timeless, and not when it is not', () => {
  const db = M.emptyDb();
  const r1 = lesson(db, 'chand par sab se pehle kaun gaya tha', { verdict: 'reply', reply: { ro: 'Roos sab se bara mulk hai.', ur: 'روس سب سے بڑا ملک ہے۔', hi: 'रूस सबसे बड़ा मुल्क है।' }, keep: true, general: true }, 0);
  assert.equal(r1.reply.show, 'Roos sab se bara mulk hai.');
  assert.equal(M.handleBest(['chand par sab se pehle kaun gaya tha'], db, at(50)).show, 'Roos sab se bara mulk hai.');
  lesson(db, 'aaj mausam kaisa rahe ga', { verdict: 'reply', reply: { ro: 'Aaj dhoop hai.' }, keep: false, general: true }, 100);
  assert.doesNotMatch(M.handleBest(['aaj mausam kaisa rahe ga'], db, at(150)).show, /dhoop/);
});

test('teacher: it can never make the app delete, and an unusable lesson keeps the app\'s own answer', () => {
  const db = M.emptyDb();
  M.handleBest(['petrol 500'], db, at(0));
  lesson(db, 'sab kuch naye sire se karo', { verdict: 'command', command: 'mita do', general: true }, 10);
  assert.equal(db.entries.length, 1);
  const r = lesson(db, 'chai 50', { verdict: 'command', command: 'blah blah nonsense', general: true }, 20);
  assert.match(r.reply.show, /khanay ke 50/);
  assert.equal(db.log[db.log.length - 1].teacher, 'unusable');
});

test('teacher: "jawab ghalat tha" withdraws the lesson and the teacher is asked again', () => {
  const db = M.emptyDb();
  lesson(db, 'Ashraf ki taraf se 500 kachi eent aayi hai', { verdict: 'command', command: 'Ashraf ne 500 kachi eent dali hai stock mein likh do', general: true }, 0);
  M.handleBest(['Bilal ki taraf se 20 kachi eent aayi hai'], db, at(10));
  M.handleBest(['yeh jawab ghalat tha'], db, at(15));
  assert.equal(M.prepare(['Bilal ki taraf se 30 kachi eent aayi hai'], db, at(20)).ask, true);
  assert.equal(Object.values(db.learned.shapes).filter((e) => e.tpl).length, 0);
});

test('teacher: without a reachable teacher everything works as before', () => {
  const db = M.emptyDb();
  assert.match(M.answer(['petrol 500'], null, db, at(0)).show, /petrol ke 500/);
  assert.match(M.answer(['chai 50'], 'not json at all', db, at(5)).show, /khanay ke 50/);
});

test('shared lessons: only general ones leave, names and numbers stay open, and they merge back in', () => {
  const a = M.emptyDb();
  lesson(a, 'Ashraf ki taraf se 500 kachi eent aayi hai', { verdict: 'command', command: 'Ashraf ne 500 kachi eent dali hai stock mein likh do', general: true }, 0);
  lesson(a, 'meri beti Ayesha ki school fees kab jama karni hai', { verdict: 'reply', reply: { ro: 'Mujhe nahi pata.' }, keep: true, general: false }, 10);
  const file = M.mergeShared(a, null);
  assert.equal(M.mergeShared(a, file), '');                                 // nothing new: nothing to upload
  assert.doesNotMatch(file, /Ashraf|500|Ayesha/);
  const b = M.emptyDb();
  assert.equal(M.absorbShared(b, file), 1);
  assert.match(M.handleBest(['Kamran ki taraf se 70 kachi eent aayi hai'], b, at(0)).show, /Kamran ke naam 70 kachi eent/);
  assert.equal(M.absorbShared(b, '{"shapes":{"x":{"p":["x"],"tpl":["mita","do"]}}}'), 0);   // a shared lesson cannot delete either
});

test('a voice question says how much the teacher is still needed', () => {
  const db = M.emptyDb();
  lesson(db, 'aaj kitna kharcha hua', { verdict: 'ok', general: true }, 0);
  assert.match(M.handleBest(['ustad ki kitni zaroorat pari'], db, at(10)).show, /1 dafa ustad se poochha/);
});

test('expenses on an earlier date, and questions about that date', () => {
  // NOW is Thursday 12 November 2026
  const { db, replies } = talk(['5 tareekh ko 500 ka petrol dala', '3 din pehle 200 ki chai pi', '10 October ko 1500 ka bijli ka bill diya',
    'peer ko 300 ka rashan liya', 'November ki 2 tareekh ko 700 kiraya diya', '5 tareekh ko kitna kharcha hua', 'is mahine kitna kharcha hua',
    '۵ نومبر کو ۴۰۰ کا پیٹرول ڈالا', '20 tareekh ko 100 ki chai']);
  assert.deepEqual(db.entries.map((e) => [e.amount, e.date]), [[500, '2026-11-05'], [200, '2026-11-09'], [1500, '2026-10-10'],
    [300, '2026-11-09'], [700, '2026-11-02'], [400, '2026-11-05'], [100, '2026-10-20']]);
  assert.match(replies[0].show, /petrol ke 500 rupay likh liye\. 5 November ka kul kharcha 500/);
  assert.match(replies[5].show, /5 November ko kul kharcha 500/);
  assert.match(replies[6].show, /Is mahine kul kharcha 1,700/);
  assert.match(replies[7].speak.ur, /پیٹرول کے 400 روپے/);
  assert.match(replies[8].show, /20 October ka kul kharcha 100/);                  // the 20th has not come yet this month
});

test('a date does not swallow ordinary numbers', () => {
  const { db } = talk(['500 ka petrol dala', '25 guna 4', 'Ahmed ke naam 5000 likh do']);
  assert.deepEqual(db.entries.map((e) => [e.amount, e.date]), [[500, '2026-11-12'], [5000, '2026-11-12']]);
});

// ------------------------------------------------------------ phrase bank ---
test('bank: other wordings of hisaab questions reach the right answer', () => {
  const { replies } = talk(['petrol 500', 'kal 200 ki chai', 'aaj kitne paise ud gaye', 'آج کتنے پیسے اڑ گئے',
    'paise kahan zyada ja rahe hain', 'is mahine ka kharcha batao', 'stock mein kitna maal para hai', 'aaj konsa din hai']);
  assert.match(replies[2].show, /Aaj kul kharcha 500/);
  assert.match(replies[3].speak.ur, /آج ٹوٹل خرچہ 500/);
  assert.match(replies[4].show, /sab se zyada kharcha petrol par/);
  assert.match(replies[5].show, /Is mahine kul kharcha 700/);
  assert.match(replies[6].show, /Stock mein/);
  assert.equal(replies[7].show, 'Aaj Jumerat hai.');
});

test('bank: gup-shup gets real replies, and they take turns', () => {
  const { db, replies } = talk(['aaj dukaan par rush bohat tha', 'mehngai bohat barh gayi hai', 'paise kaise bachaen', 'koi kahani sunao',
    'ek mann mein kitne kilo hote hain', 'kanal mein kitne marle hote hain', 'lateefa sunao', 'lateefa sunao', 'mujhe zukaam hai',
    'میں بہت اداس ہوں', 'تم کیا کیا کر سکتی ہو']);
  assert.match(replies[0].show, /Mashallah/);
  assert.match(replies[1].show, /mehngai/);
  assert.match(replies[2].show, /alag rakh/);
  assert.match(replies[3].show, /kawwa|kachhua/);
  assert.match(replies[4].show, /40 kilo/);
  assert.match(replies[5].show, /20 marle/);
  assert.notEqual(replies[6].show, replies[7].show);
  assert.match(replies[8].show, /shifa/);
  assert.match(replies[9].speak.ur, /افسوس|مشکل/);
  assert.match(replies[10].show, /stock likhti hoon/);
  assert.equal(db.entries.length + db.notes.length, 0);
});

test('bank: it never takes over what the rules already understand', () => {
  const { db, replies } = talk(['500 ka petrol dala', 'Ahmed ke naam 1000 likh do', 'yaad rakho kal bijli ka bill bharna hai', 'aaj kitna kharcha hua', 'khuda hafiz']);
  assert.deepEqual(db.entries.map((e) => [e.type, e.amount]), [['expense', 500], ['loan', 1000]]);
  assert.equal(db.notes.length, 1);
  assert.match(replies[3].show, /Aaj kul kharcha 500/);
  assert.equal(replies[4].action, 'hangup');
});

test('bank: unrelated sentences do not match anything', () => {
  ['qwerty asdf', 'kal Ali se mulaqat hui thi bazaar mein', 'mera bhai Karachi gaya hai'].forEach((s) => assert.equal(M.bankMatch(s), null, s));
});
