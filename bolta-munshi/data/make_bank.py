#!/usr/bin/env python3
"""
Builds the phrase bank (bank.json and bank.js) for Bolta Munshi.

Each intent lists ways people say something, in Roman Urdu and Urdu script,
and either replies to give (gup-shup) or a plain command the rules already
understand (hisaab). Patterns are expanded: "(a|b|c)" picks one of the
choices and "[x]" may be left out, so a few lines give many phrasings.

    python3 data/make_bank.py

To add knowledge, add an intent below and run it again.
"""
import itertools, json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))

def expand(pattern):
    """'(a|b) [c] d' -> ['a c d', 'a d', 'b c d', 'b d']"""
    parts = re.split(r'(\([^()]*\)|\[[^\[\]]*\])', pattern)
    choices = []
    for p in parts:
        if p.startswith('('):
            choices.append(p[1:-1].split('|'))
        elif p.startswith('['):
            choices.append([p[1:-1], ''])
        else:
            choices.append([p])
    out = set()
    for combo in itertools.product(*choices):
        out.add(re.sub(r'\s+', ' ', ''.join(combo)).strip())
    return sorted(x for x in out if x)

# ---------------------------------------------------------------- hisaab ---
# Questions about the accounts, in many wordings, each mapped to one command
# the rules already answer. They are used only when the rules themselves did
# not understand the sentence.
HISAAB = [
    ('aaj kitna kharcha hua', [
        '(aaj|aj) (ka|ke) (kharcha|kharche|kharchay|hisaab|hisab) (batao|bataen|bata do|sunao|kitna hai|kya hai|kitne hain)',
        '(aaj|aj) (kitne|kitna) (paise|rupay|pese) (lage|laga|gaye|kharch hue|kharch kiye|udh gaye|uth gaye)',
        '(aaj|aj) (kya kya|kis kis cheez par) (kharch kiya|kharcha kiya|liya|kharida)',
        '(aaj|aj) (main ne|maine|hum ne) (kitna|kitne) (kharch kiya|kharcha kiya|paise kharch kiye|udaye)',
        '(آج|اج) (کا|کے) (خرچہ|خرچے|حساب) (بتاؤ|بتاو|بتا دو|سناؤ|کتنا ہے|کیا ہے)',
        '(آج|اج) (کتنے|کتنا) (پیسے|روپے) (لگے|لگا|گئے|خرچ ہوئے|خرچ کیے|اڑ گئے)',
        '(آج|اج) (کیا کیا|کس کس چیز پر) (خرچ کیا|خریدا|لیا)',
        '(آج|اج) (میں نے|ہم نے) (کتنا|کتنے) (خرچ کیا|خرچہ کیا|پیسے خرچ کیے)',
    ]),
    ('kal kitna kharcha hua', [
        'kal (ka|ke) (kharcha|kharche|hisaab) (batao|bata do|kitna tha|kya tha)',
        'kal (kitne|kitna) (paise|rupay) (lage|lage the|gaye|kharch hue)',
        'کل (کا|کے) (خرچہ|خرچے|حساب) (بتاؤ|بتا دو|کتنا تھا|کیا تھا)',
        'کل (کتنے|کتنا) (پیسے|روپے) (لگے|لگے تھے|گئے|خرچ ہوئے)',
    ]),
    ('is hafte kitna kharcha hua', [
        '(is|iss) (hafte|haftay|week) (ka|ke) (kharcha|kharche|hisaab) (batao|kitna hai|kya hai)',
        '(is|iss) (hafte|haftay|week) (kitne|kitna) (paise|rupay) (lage|gaye|kharch hue)',
        '(اس) (ہفتے) (کا|کے) (خرچہ|خرچے|حساب) (بتاؤ|کتنا ہے|کیا ہے)',
        '(اس) (ہفتے) (کتنے|کتنا) (پیسے|روپے) (لگے|گئے|خرچ ہوئے)',
    ]),
    ('is mahine kitna kharcha hua', [
        '(is|iss) (mahine|maheene|month) (ka|ke) (kharcha|kharche|hisaab) (batao|kitna hai|kya hai|sunao)',
        '(is|iss) (mahine|maheene|month) (kitne|kitna) (paise|rupay) (lage|gaye|kharch hue|udh gaye)',
        '(mahine|maheene) (ka|ke) (total|kul|poora) (kharcha|hisaab) (batao|kitna hua|kya hai)',
        '(اس) (مہینے|ماہ) (کا|کے) (خرچہ|خرچے|حساب) (بتاؤ|کتنا ہے|کیا ہے|سناؤ)',
        '(اس) (مہینے|ماہ) (کتنے|کتنا) (پیسے|روپے) (لگے|گئے|خرچ ہوئے)',
        '(مہینے) (کا|کے) (ٹوٹل|کل|پورا) (خرچہ|حساب) (بتاؤ|کتنا ہوا|کیا ہے)',
    ]),
    ('pichle mahine kitna kharcha hua', [
        '(pichle|pichhle|guzre) (mahine|maheene|month) (ka|ke) (kharcha|hisaab) (batao|kitna tha|kya tha)',
        '(پچھلے|گزشتہ|گزرے) (مہینے) (کا|کے) (خرچہ|حساب) (بتاؤ|کتنا تھا|کیا تھا)',
    ]),
    ('ab tak kitna kharcha hua', [
        '(ab tak|abhi tak|shuru se) (ka|ke) (sara|poora|total) (kharcha|hisaab) (batao|kitna hai)',
        '(ab tak|abhi tak) (kitne|kitna) (paise|rupay) (kharch hue|lag chuke|gaye)',
        '(اب تک|ابھی تک|شروع سے) (کا|کے) (سارا|پورا|ٹوٹل) (خرچہ|حساب) (بتاؤ|کتنا ہے)',
        '(اب تک|ابھی تک) (کتنے|کتنا) (پیسے|روپے) (خرچ ہوئے|لگ چکے|گئے)',
    ]),
    ('sab se zyada kharcha kis par hua', [
        '(sab se|sabse) (zyada|ziada) (paise|kharcha|pese) (kahan|kis cheez par|kis par) (lage|gaye|hua|ja rahe hain)',
        '(paise|pese) (kahan|kis cheez par) (zyada|ziada) (ja rahe hain|lag rahe hain|ud rahe hain)',
        '(سب سے|سبسے) (زیادہ) (پیسے|خرچہ) (کہاں|کس چیز پر|کس پر) (لگے|گئے|ہوا|جا رہے ہیں)',
        '(پیسے) (کہاں|کس چیز پر) (زیادہ) (جا رہے ہیں|لگ رہے ہیں|اڑ رہے ہیں)',
    ]),
    ('kharche ki tafseel batao', [
        '(kharche|kharchay|kharch) (ki|ka) (tafseel|detail|list|poori list) (batao|do|sunao|dikhao)',
        '(kis kis|kya kya) (cheez par|par) (kitna|kitne) (kharch hua|lage|gaye)',
        '(خرچے|خرچ) (کی|کا) (تفصیل|لسٹ|پوری لسٹ) (بتاؤ|دو|سناؤ)',
        '(کس کس|کیا کیا) (چیز پر|پر) (کتنا|کتنے) (خرچ ہوا|لگے|گئے)',
    ]),
    ('is mahine kitni bachat hui', [
        '(is|iss) (mahine|maheene) (kitni|kitne) (bachat|paise bache|bachay|bach gaye) (hui|hai|hain)',
        '(kitne|kitna) (paise|rupay) (bache|bach gaye|bachay) (hain|hue)',
        '(meri|hamari) (bachat|saving|savings) (kitni|kya) (hai|hui)',
        '(اس) (مہینے) (کتنی|کتنے) (بچت|پیسے بچے|بچ گئے) (ہوئی|ہے|ہیں)',
        '(کتنے|کتنا) (پیسے|روپے) (بچے|بچ گئے) (ہیں|ہوئے)',
        '(میری|ہماری) (بچت) (کتنی|کیا) (ہے|ہوئی)',
    ]),
    ('is mahine kitni aamdani hui', [
        '(is|iss) (mahine|maheene) (kitni|kitna) (aamdani|amdani|kamai|income|paise aaye) (hui|hai|aayi)',
        '(kitni|kitna) (kamai|kamaya|aamdani) (hui|kiya|hai)',
        '(اس) (مہینے) (کتنی|کتنا) (آمدنی|کمائی|پیسے آئے) (ہوئی|ہے|آئی)',
        '(کتنی|کتنا) (کمائی|کمایا|آمدنی) (ہوئی|کیا|ہے)',
    ]),
    ('udhar ka hisaab batao', [
        '(kis kis|kaun kaun) (ne|ko) (mujhe|mere) (paise|udhar) (dene hain|lene hain|dena hai)',
        '(udhar|udhaar|qarza|qarz) (ka|ki) (hisaab|list|tafseel) (batao|sunao|kya hai)',
        '(logon|sab logon) (se|ko) (kitna|kitne) (lena|dena) (hai|hain)',
        '(کس کس|کون کون) (نے|کو) (مجھے|میرے) (پیسے|ادھار) (دینے ہیں|لینے ہیں|دینا ہے)',
        '(ادھار|قرضہ|قرض) (کا|کی) (حساب|لسٹ|تفصیل) (بتاؤ|سناؤ|کیا ہے)',
        '(لوگوں|سب لوگوں) (سے|کو) (کتنا|کتنے) (لینا|دینا) (ہے|ہیں)',
    ]),
    ('aakhri kharcha kya tha', [
        '(aakhri|akhri|last|pichla) (kharcha|kharch|entry) (kya|kitna|kis cheez ka) (tha|thi)',
        '(abhi|thori der pehle) (kya|kitna) (likha|likhwaya) (tha|hai)',
        '(آخری|اخری|پچھلا) (خرچہ|خرچ|انٹری) (کیا|کتنا|کس چیز کا) (تھا|تھی)',
        '(ابھی|تھوڑی دیر پہلے) (کیا|کتنا) (لکھا|لکھوایا) (تھا|ہے)',
    ]),
    ('stock mein kitni eent hai', [
        '(stock|istak|godam|godown) (mein|me) (kitni|kitna) (eent|eentein|maal|saman) (hai|para hai|bachi hai)',
        '(eent|eentein|eenton) (ka|ki) (stock|hisaab) (batao|kitna hai|kya hai)',
        '(سٹاک|اسٹاک|گودام) (میں|مے) (کتنی|کتنا) (اینٹ|اینٹیں|مال|سامان) (ہے|پڑا ہے|بچی ہے)',
        '(اینٹ|اینٹوں) (کا|کی) (سٹاک|اسٹاک|حساب) (بتاؤ|کتنا ہے|کیا ہے)',
    ]),
    ('kis ke naam kitna likha hai', [
        '(sab|sare|saare) (logon|bandon) (ke|ka) (naam|khata|hisaab) (kitna|kya) (likha|hai)',
        '(khata|khaata|khate) (ka|ki) (hisaab|list|total) (batao|kya hai|kitna hai)',
        '(سب|سارے) (لوگوں|بندوں) (کے|کا) (نام|کھاتہ|حساب) (کتنا|کیا) (لکھا|ہے)',
        '(کھاتہ|کھاتے) (کا|کی) (حساب|لسٹ|ٹوٹل) (بتاؤ|کیا ہے|کتنا ہے)',
    ]),
    ('aaj kaun sa din hai', [
        '(aaj|aj) (konsa|kaunsa|kon sa|kaun sa|kya) (din|day|war) (hai|he)',
        '(aaj|aj) (din|day) (kaun sa|konsa|kya) (hai|he)',
        '(آج|اج) (کونسا|کون سا|کیا) (دن|ڈے) (ہے)',
        '(آج|اج) (دن|ڈے) (کون سا|کونسا|کیا) (ہے)',
    ]),
    ('aaj kya tareekh hai', [
        '(aaj|aj) (kya|kaunsi|konsi|kitni) (tareekh|tarikh|date) (hai|he)',
        '(aaj|aj) (date|tareekh) (kya|kitni|batao)',
        '(آج|اج) (کیا|کونسی|کتنی) (تاریخ|ڈیٹ) (ہے)',
        '(آج|اج) (کی) (تاریخ|ڈیٹ) (کیا|بتاؤ|کتنی) [ہے]',
    ]),
    ('time kya hua hai', [
        '(time|waqt|taim) (kya|kitna) (hua|ho gaya|hai) [hai]',
        '(kitne|kya) (baje|bajay) (hain|hue|ho gaye)',
        '(ٹائم|وقت) (کیا|کتنا) (ہوا|ہو گیا|ہے) [ہے]',
        '(کتنے|کیا) (بجے) (ہیں|ہوئے|ہو گئے)',
    ]),
]

# More shop questions, without names.
HISAAB += [
    ('aaj kitni aamdani hui', [
        '(aaj|aj) (ki|ka) (sale|sell|bikri|bikree|dihari|dehari|kamai) (kitni|kitna|kya) (hui|hai|rahi|bani)',
        '(aaj|aj) (kitna|kitne ka) (maal|saman|sauda) (bika|bik gaya|becha)',
        '(aaj|aj) (dukaan|dukan|shop) (ne|se) (kitna|kitne) (kamaya|kamaye|paise banaye)',
        '(آج|اج) (کی|کا) (سیل|بکری|دیہاڑی|کمائی) (کتنی|کتنا|کیا) (ہوئی|ہے|رہی|بنی)',
        '(آج|اج) (کتنا|کتنے کا) (مال|سامان|سودا) (بکا|بک گیا|بیچا)',
        '(آج|اج) (دکان) (نے|سے) (کتنا|کتنے) (کمایا|کمائے)',
    ]),
    ('aaj kitni bachat hui', [
        '(galle|galla|gala|gallay|cash) (mein|me) (kitne|kitna) [paise|rupay] (hain|hai|hone chahiye|bache)',
        '(aaj|aj) (ka )?(munafa|munafaa|profit|faida|fayda|nafa) (kitna|kya) (hua|hai|bana)',
        '(گلے|گلہ|کیش) (میں) (کتنے|کتنا) [پیسے|روپے] (ہیں|ہے|ہونے چاہییں|بچے)',
        '(آج|اج) [کا ](منافع|پرافٹ|فائدہ|نفع) (کتنا|کیا) (ہوا|ہے|بنا)',
    ]),
    ('is mahine kitni bachat hui', [
        '(is|iss) (mahine|maheene) (ka )?(munafa|munafaa|profit|faida|fayda|nafa) (kitna|kya) (hua|hai|bana)',
        '(nuqsan|nuksan|loss|ghata|khasara) (hua|ho raha|ho gaya) (hai)? (kya)?',
        '(اس) (مہینے) [کا ](منافع|پرافٹ|فائدہ|نفع) (کتنا|کیا) (ہوا|ہے|بنا)',
        '(نقصان|لاس|گھاٹا|خسارہ) (ہوا|ہو رہا|ہو گیا) [ہے] [کیا]',
    ]),
    ('is mahine kitni aamdani hui', [
        '(is|iss) (mahine|maheene) (ki|ka) (sale|sell|bikri|kamai) (kitni|kitna|kya) (hui|hai|rahi)',
        '(is|iss) (mahine|maheene) (kitna|kitne ka) (maal|saman) (bika|becha)',
        '(اس) (مہینے) (کی|کا) (سیل|بکری|کمائی) (کتنی|کتنا|کیا) (ہوئی|ہے|رہی)',
        '(اس) (مہینے) (کتنا|کتنے کا) (مال|سامان) (بکا|بیچا)',
    ]),
    ('kis ke naam kitna likha hai', [
        '(kis kis|kaun kaun|kon kon) (ka|ki|ke|ne) (baqaya|bakaya|udhar|paise|khata) (baqi|reh gaya|dena|hai|hain)',
        '(grahakon|gahakon|customers|logon) (ka|ki|se|ke) (udhar|baqaya|bakaya|paise) (kitna|kitne|batao|kya hai)',
        '(market|bazaar|bazar|bahar) (mein|me) (kitna|kitne) (paise|udhar|baqaya) (phansa|phanse|pare|para) (hai|hain|hua)',
        '(کس کس|کون کون) (کا|کی|کے|نے) (بقایا|ادھار|پیسے|کھاتہ) (باقی|رہ گیا|دینا|ہے|ہیں)',
        '(گاہکوں|کسٹمرز|لوگوں) (کا|کی|سے|کے) (ادھار|بقایا|پیسے) (کتنا|کتنے|بتاؤ|کیا ہے)',
        '(مارکیٹ|بازار|باہر) (میں) (کتنا|کتنے) (پیسے|ادھار|بقایا) (پھنسا|پھنسے|پڑے|پڑا) (ہے|ہیں|ہوا)',
    ]),
]

# Shop work with a name and an amount in it. "@" is where the name goes and "500"
# any number; the command uses {name} and {num}. These run even when the rules
# would have read the sentence another way, because in a shop "Ahmed ne saman
# udhar liya" means Ahmed owes the shop.
SLOTS = [
    ('{name} ke naam {num} rupay likh do', [   # a customer takes goods on credit: they owe the shop
        '@ (ne|nay) 500 (ka|ki|ke|rupay ka|rupay ki) (saman|samaan|maal|sauda|cheezen|cheez) (udhar|udhaar|khate mein|khatay mein|khate par) (liya|liye|le gaya|le gayi|lia|uthaya)',
        '@ (ne|nay) (udhar|udhaar) (mein )?500 (ka|ki|ke) (saman|maal|sauda) (liya|le gaya)',
        '@ (ko|kou) 500 (ka|ki|ke) (saman|samaan|maal|sauda) (udhar|udhaar) (diya|diye|dia|de diya)',
        '@ (ke|ka|ki) (khate|khatay|khata) (mein|me) 500 [rupay] (daal do|dal do|likh do|charha do|chadha do|jor do)',
        '@ (ka|ki) (baqaya|bakaya|baqi) 500 [rupay] (likh do|daal do|hai|ho gaya)',
        '@ (pe|par|ki taraf|ke zimme) 500 [rupay] (nikalte hain|nikalte|baqi hain|reh gaye|hain)',
        '@ (نے) 500 (کا|کی|کے|روپے کا) (سامان|مال|سودا|چیزیں) (ادھار|کھاتے میں|کھاتے پر) (لیا|لیے|لے گیا|لے گئی|اٹھایا)',
        '@ (نے) (ادھار) [میں ]500 (کا|کی|کے) (سامان|مال|سودا) (لیا|لے گیا)',
        '@ (کو) 500 (کا|کی|کے) (سامان|مال|سودا) (ادھار) (دیا|دیے|دے دیا)',
        '@ (کے|کا|کی) (کھاتے|کھاتہ) (میں) 500 [روپے] (ڈال دو|لکھ دو|چڑھا دو|جوڑ دو)',
        '@ (کا|کی) (بقایا|باقی) 500 [روپے] (لکھ دو|ڈال دو|ہے|ہو گیا)',
        '@ (پہ|پر|کی طرف|کے ذمے) 500 [روپے] (نکلتے ہیں|باقی ہیں|رہ گئے|ہیں)',
    ]),
    ('{name} ke naam se {num} rupay kam kar do', [   # a customer pays off what they owe
        '@ (ne|nay) 500 [rupay] (jama|jamaa) (karwaye|karwa diye|kara diye|kiye|karaye|kar diye)',
        '@ (se|say) 500 [rupay] (wasool|wusool|vasool|mil gaye|aa gaye|le liye) (hue|ho gaye|kiye|kar liye)',
        '@ (ne|nay) (apne )?(khate|khatay|baqaye|udhar) (ke|ka|mein se) 500 [rupay] (diye|de diye|jama karwaye|chuka diye)',
        '@ (ka|ki) 500 [rupay] (ka )?(khata|baqaya|udhar) (clear|saaf|ada) (ho gaya|kar diya|hua)',
        '@ (نے) 500 [روپے] (جمع) (کروائے|کروا دیے|کرا دیے|کیے|کر دیے)',
        '@ (سے) 500 [روپے] (وصول|مل گئے|آ گئے|لے لیے) (ہوئے|ہو گئے|کیے|کر لیے)',
        '@ (نے) [اپنے ](کھاتے|بقایا|ادھار) (کے|کا|میں سے) 500 [روپے] (دیے|دے دیے|جمع کروائے|چکا دیے)',
    ]),
    ('{name} se {num} udhar liye', [   # the shop takes goods on credit from a supplier: the shop owes them
        '@ (se|say) 500 (ka|ki|ke) (maal|saman|samaan|stock) (udhar|udhaar|credit par|khate mein) (liya|liye|uthaya|aaya|mangwaya)',
        '@ (ka|ki) 500 (ka )?(bill|bil) (aaya|aya|baqi hai|dena hai|aa gaya)',
        '@ (ko|kou) 500 [rupay] (dene|dena) (hain|hai|baqi hain)',
        '@ (سے) 500 (کا|کی|کے) (مال|سامان|اسٹاک) (ادھار|کریڈٹ پر|کھاتے میں) (لیا|لیے|اٹھایا|آیا|منگوایا)',
        '@ (کا|کی) 500 [کا ](بل) (آیا|باقی ہے|دینا ہے|آ گیا)',
        '@ (کو) 500 [روپے] (دینے|دینا) (ہیں|ہے|باقی ہیں)',
    ]),
    ('{name} ko {num} wapas kiye', [   # the shop pays a supplier what it owes
        '@ (ko|kou) 500 (ka|rupay ka)? ?(bill|bil|payment|adaigi) (de diya|de diye|chuka diya|ada kar diya|bhej diya|kar diya)',
        '@ (ka|ki) (bill|bil|payment) 500 [rupay] (ada|clear|chuka) (kar diya|ho gaya|kiya)',
        '@ (ko|kou) (bill|bil) (ke|ka) 500 [rupay] (de diye|bhej diye|diye)',
        '@ (کو) 500 [روپے کا ](بل|پیمنٹ|ادائیگی) (دے دیا|دے دیے|چکا دیا|ادا کر دیا|بھیج دیا|کر دیا)',
        '@ (کا|کی) (بل|پیمنٹ) 500 [روپے] (ادا|کلیئر|چکا) (کر دیا|ہو گیا|کیا)',
        '@ (کو) (بل) (کے|کا) 500 [روپے] (دے دیے|بھیج دیے|دیے)',
    ]),
    ('{name} ka kitna udhar hai', [   # whichever way round, what stands between the shop and this person
        '@ (ka|ki|ke) (baqaya|bakaya|baqi|udhar|udhaar|khata|hisaab|hisab|bill) (kitna|kya|kitne) (hai|hain|reh gaya|bacha|banta)',
        '@ (pe|par|ki taraf|ke zimme) (kitne|kitna) [paise|rupay] (nikalte|baqi|reh gaye|hain) [hain]',
        '@ (se|say) (kitne|kitna) [paise|rupay] (lene|lena) (hain|hai)',
        '@ (ko|kou) (kitne|kitna) [paise|rupay] (dene|dena) (hain|hai)',
        '@ (کا|کی|کے) (بقایا|باقی|ادھار|کھاتہ|حساب|بل) (کتنا|کیا|کتنے) (ہے|ہیں|رہ گیا|بچا|بنتا)',
        '@ (پہ|پر|کی طرف|کے ذمے) (کتنے|کتنا) [پیسے|روپے] (نکلتے|باقی|رہ گئے|ہیں) [ہیں]',
        '@ (سے) (کتنے|کتنا) [پیسے|روپے] (لینے|لینا) (ہیں|ہے)',
        '@ (کو) (کتنے|کتنا) [پیسے|روپے] (دینے|دینا) (ہیں|ہے)',
    ]),
    ('{num} rupay ki aamdani hui', [   # the day's takings
        '(aaj|aj|kal)? ?(ki|ka) (sale|sell|bikri|bikree|dihari|dehari|galla|kamai) 500 [rupay] (hui|hai|rahi|ho gayi|bani)',
        '500 [rupay] (ki|ka) (sale|sell|bikri|bikree|dihari|dehari) (hui|hai|rahi|ho gayi)',
        '500 [rupay] (ka|ki) (maal|saman|sauda) (bika|biki|bik gaya|becha|bech diya)',
        '(galle|galla|gallay) (mein|me) 500 [rupay] (aaye|aa gaye|jama hue|hain)',
        '[آج |اج |کل ](کی|کا) (سیل|بکری|دیہاڑی|گلہ|کمائی) 500 [روپے] (ہوئی|ہے|رہی|ہو گئی|بنی)',
        '500 [روپے] (کی|کا) (سیل|بکری|دیہاڑی) (ہوئی|ہے|رہی|ہو گئی)',
        '500 [روپے] (کا|کی) (مال|سامان|سودا) (بکا|بکی|بک گیا|بیچا|بیچ دیا)',
        '(گلے|گلہ) (میں) 500 [روپے] (آئے|آ گئے|جمع ہوئے|ہیں)',
    ]),
    ('{num} ka maal kharida', [   # stock bought for cash
        '500 (ka|ki|ke) (maal|saman|samaan|stock|sauda) (kharida|khareeda|liya|le aaya|mangwaya|aaya) (cash|naqad|naqd|nakad) (mein|par)?',
        '(mandi|market|godam) (se|say) 500 (ka|ki|ke) (maal|saman|sabzi|phal|stock) (liya|kharida|khareeda|laye|le aaya)',
        '500 (ka|ki|ke) (naya )?(maal|stock) (dala|dalwaya|bharwaya|mangwaya)',
        '500 (کا|کی|کے) (مال|سامان|اسٹاک|سودا) (خریدا|لیا|لے آیا|منگوایا|آیا) (کیش|نقد) [میں|پر]',
        '(منڈی|مارکیٹ|گودام) (سے) 500 (کا|کی|کے) (مال|سامان|سبزی|پھل|اسٹاک) (لیا|خریدا|لائے|لے آیا)',
        '500 (کا|کی|کے) [نیا ](مال|اسٹاک) (ڈالا|ڈلوایا|بھروایا|منگوایا)',
    ]),
    ('{num} ki mazdoori di', [   # wages
        '(mazdoor|mazdooron|karigar|kaarigar|worker|workers|naukar|larke|larkon|chhotu|munshi|staff) (ko|kou) 500 [rupay] (ki )?(mazdoori|tankhwah|tankha|pagaar|dihari|salary|paise) (di|diye|de di|de diye|diya)',
        '500 [rupay] (ki|ka) (mazdoori|pagaar|dihari|salary) (di|diya|de di|ada ki)',
        '(مزدور|مزدوروں|کاریگر|ورکر|نوکر|لڑکے|لڑکوں|چھوٹو|سٹاف) (کو) 500 [روپے] [کی ](مزدوری|تنخواہ|پگار|دیہاڑی|سیلری|پیسے) (دی|دیے|دے دی|دے دیے|دیا)',
        '500 [روپے] (کی|کا) (مزدوری|پگار|دیہاڑی|سیلری) (دی|دیا|دے دی|ادا کی)',
    ]),
]

# ---------------------------------------------------------------- gup-shup ---
# (id, patterns, replies as (roman, urdu)). Replies rotate.
CHAT = [
    ('salam', ['(assalam o alaikum|assalamualaikum|asalam o alaikum|salam|salaam|aoa)', '(السلام علیکم|اسلام علیکم|سلام)'],
     [('Wa alaikum assalam! Kaise hain aap?', 'وعلیکم السلام! کیسے ہیں آپ؟')]),
    ('hello', ['(hello|hi|hey|helo|hy)[ ji]', '(ہیلو|ہائے)[ جی]'],
     [('Ji, salam! Bataiye kya haal hai?', 'جی، سلام! بتائیے کیا حال ہے؟')]),
    ('haal', ['(kya|kia) haal (hai|hain|he) [aap ka|tumhara|tera]', '(kaise|kese|kaisi|kesi) (ho|hain|hai) [aap|tum]', '(sunao|sunaen|sunaiye) (kya|kia) haal (hai|hain)', '(کیا|کیسے) (حال|ہو) (ہے|ہیں|ہو)', '(کیسی|کیسے) (ہو|ہیں) [آپ|تم]', '(سناؤ|سنائیں) (کیا) (حال) (ہے|ہیں)'],
     [('Allah ka shukar hai, main theek hoon. Aap sunaiye?', 'اللہ کا شکر ہے، میں ٹھیک ہوں۔ آپ سنائیے؟'), ('Main bilkul theek hoon. Aap ka din kaisa ja raha hai?', 'میں بالکل ٹھیک ہوں۔ آپ کا دن کیسا جا رہا ہے؟')]),
    ('main_theek', ['(main|mein|hum) (bhi )?(theek|thik|achha|acha|fit|khairiyat se) (hoon|hun|hain|hu)', '(main|mein) (bilkul|ekdum) (theek|fit) (hoon|hun)', '(میں|ہم) [بھی ](ٹھیک|اچھا|خیریت سے|فٹ) (ہوں|ہیں)', '(alhamdulillah|الحمدللہ|الحمد للہ)[ theek hoon| ٹھیک ہوں]'],
     [('Yeh sun kar khushi hui. Allah aap ko hamesha khush rakhe.', 'یہ سن کر خوشی ہوئی۔ اللہ آپ کو ہمیشہ خوش رکھے۔'), ('Shukar hai. Bataiye, aaj kya chal raha hai?', 'شکر ہے۔ بتائیے، آج کیا چل رہا ہے؟')]),
    ('kya_kar_rahi', ['(kya|kia) (kar|karti|kr) (rahi|rhi|raha) (ho|hai|hain)', '(aaj kal|ajkal) (kya|kia) (karti|kar rahi) (ho|hain)', '(کیا|کیہ) (کر|کرتی) (رہی|رہا) (ہو|ہے|ہیں)', '(آج کل|اجکل) (کیا) (کرتی|کر رہی) (ہو|ہیں)'],
     [('Aap se baatein kar rahi hoon. Aap sunaiye?', 'آپ سے باتیں کر رہی ہوں۔ آپ سنائیے؟'), ('Aap ka hisaab sambhal rahi hoon aur aap ki baatein sun rahi hoon.', 'آپ کا حساب سنبھال رہی ہوں اور آپ کی باتیں سن رہی ہوں۔')]),
    ('kaun_ho', ['(tum|aap|tu) (kaun|kon) (ho|hain|hai)', '(apna|apne) (baare|bare) (mein|me) (batao|bataen)', '(تم|آپ|تو) (کون) (ہو|ہیں|ہے)', '(اپنے) (بارے) (میں) (بتاؤ|بتائیں)'],
     [('Main aap ki munshi hoon. Aap ka hisaab rakhti hoon, baatein yaad rakhti hoon, aur aap se gup-shup bhi kar leti hoon.', 'میں آپ کی منشی ہوں۔ آپ کا حساب رکھتی ہوں، باتیں یاد رکھتی ہوں، اور آپ سے گپ شپ بھی کر لیتی ہوں۔')]),
    ('kya_kar_sakti', ['(tum|aap) (kya kya|kya) (kar|kr) (sakti|sakte) (ho|hain)', '(tumhe|aap ko) (kya kya|kya) (aata|ata) (hai|he)', '(تم|آپ) (کیا کیا|کیا) (کر) (سکتی|سکتے) (ہو|ہیں)', '(تمہیں|آپ کو) (کیا کیا|کیا) (آتا|اتا) (ہے)'],
     [('Main kharcha, aamdani, udhar, khata aur stock likhti hoon, hisaab batati hoon, din aur waqt batati hoon, hisaab lagati hoon, aur gup-shup bhi karti hoon.', 'میں خرچہ، آمدنی، ادھار، کھاتہ اور اسٹاک لکھتی ہوں، حساب بتاتی ہوں، دن اور وقت بتاتی ہوں، حساب لگاتی ہوں، اور گپ شپ بھی کرتی ہوں۔')]),
    ('shukriya', ['(shukriya|shukria|thank you|thanks|thank u|meharbani|jazakallah|jazak allah)[ ji| yaar| bohat| bahut]', '(bohat|bahut) (shukriya|meharbani)', '(شکریہ|مہربانی|جزاک اللہ)[ جی| یار| بہت]', '(بہت) (شکریہ|مہربانی)'],
     [('Koi baat nahi, khushi hui.', 'کوئی بات نہیں، خوشی ہوئی۔'), ('Aap ka bhi shukriya.', 'آپ کا بھی شکریہ۔')]),
    ('acha', ['(acha|achha|accha|theek hai|thik hai|ok|okay|chalo|chalein|hmm|han|haan)', '(اچھا|ٹھیک ہے|اوکے|چلو|ہاں|ہمم)'],
     [('Ji.', 'جی۔'), ('Ji, bataiye.', 'جی، بتائیے۔')]),
    ('khana_khaya', ['(khana|khaana) (kha liya|khaya|kha lia) [tum ne|aap ne]', '(tum ne|aap ne) (khana|khaana) (khaya|kha liya)', '(کھانا) (کھا لیا|کھایا) [تم نے|آپ نے]', '(تم نے|آپ نے) (کھانا) (کھایا|کھا لیا)'],
     [('Main to khana nahi khati, lekin aap ne khaya? Waqt par kha lijiye.', 'میں تو کھانا نہیں کھاتی، لیکن آپ نے کھایا؟ وقت پر کھا لیجیے۔')]),
    ('kya_khaya', ['(aaj|aj) (kya|kia) (pakaya|banaya|khaya|pakka) [hai]', '(آج|اج) (کیا) (پکایا|بنایا|کھایا) [ہے]'],
     [('Main to nahi khati, aap bataiye aaj kya bana?', 'میں تو نہیں کھاتی، آپ بتائیے آج کیا بنا؟')]),
    ('namaz', ['(namaz|namaaz|salah) (ka|ki) (waqt|time) (ho gaya|hai|kab hai)', '(namaz|namaaz) (parhne|padhne) (ja raha|jaa raha|ja rahi) (hoon|hun)', '(نماز) (کا|کی) (وقت|ٹائم) (ہو گیا|ہے|کب ہے)', '(نماز) (پڑھنے) (جا رہا|جا رہی) (ہوں)'],
     [('Ji zaroor, Allah qabool kare.', 'جی ضرور، اللہ قبول کرے۔')]),
    ('jumma_mubarak', ['(jumma|juma|jummah) mubarak', '(جمعہ) (مبارک)'], [('Aap ko bhi Jumma Mubarak!', 'آپ کو بھی جمعہ مبارک!')]),
    ('eid_mubarak', ['(eid|id) mubarak', '(عید) (مبارک)'], [('Khair Mubarak! Aap ko bhi Eid bohat bohat mubarak.', 'خیر مبارک! آپ کو بھی عید بہت بہت مبارک۔')]),
    ('ramzan', ['(ramzan|ramadan|ramazan) (mubarak|kareem)', '(رمضان) (مبارک|کریم)'], [('Aap ko bhi Ramzan Mubarak. Allah roze qabool kare.', 'آپ کو بھی رمضان مبارک۔ اللہ روزے قبول کرے۔')]),
    ('subah', ['(subah|good morning|gud morning) (bakhair|bakher|morning)?', '(صبح) (بخیر)', 'good morning'], [('Subah bakhair! Allah kare aaj ka din acha guzre.', 'صبح بخیر! اللہ کرے آج کا دن اچھا گزرے۔')]),
    ('raat', ['(shab|good night|gud night) (bakhair|bakher|night)?', '(شب) (بخیر)', '(so|sone) (raha|ja raha|ja rahi|lagi) (hoon|hun)', '(میں) (سونے) (جا رہا|جا رہی|لگا) (ہوں)'], [('Shab bakhair! Aaram se soiye.', 'شب بخیر! آرام سے سوئیے۔')]),
    ('khuda_hafiz_soon', ['(phir|fir) (milte|baat karte) (hain|hen)', '(baad|bad) (mein|me) (baat|bat) (karte|karenge|karoon ga) (hain)?', '(پھر) (ملتے|بات کرتے) (ہیں)', '(بعد) (میں) (بات) (کرتے|کریں گے) [ہیں]'],
     [('Ji zaroor, jab chahein bula lijiye ga.', 'جی ضرور، جب چاہیں بلا لیجیے گا۔')]),
    ('thak_gaya', ['(main|mein) (bohat|bahut|bht)? ?(thak|thaka|thaki) (gaya|gayi|gai|hua|hui) (hoon|hun)', '(aaj|aj) (bohat|bahut) (thakan|thakawat) (hai|ho gayi)', '(میں) [بہت ](تھک|تھکا|تھکی) (گیا|گئی|ہوا|ہوئی) (ہوں)', '(آج|اج) (بہت) (تھکن|تھکاوٹ) (ہے|ہو گئی)'],
     [('Thori der aaram kar lijiye. Pani pi lein to acha lage ga.', 'تھوڑی دیر آرام کر لیجیے۔ پانی پی لیں تو اچھا لگے گا۔'), ('Aaj bohat kaam kiya hai aap ne. Ab aaram kijiye.', 'آج بہت کام کیا ہے آپ نے۔ اب آرام کیجیے۔')]),
    ('udaas', ['(main|mein) (udaas|udas|pareshan|dukhi|tension mein) (hoon|hun)', '(dil|mood) (udaas|kharab|off|theek nahi) (hai|he)', '(بہت|بڑی) (ٹینشن|پریشانی) (ہے)', '(میں) (اداس|پریشان|دکھی|ٹینشن میں) (ہوں)', '(دل|موڈ) (اداس|خراب|ٹھیک نہیں) (ہے)'],
     [('Mujhe afsos hai. Kya hua? Bataiye, dil halka ho jaye ga.', 'مجھے افسوس ہے۔ کیا ہوا؟ بتائیے، دل ہلکا ہو جائے گا۔'), ('Allah aap ki mushkil aasan kare. Sab theek ho jaye ga.', 'اللہ آپ کی مشکل آسان کرے۔ سب ٹھیک ہو جائے گا۔')]),
    ('khush', ['(main|mein) (bohat|bahut)? ?(khush|khus) (hoon|hun)', '(aaj|aj) (bohat|bahut)? ?(maza|mazza) (aaya|aya)', '(میں) [بہت ](خوش) (ہوں)', '(آج|اج) [بہت ](مزہ|مزا) (آیا|ایا)'],
     [('Yeh sun kar bohat acha laga! Allah aap ko hamesha khush rakhe.', 'یہ سن کر بہت اچھا لگا! اللہ آپ کو ہمیشہ خوش رکھے۔')]),
    ('bimar', ['(main|mein) (bimar|beemar|sick) (hoon|hun)', '(mujhe|mujhay) (bukhar|bukhaar|zukaam|nazla|khansi|sar dard|dard) (hai|ho gaya)', '(tabiyat|tabeeyat|tabiat) (theek nahi|kharab) (hai)', '(میں) (بیمار) (ہوں)', '(مجھے) (بخار|زکام|نزلہ|کھانسی|سر درد|درد) (ہے|ہو گیا)', '(طبیعت) (ٹھیک نہیں|خراب) (ہے)'],
     [('Allah aap ko shifa de. Aaram kijiye, pani zyada piyein, aur zaroorat ho to doctor ko dikhayein.', 'اللہ آپ کو شفا دے۔ آرام کیجیے، پانی زیادہ پئیں، اور ضرورت ہو تو ڈاکٹر کو دکھائیں۔')]),
    ('bhook', ['(mujhe|mujhay) (bhook|bhuk) (lagi|lag rahi) (hai)', '(مجھے) (بھوک) (لگی|لگ رہی) (ہے)'], [('To kuch kha lijiye. Aur kharcha mujhe bata dijiye ga.', 'تو کچھ کھا لیجیے۔ اور خرچہ مجھے بتا دیجیے گا۔')]),
    ('pyaas', ['(mujhe|mujhay) (pyaas|pyas) (lagi|lag rahi) (hai)', '(مجھے) (پیاس) (لگی|لگ رہی) (ہے)'], [('Pani pi lijiye, sehat ke liye acha hai.', 'پانی پی لیجیے، صحت کے لیے اچھا ہے۔')]),
    ('neend', ['(mujhe|mujhay) (neend|nind) (aa rahi|a rahi|ati) (hai)', '(mujhe|mujhay) (neend|nind) (nahi|nai) (aa rahi|a rahi|ati)', '(مجھے) (نیند) (آ رہی|ا رہی|آتی) (ہے)', '(مجھے) (نیند) (نہیں) (آ رہی|ا رہی|آتی)'],
     [('Phone rakh kar aankhein band kar lein aur gehri saans lein.', 'فون رکھ کر آنکھیں بند کر لیں اور گہری سانس لیں۔')]),
    ('bore', ['(main|mein) (bore|bor) (ho raha|ho rahi|hua|hui) (hoon|hun)', '(kuch|koi) (baat|bat) (karo|kro|sunao)', '(میں) (بور) (ہو رہا|ہو رہی) (ہوں)', '(کچھ|کوئی) (بات) (کرو|سناؤ)'],
     [('Chaliye baat karte hain. Aaj kya kya hua? Ya lateefa sunaoon?', 'چلیے بات کرتے ہیں۔ آج کیا کیا ہوا؟ یا لطیفہ سناؤں؟')]),
    ('gussa', ['(mujhe|mujhay) (gussa|ghussa) (aa raha|a raha|aaya) (hai)', '(میں|مجھے) (غصے|غصہ) (میں|آ رہا|آیا) (ہوں|ہے)'], [('Thora pani pi lein aur das tak ginein. Gussa thanda ho jaye ga.', 'تھوڑا پانی پی لیں اور دس تک گنیں۔ غصہ ٹھنڈا ہو جائے گا۔')]),
    ('dar', ['(mujhe|mujhay) (dar|khauf) (lag raha|lagta|lagta hai) (hai)?', '(مجھے) (ڈر|خوف) (لگ رہا|لگتا) [ہے]'], [('Ghabraiye nahi, main aap ke sath hoon. Gehri saans lijiye.', 'گھبرائیے نہیں، میں آپ کے ساتھ ہوں۔ گہری سانس لیجیے۔')]),
    ('akela', ['(main|mein) (akela|akeli|tanha) (hoon|hun|mehsoos karta hoon)', '(میں) (اکیلا|اکیلی|تنہا) (ہوں)'], [('Main yahin hoon. Aap mujh se jitni chahein baat kar sakte hain.', 'میں یہیں ہوں۔ آپ مجھ سے جتنی چاہیں بات کر سکتے ہیں۔')]),
    ('kaam_zyada', ['(aaj|aj) (kaam|kam) (bohat|bahut|zyada|ziada) (tha|hai|hua)', '(dukaan|dukan|shop) (par|pe) (rush|bheer|bhir|grahak) (bohat|zyada) (tha|hai)', '(آج|اج) (کام) (بہت|زیادہ) (تھا|ہے|ہوا)', '(دکان) (پر|پہ) (رش|بھیڑ|گاہک) (بہت|زیادہ) (تھا|ہے)'],
     [('Mashallah, kaam achha chal raha hai. Thora aaram bhi kijiye.', 'ماشاءاللہ، کام اچھا چل رہا ہے۔ تھوڑا آرام بھی کیجیے۔')]),
    ('kaam_kam', ['(aaj|aj) (kaam|kam|sale|bikri) (kam|thanda|mandi) (tha|hai|raha)', '(dukaan|dukan) (par|pe) (koi|grahak) (nahi|nai) (aaya|aya)', '(آج|اج) (کام|سیل|بکری) (کم|ٹھنڈا|مندا) (تھا|ہے|رہا)', '(دکان) (پر|پہ) (کوئی|گاہک) (نہیں) (آیا)'],
     [('Koi baat nahi, kabhi kam kabhi zyada. Kal behtar hoga, inshallah.', 'کوئی بات نہیں، کبھی کم کبھی زیادہ۔ کل بہتر ہوگا، انشاءاللہ۔')]),
    ('grahak_tang', ['(grahak|gahak|customer) (tang|pareshan) (karta|kar raha|karte) (hai|hain)', '(udhar|udhaar) (wapas|wapis) (nahi|nai) (deta|de raha|dete) (hai|hain)?', '(گاہک|کسٹمر) (تنگ|پریشان) (کرتا|کر رہا|کرتے) (ہے|ہیں)', '(ادھار) (واپس) (نہیں) (دیتا|دے رہا|دیتے) [ہے|ہیں]'],
     [('Udhar dete waqt had rakhein, aur jo purana udhar na de use naya saman naqad dein. Main har ek ka hisaab yaad rakhti hoon, poochh lijiye.', 'ادھار دیتے وقت حد رکھیں، اور جو پرانا ادھار نہ دے اسے نیا سامان نقد دیں۔ میں ہر ایک کا حساب یاد رکھتی ہوں، پوچھ لیجیے۔')]),
    ('rate_barhao', ['(rate|qeemat|price) (barhaun|barhaon|barha doon|kam karoon) (kya)?', '(maal|saman) (ka )?(rate|qeemat) (kya|kitna) (rakhun|rakhoon|lagaun)', '(ریٹ|قیمت) (بڑھاؤں|بڑھا دوں|کم کروں) [کیا]', '(مال|سامان) [کا ](ریٹ|قیمت) (کیا|کتنا) (رکھوں|لگاؤں)'],
     [('Khareed ki qeemat par apna kharcha aur munasib munafa jor kar rate rakhein, aur aas paas ki dukanon ka rate bhi dekh lein.', 'خرید کی قیمت پر اپنا خرچہ اور مناسب منافع جوڑ کر ریٹ رکھیں، اور آس پاس کی دکانوں کا ریٹ بھی دیکھ لیں۔')]),
    ('dukaan_band', ['(dukaan|dukan|shop) (band|bund) (kar|kr) (raha|rahi|di|diya|li) (hoon|hun|hai)?', '(دکان) (بند) (کر) (رہا|رہی|دی|دیا|لی) [ہوں|ہے]'],
     [('Theek hai. Aaj ki sale bata dein to likh deti hoon, phir aaram kijiye.', 'ٹھیک ہے۔ آج کی سیل بتا دیں تو لکھ دیتی ہوں، پھر آرام کیجیے۔')]),
    ('dukaan_khol', ['(dukaan|dukan|shop) (khol|khool) (raha|rahi|di|diya|li|li hai) (hoon|hun|hai)?', '(دکان) (کھول) (رہا|رہی|دی|دیا|لی) [ہوں|ہے]'],
     [('Bismillah! Allah kaam mein barkat de.', 'بسم اللہ! اللہ کام میں برکت دے۔')]),
    ('mehngai', ['(mehngai|mehangai|mahngai) (bohat|bahut|zyada) (ho gayi|hai|barh gayi)', '(cheezen|cheezein|sab kuch) (mehngi|mehngay|mehnga) (ho gayi|ho gaya|hai)', '(مہنگائی) (بہت|زیادہ) (ہو گئی|ہے|بڑھ گئی)', '(چیزیں|سب کچھ) (مہنگی|مہنگا) (ہو گئی|ہو گیا|ہے)'],
     [('Ji, mehngai waqai barh gayi hai. Isi liye hisaab rakhna zaroori hai, taake pata rahe paisa kahan ja raha hai.', 'جی، مہنگائی واقعی بڑھ گئی ہے۔ اسی لیے حساب رکھنا ضروری ہے، تاکہ پتا رہے پیسہ کہاں جا رہا ہے۔')]),
    ('paise_nahi', ['(paise|pese) (nahi|nai|khatam) (hain|ho gaye)', '(haath|hath) (tang|tung) (hai)', '(پیسے) (نہیں|ختم) (ہیں|ہو گئے)', '(ہاتھ) (تنگ) (ہے)'],
     [('Fikar na karein, waqt badalta hai. Chaliye dekhte hain kharcha kahan kam ho sakta hai: poochiye sab se zyada kharcha kis par hua.', 'فکر نہ کریں، وقت بدلتا ہے۔ چلیے دیکھتے ہیں خرچہ کہاں کم ہو سکتا ہے: پوچھیے سب سے زیادہ خرچہ کس پر ہوا۔')]),
    ('bachat_tips', ['(paise|pese) (kaise|kese) (bachaen|bachayen|bachaun|bacha sakta hoon|bachen)', '(bachat|saving) (kaise|kese) (karen|karoon|ho)', '(پیسے) (کیسے) (بچائیں|بچاؤں|بچا سکتا ہوں)', '(بچت) (کیسے) (کریں|کروں|ہو)'],
     [('Har kharcha likhte rahein, mahine ke aakhir mein dekhein kahan zyada gaya. Aamdani aate hi thora alag rakh dein, phir baqi se kharch karein.', 'ہر خرچہ لکھتے رہیں، مہینے کے آخر میں دیکھیں کہاں زیادہ گیا۔ آمدنی آتے ہی تھوڑا الگ رکھ دیں، پھر باقی سے خرچ کریں۔')]),
    ('mausam_garmi', ['(aaj|aj) (bohat|bahut|bari|badi)? ?(garmi|garam) (hai|he|ho rahi)', '(dhoop|dhup) (bohat|tez) (hai)', '(آج|اج) [بہت |بڑی ](گرمی|گرم) (ہے|ہو رہی)', '(دھوپ) (بہت|تیز) (ہے)'],
     [('Ji, garmi mein pani zyada piyein aur dhoop se bachein.', 'جی، گرمی میں پانی زیادہ پئیں اور دھوپ سے بچیں۔')]),
    ('mausam_sardi', ['(aaj|aj) (bohat|bahut)? ?(sardi|thand|thandi|thanda) (hai|he|ho rahi)', '(آج|اج) [بہت ](سردی|ٹھنڈ|ٹھنڈی) (ہے|ہو رہی)'],
     [('Sardi mein garam kapre pehnein aur chai ka maza lein.', 'سردی میں گرم کپڑے پہنیں اور چائے کا مزہ لیں۔')]),
    ('mausam_barish', ['(barish|baarish|barsat) (ho rahi|ho gayi|aa rahi|aayi) (hai)?', '(بارش|برسات) (ہو رہی|ہو گئی|آ رہی|آئی) [ہے]'],
     [('Barish ka apna hi maza hai. Bheegne se bachiye ga.', 'بارش کا اپنا ہی مزہ ہے۔ بھیگنے سے بچیے گا۔')]),
    ('mausam_q', ['(aaj|aj|kal) (mausam|weather) (kaisa|kesa|kya) (hai|hoga|rahe ga)', '(آج|اج|کل) (موسم) (کیسا|کیا) (ہے|ہوگا|رہے گا)'],
     [('Mausam ka haal mujhe nahi pata, mere paas internet ki khabar nahi aati. Bahar dekh lijiye.', 'موسم کا حال مجھے نہیں پتا، میرے پاس انٹرنیٹ کی خبر نہیں آتی۔ باہر دیکھ لیجیے۔')]),
    ('lateefa', ['(koi )?(lateefa|latifa|joke|chutkula) (sunao|sunaen|suna do|batao)', '(mujhe )?(hansao|hansa do)', '[کوئی ](لطیفہ|جوک|چٹکلا) (سناؤ|سنائیں|سنا دو)', '[مجھے ](ہنساؤ|ہنسا دو)'],
     [('Ustad ne poocha: sab se zyada barish kahan hoti hai? Shagird bola: zameen par, sir!', 'استاد نے پوچھا: سب سے زیادہ بارش کہاں ہوتی ہے؟ شاگرد بولا: زمین پر، سر!'),
      ('Ek dukandar se grahak ne poocha: yeh anday taaze hain? Dukandar bola: itne taaze ke murghi ko abhi pata bhi nahi chala!', 'ایک دکاندار سے گاہک نے پوچھا: یہ انڈے تازے ہیں؟ دکاندار بولا: اتنے تازے کہ مرغی کو ابھی پتا بھی نہیں چلا!'),
      ('Bachay ne kaha: Abbu, main bara ho kar paise wala banoon ga. Abbu bole: shuruat abhi se karo, meri jeb se haath nikalo!', 'بچے نے کہا: ابو، میں بڑا ہو کر پیسے والا بنوں گا۔ ابو بولے: شروعات ابھی سے کرو، میری جیب سے ہاتھ نکالو!'),
      ('Doctor ne kaha: roz subah do kilometer chala karein. Mareez bola: mera ghar to dukaan se das qadam par hai!', 'ڈاکٹر نے کہا: روز صبح دو کلومیٹر چلا کریں۔ مریض بولا: میرا گھر تو دکان سے دس قدم پر ہے!')]),
    ('hasna', ['(haha|hahaha|hehe|lol|hahah)', '(ہاہا|ہاہاہا|ہی ہی)', '(maza|mazza) (aa gaya|aya) (yaar)?'], [('Aap ko hansta sun kar acha laga!', 'آپ کو ہنستا سن کر اچھا لگا!')]),
    ('tareef', ['(shabash|zabardast|kamaal|kamal|wah|great|very good|bohat acha|bahut achha)[ yaar| ho| hai]', '(tum|aap) (bohat|bahut) (achi|achhi|samajhdar|zabardast) (ho|hain)', '(شاباش|زبردست|کمال|واہ|بہت اچھا)[ یار| ہو| ہے]', '(تم|آپ) (بہت) (اچھی|سمجھدار|زبردست) (ہو|ہیں)'],
     [('Shukriya! Aap ki meharbani.', 'شکریہ! آپ کی مہربانی۔')]),
    ('bura', ['(tum|aap) (pagal|bewakoof|bewaqoof|nalaiq|bekar|ghatiya) (ho|hai)', '(tumhe|tumhen) (kuch|kuchh) (nahi|nai) (aata|ata|pata)', '(تم|آپ) (پاگل|بیوقوف|نالائق|بیکار|گھٹیا) (ہو|ہے)', '(تمہیں) (کچھ) (نہیں) (آتا|اتا|پتا)'],
     [('Maaf kijiye agar ghalti hui. Main roz seekh rahi hoon, aap batate rahiye.', 'معاف کیجیے اگر غلطی ہوئی۔ میں روز سیکھ رہی ہوں، آپ بتاتے رہیے۔')]),
    ('pyar', ['(i love you|love you|tum se pyar|tumse pyar|aap se pyar) (hai|karta hoon)?', '(تم سے|آپ سے) (پیار|محبت) [ہے|کرتا ہوں]'], [('Shukriya! Main bhi aap ki khidmat kar ke khush hoon.', 'شکریہ! میں بھی آپ کی خدمت کر کے خوش ہوں۔')]),
    ('dost', ['(kya )?(tum|aap) (meri|mere) (dost|friend) (ho|bano gi|banogi)', '(ہم|تم) (دوست) (ہیں|ہو)', '(کیا )?(تم|آپ) (میری|میرے) (دوست) (ہو|بنو گی)'], [('Ji bilkul, main aap ki dost bhi hoon aur munshi bhi.', 'جی بالکل، میں آپ کی دوست بھی ہوں اور منشی بھی۔')]),
    ('shaadi', ['(tumhari|aap ki) (shadi|shaadi) (hui|ho gayi) (hai)?', '(shadi|shaadi) (karo gi|karogi|karoge)', '(تمہاری|آپ کی) (شادی) (ہوئی|ہو گئی) [ہے]'], [('Main to ek program hoon, meri shaadi kahan! Aap sunaiye?', 'میں تو ایک پروگرام ہوں، میری شادی کہاں! آپ سنائیے؟')]),
    ('umar', ['(tumhari|aap ki|teri) (umar|umr|age) (kitni|kya) (hai)', '(تمہاری|آپ کی|تیری) (عمر) (کتنی|کیا) (ہے)'], [('Meri koi umar nahi, main to bas ek program hoon.', 'میری کوئی عمر نہیں، میں تو بس ایک پروگرام ہوں۔')]),
    ('kahan_rehti', ['(tum|aap) (kahan|kidhar) (rehti|rahti|rehte) (ho|hain)', '(تم|آپ) (کہاں|کدھر) (رہتی|رہتے) (ہو|ہیں)'], [('Main aap ke phone ke andar rehti hoon.', 'میں آپ کے فون کے اندر رہتی ہوں۔')]),
    ('kis_ne_banaya', ['(tumhe|tumhen|aap ko) (kis|kisne|kis ne) (banaya|bnaya) (hai)?', '(تمہیں|آپ کو) (کس|کسنے|کس نے) (بنایا) [ہے]'], [('Mujhe aap ke liye code likh kar banaya gaya hai.', 'مجھے آپ کے لیے کوڈ لکھ کر بنایا گیا ہے۔')]),
    ('insaan', ['(kya )?(tum|aap) (insaan|insan|robot|machine|larki|aurat) (ho|hain)', '(کیا )?(تم|آپ) (انسان|روبوٹ|مشین|لڑکی) (ہو|ہیں)'], [('Main insaan nahi, ek program hoon. Lekin aap ki baat dhyan se sunti hoon.', 'میں انسان نہیں، ایک پروگرام ہوں۔ لیکن آپ کی بات دھیان سے سنتی ہوں۔')]),
    ('naam', ['(tumhara|tera|aap ka|apka) (naam|name) (kya|kia) (hai)', '(تمہارا|تیرا|آپ کا) (نام) (کیا) (ہے)'], [('Mera naam Munshi hai.', 'میرا نام منشی ہے۔')]),
    ('gana', ['(gana|gaana|song) (sunao|gao|suna do)', '(گانا) (سناؤ|گاؤ|سنا دو)'], [('Gana to mujhe nahi aata, lekin lateefa suna sakti hoon.', 'گانا تو مجھے نہیں آتا، لیکن لطیفہ سنا سکتی ہوں۔')]),
    ('kahani', ['(koi )?(kahani|kahaani|story|qissa) (sunao|suna do|batao)', '[کوئی ](کہانی|قصہ) (سناؤ|سنا دو|بتاؤ)'],
     [('Ek pyasa kawwa tha. Ghare mein pani kam tha, to us ne kankar daal daal kar pani upar kar liya aur pi liya. Sabaq: himmat aur aqal se har mushkil hal hoti hai.', 'ایک پیاسا کوا تھا۔ گھڑے میں پانی کم تھا، تو اس نے کنکر ڈال ڈال کر پانی اوپر کر لیا اور پی لیا۔ سبق: ہمت اور عقل سے ہر مشکل حل ہوتی ہے۔'),
      ('Ek kachhua aur khargosh daur mein the. Khargosh tez tha magar raste mein so gaya, aur kachhua aahista aahista chal kar jeet gaya. Sabaq: lagataar mehnat jeet-ti hai.', 'ایک کچھوا اور خرگوش دوڑ میں تھے۔ خرگوش تیز تھا مگر راستے میں سو گیا، اور کچھوا آہستہ آہستہ چل کر جیت گیا۔ سبق: لگاتار محنت جیتتی ہے۔')]),
    ('naseehat', ['(koi )?(naseehat|nasihat|achi baat|acchi baat|advice) (karo|batao|do|sunao)', '[کوئی ](نصیحت|اچھی بات) (کرو|بتاؤ|دو|سناؤ)'],
     [('Qatra qatra darya banta hai. Thori thori bachat hi bari raqam banti hai.', 'قطرہ قطرہ دریا بنتا ہے۔ تھوڑی تھوڑی بچت ہی بڑی رقم بنتی ہے۔'), ('Jitni chadar ho, utne hi paon phailane chahiyein.', 'جتنی چادر ہو، اتنے ہی پاؤں پھیلانے چاہییں۔'), ('Sabr ka phal meetha hota hai.', 'صبر کا پھل میٹھا ہوتا ہے۔'), ('Udhar dena ho to likh kar dein, rishte bhi bachte hain aur paise bhi.', 'ادھار دینا ہو تو لکھ کر دیں، رشتے بھی بچتے ہیں اور پیسے بھی۔')]),
    ('dua', ['(mere liye )?(dua|duaa) (karo|kijiye|karna)', '(میرے لیے )?(دعا) (کرو|کیجیے|کرنا)'], [('Allah aap ke liye aasaniyan paida kare, aur rizq mein barkat de. Ameen.', 'اللہ آپ کے لیے آسانیاں پیدا کرے، اور رزق میں برکت دے۔ آمین۔')]),
    ('mashallah', ['(mashallah|masha allah|subhanallah|alhamdulillah|inshallah)', '(ماشاءاللہ|ماشاللہ|سبحان اللہ|انشاءاللہ|ان شاء اللہ)'], [('Ji, beshak.', 'جی، بے شک۔')]),
    ('maafi', ['(sorry|maaf karna|maaf kijiye|maafi)[ yaar| ji]', '(معاف کرنا|معاف کیجیے|معافی|سوری)[ یار| جی]'], [('Koi baat nahi.', 'کوئی بات نہیں۔')]),
    ('ghar_aaya', ['(main|mein) (ghar|gar) (aa gaya|a gaya|pohanch gaya|aa gayi) (hoon|hun)', '(میں) (گھر) (آ گیا|ا گیا|پہنچ گیا|آ گئی) (ہوں)'], [('Khush aamdeed! Ab thora aaram kijiye.', 'خوش آمدید! اب تھوڑا آرام کیجیے۔')]),
    ('dukaan_ja', ['(main|mein) (dukaan|dukan|shop|kaam|office|duty) (par|pe|ja raha|ja rahi) (ja raha|hoon|hun)?', '(میں) (دکان|کام|آفس|ڈیوٹی) (پر|پہ)? ?(جا رہا|جا رہی) (ہوں)'], [('Theek hai, Allah kare din acha guzre aur kaam mein barkat ho.', 'ٹھیک ہے، اللہ کرے دن اچھا گزرے اور کام میں برکت ہو۔')]),
    ('khabar', ['(koi )?(khabar|news|taza khabar) (sunao|batao|kya hai)', '[کوئی ](خبر|نیوز|تازہ خبر) (سناؤ|بتاؤ|کیا ہے)'], [('Khabrein mere paas nahi aatin, internet se nahi juri hoon.', 'خبریں میرے پاس نہیں آتیں، انٹرنیٹ سے نہیں جڑی ہوں۔')]),
    ('cricket', ['(match|cricket) (kaun|kon) (jeeta|jita)', '(match|cricket) (ka|ki) (score|khabar) (kya|batao)', '(میچ|کرکٹ) (کون) (جیتا)', '(میچ|کرکٹ) (کا) (اسکور|خبر) (کیا|بتاؤ)'], [('Match ka haal mujhe nahi pata, mere paas khabrein nahi aatin.', 'میچ کا حال مجھے نہیں پتا، میرے پاس خبریں نہیں آتیں۔')]),
    ('samajh_nahi', ['(tumhe|tumhen) (samajh|samaj) (nahi|nai) (aati|ati|aa rahi|a rahi)', '(meri )?(baat|bat) (samjho|samajh lo|suno)', '(تمہیں) (سمجھ) (نہیں) (آتی|اتی|آ رہی|ا رہی)', '[میری ](بات) (سمجھو|سمجھ لو|سنو)'],
     [('Maaf kijiye, main abhi seekh rahi hoon. Chhote jumle mein bata dein, main dhyan se sunti hoon.', 'معاف کیجیے، میں ابھی سیکھ رہی ہوں۔ چھوٹے جملے میں بتا دیں، میں دھیان سے سنتی ہوں۔')]),
    ('chup', ['(chup|chupp) (karo|ho jao|raho)', '(bas|bus) (karo|kro)', '(چپ) (کرو|ہو جاؤ|رہو)', '(بس) (کرو)'], [('Theek hai.', 'ٹھیک ہے۔')]),
    ('islam', ['(kalma|kalima) (sunao|parho|batao)', '(کلمہ) (سناؤ|پڑھو|بتاؤ)'], [('La ilaha illallah, Muhammadur Rasulullah.', 'لا الہ الا اللہ، محمد رسول اللہ۔')]),
    ('quaid', ['(quaid e azam|quaid-e-azam|qaid e azam|jinnah) (kaun|kon) (the|thay|tha)', '(pakistan|pakistaan) (kis ne|kisne) (banaya)', '(قائد اعظم|جناح) (کون) (تھے)', '(پاکستان) (کس نے|کسنے) (بنایا)'],
     [('Quaid-e-Azam Muhammad Ali Jinnah Pakistan ke bani hain. Pakistan 14 August 1947 ko bana.', 'قائد اعظم محمد علی جناح پاکستان کے بانی ہیں۔ پاکستان 14 اگست 1947 کو بنا۔')]),
    ('iqbal', ['(allama )?(iqbal|iqbaal) (kaun|kon) (the|thay)', '(قومی) (شاعر) (کون) (ہیں|ہے)', '(علامہ )?(اقبال) (کون) (تھے)'], [('Allama Muhammad Iqbal Pakistan ke qaumi shayar hain. Unhon ne Pakistan ka khwab dekha tha.', 'علامہ محمد اقبال پاکستان کے قومی شاعر ہیں۔ انہوں نے پاکستان کا خواب دیکھا تھا۔')]),
    ('capital', ['(pakistan|pakistaan) (ka|ki) (capital|darul hukumat|dar ul hukumat|rajdhani) (kya|kaun sa|konsa) (hai)', '(پاکستان) (کا) (دارالحکومت|کیپیٹل) (کیا|کون سا) (ہے)'], [('Pakistan ka dar-ul-hukumat Islamabad hai.', 'پاکستان کا دارالحکومت اسلام آباد ہے۔')]),
    ('bara_shehar', ['(pakistan|pakistaan) (ka )?(sab se|sabse) (bara|bada) (shehar|sheher|city) (kaun sa|konsa|kya) (hai)', '(پاکستان) [کا ](سب سے) (بڑا) (شہر) (کون سا|کونسا|کیا) (ہے)'], [('Pakistan ka sab se bara shehar Karachi hai.', 'پاکستان کا سب سے بڑا شہر کراچی ہے۔')]),
    ('bara_mulk', ['(duniya|dunya) (ka )?(sab se|sabse) (bara|bada) (mulk|country) (kaun sa|konsa|kya) (hai)', '(دنیا) [کا ](سب سے) (بڑا) (ملک) (کون سا|کونسا|کیا) (ہے)'], [('Raqbe ke hisaab se duniya ka sab se bara mulk Roos hai, aur aabadi ke hisaab se Bharat.', 'رقبے کے حساب سے دنیا کا سب سے بڑا ملک روس ہے، اور آبادی کے حساب سے بھارت۔')]),
    ('uncha_pahar', ['(duniya|dunya|pakistan) (ka )?(sab se|sabse) (uncha|ooncha|bara) (pahar|pahaar|mountain) (kaun sa|konsa|kya) (hai)', '(دنیا|پاکستان) [کا ](سب سے) (اونچا|بڑا) (پہاڑ) (کون سا|کونسا|کیا) (ہے)'], [('Duniya ka sab se ooncha pahar Mount Everest hai, aur Pakistan ka K2.', 'دنیا کا سب سے اونچا پہاڑ ماؤنٹ ایورسٹ ہے، اور پاکستان کا کے ٹو۔')]),
    ('lamba_darya', ['(duniya|dunya|pakistan) (ka )?(sab se|sabse) (lamba|bara) (darya|dariya|river) (kaun sa|konsa|kya) (hai)', '(دنیا|پاکستان) [کا ](سب سے) (لمبا|بڑا) (دریا) (کون سا|کونسا|کیا) (ہے)'], [('Duniya ka sab se lamba darya Neel hai, aur Pakistan ka Sindh.', 'دنیا کا سب سے لمبا دریا نیل ہے، اور پاکستان کا سندھ۔')]),
    ('qaumi', ['(pakistan ka )?(qaumi|quami) (khel|game) (kya|kaun sa) (hai)', '(پاکستان کا )?(قومی) (کھیل) (کیا|کون سا) (ہے)'], [('Pakistan ka qaumi khel hockey hai.', 'پاکستان کا قومی کھیل ہاکی ہے۔')]),
    ('qaumi_zaban', ['(pakistan ki )?(qaumi|quami) (zaban|zabaan|language) (kya|kaun si) (hai)', '(پاکستان کی )?(قومی) (زبان) (کیا|کون سی) (ہے)'], [('Pakistan ki qaumi zabaan Urdu hai.', 'پاکستان کی قومی زبان اردو ہے۔')]),
    ('suraj', ['(suraj|sooraj|sun) (kahan|kidhar) (se )?(nikalta|nikalta hai|ugta)', '(سورج) (کہاں|کدھر) [سے ](نکلتا|طلوع ہوتا) [ہے]'], [('Suraj mashriq se nikalta hai aur maghrib mein doobta hai.', 'سورج مشرق سے نکلتا ہے اور مغرب میں ڈوبتا ہے۔')]),
    ('din_hafte', ['(hafte|haftay|week) (mein|me) (kitne|kitnay) (din|days) (hote|hotay) (hain)', '(ہفتے) (میں) (کتنے) (دن) (ہوتے) (ہیں)'], [('Ek hafte mein 7 din hote hain.', 'ایک ہفتے میں 7 دن ہوتے ہیں۔')]),
    ('mahine_saal', ['(saal|year) (mein|me) (kitne|kitnay) (mahine|maheene|months) (hote|hotay) (hain)', '(سال) (میں) (کتنے) (مہینے) (ہوتے) (ہیں)'], [('Ek saal mein 12 mahine hote hain.', 'ایک سال میں 12 مہینے ہوتے ہیں۔')]),
    ('din_saal', ['(saal|year) (mein|me) (kitne|kitnay) (din|days) (hote|hotay) (hain)', '(سال) (میں) (کتنے) (دن) (ہوتے) (ہیں)'], [('Ek saal mein 365 din hote hain, aur leap ke saal mein 366.', 'ایک سال میں 365 دن ہوتے ہیں، اور لیپ کے سال میں 366۔')]),
    ('kilo_gram', ['(kilo|kg) (mein|me) (kitne|kitnay) (gram|garam) (hote|hotay) (hain)', '(کلو) (میں) (کتنے) (گرام) (ہوتے) (ہیں)'], [('Ek kilo mein 1000 gram hote hain.', 'ایک کلو میں 1000 گرام ہوتے ہیں۔')]),
    ('mann', ['(ek )?(mann|man|maund) (mein|me) (kitne|kitnay) (kilo|kg) (hote|hotay) (hain)', '(ایک )?(من) (میں) (کتنے) (کلو) (ہوتے) (ہیں)'], [('Ek mann taqreeban 40 kilo ka hota hai.', 'ایک من تقریباً 40 کلو کا ہوتا ہے۔')]),
    ('tola', ['(ek )?(tola|tole) (mein|me|kitne) (kitne )?(gram|garam) (ka|hote|hota) (hai|hain)?', '(ایک )?(تولہ|تولے) (میں|کتنے) [کتنے ](گرام) (کا|ہوتے|ہوتا) [ہے|ہیں]'], [('Ek tola taqreeban 11.66 gram ka hota hai.', 'ایک تولہ تقریباً 11.66 گرام کا ہوتا ہے۔')]),
    ('marla', ['(ek )?(marla|marlay|marle) (mein|me|kitne) (kitne )?(foot|feet|square feet|fut) (ka|hote|hota) (hai|hain)?', '(kanal) (mein|me) (kitne) (marle|marlay) (hote) (hain)', '(ایک )?(مرلہ|مرلے) (میں|کتنے) [کتنے ](فٹ|اسکوائر فٹ) (کا|ہوتے|ہوتا) [ہے|ہیں]', '(کنال) (میں) (کتنے) (مرلے) (ہوتے) (ہیں)'],
     [('Aam taur par ek marla 225 ya 272 square foot ka hota hai, ilaqe ke hisaab se. Ek kanal mein 20 marle hote hain.', 'عام طور پر ایک مرلہ 225 یا 272 اسکوائر فٹ کا ہوتا ہے، علاقے کے حساب سے۔ ایک کنال میں 20 مرلے ہوتے ہیں۔')]),
    ('darjan', ['(ek )?(darjan|dozen|darzan) (mein|me) (kitne|kitni) (hote|hoti) (hain)', '(ایک )?(درجن) (میں) (کتنے|کتنی) (ہوتے|ہوتی) (ہیں)'], [('Ek darjan mein 12 hote hain.', 'ایک درجن میں 12 ہوتے ہیں۔')]),
    ('eent_hazaar', ['(ek )?(hazaar|hazar|1000) (eent|eenten) (ka|ki) (truck|trolley|trali) (hota|hoti) (hai)', '(eent|eenton) (ka|ki) (rate|bhao|qeemat) (kya|kitna) (hai)', '(اینٹ|اینٹوں) (کا|کی) (ریٹ|بھاؤ|قیمت) (کیا|کتنا) (ہے)'],
     [('Eent ka rate shehar aur bhatte ke hisaab se badalta rehta hai, mere paas aaj ka rate nahi. Aap rate bata dein to hisaab main laga deti hoon.', 'اینٹ کا ریٹ شہر اور بھٹے کے حساب سے بدلتا رہتا ہے، میرے پاس آج کا ریٹ نہیں۔ آپ ریٹ بتا دیں تو حساب میں لگا دیتی ہوں۔')]),
    ('rate_q', ['(petrol|sona|gold|dollar|cheeni|atta|ata) (ka|ki) (rate|bhao|qeemat|price) (kya|kitna|kitni) (hai)', '(پٹرول|پیٹرول|سونا|ڈالر|چینی|آٹا) (کا|کی) (ریٹ|بھاؤ|قیمت) (کیا|کتنا|کتنی) (ہے)'],
     [('Aaj ka rate mere paas nahi aata, kyun ke main internet se khabrein nahi leti.', 'آج کا ریٹ میرے پاس نہیں آتا، کیوں کہ میں انٹرنیٹ سے خبریں نہیں لیتی۔')]),
    ('zakat', ['(zakat|zakaat) (kitni|kaise|kitna) (hoti|nikalte|nikalti|deni) (hai)?', '(زکوٰۃ|زکات|زکوۃ) (کتنی|کیسے|کتنا) (ہوتی|نکالتے|دینی) [ہے]'],
     [('Zakat bachat ka dhai feesad hoti hai, jab woh saal bhar nisaab se zyada rahe. Pakki maloomat ke liye kisi aalim se pooch lein.', 'زکوٰۃ بچت کا ڈھائی فیصد ہوتی ہے، جب وہ سال بھر نصاب سے زیادہ رہے۔ پکی معلومات کے لیے کسی عالم سے پوچھ لیں۔')]),
    ('hisaab_kyun', ['(hisaab|hisab) (kyun|kyu|kiyon) (rakhna|rakhen) (chahiye|zaroori hai)', '(حساب) (کیوں) (رکھنا|رکھیں) (چاہیے|ضروری ہے)'],
     [('Hisaab rakhne se pata rehta hai ke paisa kahan ja raha hai, udhar yaad rehta hai, aur bachat aasan ho jati hai.', 'حساب رکھنے سے پتا رہتا ہے کہ پیسہ کہاں جا رہا ہے، ادھار یاد رہتا ہے، اور بچت آسان ہو جاتی ہے۔')]),
    ('kaise_likhoon', ['(kharcha|kharch|udhar|khata|stock) (kaise|kese) (likhwaun|likhwaoon|likhoon|batauon|batau)', '(tum ko|tumhe) (kaise|kese) (batauon|bataun) (kharcha|hisaab)', '(خرچہ|ادھار|کھاتہ|اسٹاک) (کیسے) (لکھواؤں|بتاؤں)'],
     [('Bas aise bolein: 500 ka petrol dala, ya Ahmed ke naam 1000 likh do, ya Ashraf ne 200 bori cement dali hai stock mein likh do.', 'بس ایسے بولیں: 500 کا پیٹرول ڈالا، یا احمد کے نام 1000 لکھ دو، یا اشرف نے 200 بوری سیمنٹ ڈالی ہے اسٹاک میں لکھ دو۔')]),
]

def main():
    intents, examples, seen = [], [], set()
    for command, patterns in HISAAB:
        idx = len(intents)
        intents.append({'id': 'h:' + command, 'cmd': command})
        for p in patterns:
            for e in expand(p):
                if (e, idx) in seen: continue
                seen.add((e, idx)); examples.append([e, idx])
    for command, patterns in SLOTS:
        idx = len(intents)
        intents.append({'id': 's:' + command, 'cmd': command, 'slots': 1})
        for p in patterns:
            for e in expand(p):
                if (e, idx) in seen: continue
                seen.add((e, idx)); examples.append([e, idx])
    for iid, patterns, replies in CHAT:
        idx = len(intents)
        intents.append({'id': 'c:' + iid, 'replies': [list(r) for r in replies]})
        for p in patterns:
            for e in expand(p):
                if (e, idx) in seen: continue
                seen.add((e, idx)); examples.append([e, idx])
    data = {'v': 1, 'intents': intents, 'examples': examples}
    text = json.dumps(data, ensure_ascii=False, separators=(',', ':'))
    with open(os.path.join(HERE, 'bank.json'), 'w', encoding='utf-8') as f: f.write(text)
    with open(os.path.join(HERE, 'bank.js'), 'w', encoding='utf-8') as f:
        f.write('// Generated by data/make_bank.py. Do not edit by hand.\nvar MUNSHI_BANK = ' + text + ';\n')
    print(len(intents), 'intents,', len(examples), 'phrasings,', round(len(text.encode('utf-8')) / 1024), 'KB')

if __name__ == '__main__':
    main()
