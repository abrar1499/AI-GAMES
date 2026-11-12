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
