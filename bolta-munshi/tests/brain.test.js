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
  assert.match(replies[5].show, /kuch yaad nahi/);
});

test('"yaad rakho" with a payment still books the payment', () => {
  const { db } = talk(['yaad rakho aaj 700 ka petrol dala']);
  assert.deepEqual(amounts(db, 'expense'), [700]);
  assert.equal(db.notes.length, 0);
});

test('undo removes whatever was added last', () => {
  const { db } = talk(['petrol 500', 'meri gaari ka rang safed hai', 'aakhri wala mita do']);
  assert.equal(db.notes.length, 0);
  assert.equal(db.entries.length, 1);
  talk(['ghalat hai'], db);
  assert.equal(db.entries.length, 0);
});

test('understands Urdu script, including the two meanings of "کیا"', () => {
  const { db, replies } = talk(['آج 500 کا پیٹرول ڈالا', 'پانچ سو روپے کھانے پر خرچ کیے', 'میں نے ۳۰۰ خرچ کیا',
    'آج کتنا خرچہ ہوا', 'یاد رکھو کہ میری گاڑی کا نمبر 1234 ہے', 'گاڑی کا نمبر کیا ہے']);
  assert.deepEqual(amounts(db, 'expense'), [500, 500, 300]);
  assert.match(replies[3].speak.ur, /آج کل خرچہ 1300 روپے ہے/);
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
  assert.match(replies[1].show, /munshi/);
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

test('offers to remember what it could not understand', () => {
  const { db, replies } = talk(['kal shaam chalein gay bazaar', 'haan', 'bazaar kab jana tha']);
  assert.match(replies[0].show, /yaad rakh loon/);
  assert.equal(db.notes.length, 1);
  assert.match(replies[2].show, /bazaar/);
  const second = talk(['kal shaam chalein gay bazaar', 'nahi']);
  assert.equal(second.db.notes.length, 0);
});

test('knows the user by name and reads notes back as "aap ki"', () => {
  const { db, replies } = talk(['mera naam Abrar hai', 'mera naam kya hai', 'meri gaari ka number LEA 1234 hai', 'gaari ka number kya hai']);
  assert.match(replies[1].show, /Aap ka naam Abrar hai/);
  assert.match(replies[3].show, /^Aap ki gaari ka number LEA 1234 hai/);
  assert.match(M.greet(db).ro, /Assalam o alaikum Abrar/);
});

test('near-miss spellings of safe words are understood; names are left alone', () => {
  const { db } = talk(['pertol 100', 'Kamal ko 500 udhar diye', 'Hafiz sahab ko 200 diye']);
  assert.equal(db.entries[0].cat, 'petrol');
  assert.equal(db.entries[1].person, 'Kamal');
  assert.equal(db.entries[2].item, 'Hafiz');
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
