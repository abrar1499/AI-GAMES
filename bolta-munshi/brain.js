/*
 * Bolta Munshi - the "brain".
 *
 * Pure logic, no browser APIs: takes one spoken/typed sentence plus the saved
 * data, updates the data and returns what to say back. Understands Roman Urdu,
 * Urdu script and Devanagari (the script the speech recogniser returns depends
 * on the chosen voice mode), so every word list below carries all three.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Munshi = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // ---------------------------------------------------------------- text ---

  var DIGITS = { '۰': 0, '۱': 1, '۲': 2, '۳': 3, '۴': 4, '۵': 5, '۶': 6, '۷': 7, '۸': 8, '۹': 9,
                 '٠': 0, '١': 1, '٢': 2, '٣': 3, '٤': 4, '٥': 5, '٦': 6, '٧': 7, '٨': 8, '٩': 9,
                 '०': 0, '१': 1, '२': 2, '३': 3, '४': 4, '५': 5, '६': 6, '७': 7, '८': 8, '९': 9 };

  /** Fold spelling variants so that the same word always compares equal. */
  function normalise(text) {
    var s = String(text == null ? '' : text).normalize('NFC').toLowerCase();
    s = s.replace(/[​-‏‪-‮﻿]/g, '');   // invisible marks
    s = s.replace(/[ً-ْٰـ]/g, '');          // Urdu zer/zabar, tatweel
    s = s.replace(/[يى]/g, 'ی')                  // Arabic yeh  -> Urdu yeh
         .replace(/ك/g, 'ک')                          // Arabic kaf  -> Urdu kaf
         .replace(/[هە]/g, 'ہ');                 // Arabic heh  -> Urdu heh
    s = s.replace(/़/g, '').replace(/ँ/g, 'ं');  // nukta, chandrabindu
    s = s.replace(/[۰-۹٠-٩०-९]/g, function (d) { return DIGITS[d]; });
    return s;
  }

  // ------------------------------------------------------------- lexicon ---

  // canonical word -> every spelling we accept for it
  var WORDS = {
    // money
    cur: 'rupay rupaye rupey rupee rupees rupiya rupya rupae rupe rs pkr روپے روپیہ روپیے روپئے روپے रुपये रुपए रुपया रुपय',
    paise: 'paise paisa paisay pese pesa پیسے پیسہ पैसे पैसा',
    kharch: 'kharcha kharch kharche kharchay kharchy kharach kharchi expense expenses spent spend خرچ خرچہ خرچا خرچے خرچہ खर्च खर्चा खर्चे',
    spendv: 'lagaya lagaye lagaaya laga lage lagay lagi dala dale dali daala dalwaya khareeda khareedi khareede kharida kharidi kharide bhara bhari bharwaya paid bought لگایا لگائے لگا لگے لگی ڈالا ڈالے ڈالی ڈلوایا خریدا خریدی خریدے بھرا بھری بھروایا लगाया लगाए लगा लगे लगी डाला डाले डाली डलवाया खरीदा खरीदी खरीदे भरा भरी भरवाया',
    gave: 'diya diye diyay dia diay دیا دیے دیئے دئے दिया दिए दिये',
    took: 'liya liye liyay lia li لیا لیے لئے لی लिया लिए लिये ली',
    incomev: 'mili mila mile milay milli aaye aaya kamaya kamaye kamai received earned ملی ملا ملے آئے آیا کمایا کمائے کمائی मिली मिला मिले आए आया कमाया कमाए कमाई',
    incomen: 'aamdani amdani tankhwah tankha tankhah tankhwa salary income آمدنی تنخواہ سیلری आमदनी तनख्वाह सैलरी',
    loan: 'udhar udhaar qarz qarza karz karza loan ادھار قرض قرضہ उधार कर्ज कर्जा',
    wapas: 'wapas wapis wapsi واپس वापस',
    bachat: 'bachat bacha bache bachay baqi baaqi balance بچت بچا بچے باقی بیلنس बचत बचा बचे बाकी बैलेंस',
    hisaab: 'hisaab hisab hesab حساب हिसाब',
    zyada: 'zyada ziada ziyada zyadah زیادہ ज्यादा',
    detail: 'tafseel tafsil detail details breakdown تفصیل ڈیٹیل तफसील डिटेल',
    last: 'aakhri akhri aakhiri akhiri latest آخری आखिरी',
    // questions
    kitna: 'kitna kitne kitni kitnay کتنا کتنے کتنی कितना कितने कितनी',
    kya: 'kya क्या what',
    kia: 'kia کیا',            // "what" or "did" - decided from position
    kab: 'kab کب कब when',
    kahan: 'kahan kahaan kidhar کہاں कहां where',
    kaun: 'kaun kon kaunsa konsa kaunsi konsi کون کونسا کونسی कौन कौनसा कौनसी who which',
    kis: 'kis کس किस',
    batao: 'batao bataao btao bata bataiye batayen bataen batana بتاؤ بتاو بتا بتائیں بتائیے بتانا बताओ बता बताइए बताएं बताना tell',
    kaise: 'kaise kese kaisay kaisa kaisi کیسے کیسا کیسی कैसे कैसा कैसी how',
    // remembering and removing
    yaad: 'yaad yad یاد याद remember',
    rakh: 'rakho rakhna rakh rakhen rakhein rakhiye rakhlo رکھو رکھنا رکھ رکھیں رکھیے रखो रखना रख रखें रखिए',
    note: 'note نوٹ नोट',
    likh: 'likh likho likhlo likhna لکھ لکھو لکھنا लिख लिखो लिखना',
    save: 'save mehfooz سیو محفوظ सेव',
    del: 'mita mitao mitado hata hatao hatado delete cancel undo مٹا مٹاؤ مٹاو ہٹا ہٹاؤ ہٹاو ڈیلیٹ کینسل मिटा मिटाओ हटा हटाओ डिलीट कैंसिल',
    ghalat: 'ghalat galat ghalti غلط غلطی गलत गलती wrong',
    // time
    aaj: 'aaj aj today آج आज',
    kal: 'kal yesterday کل कल',
    parso: 'parso parson parsoon پرسوں परसों',
    hafta: 'hafta hafte haftay hafty week ہفتہ ہفتے हफ्ता हफ्ते',
    mahina: 'mahina mahine mahinay maheena maheene maheenay month مہینہ مہینے مہینا महीना महीने',
    saal: 'saal year سال साल',
    is: 'is this اس इस',
    pichla: 'pichle pichhle pichla pichli pichlay guzishta guzre guzray پچھلے پچھلا پچھلی گزشتہ گزرے पिछले पिछला पिछली गुजरे',
    ab: 'ab اب अब',
    tak: 'tak تک तक',
    total: 'total tootal kul sara saara sare saare ٹوٹل سارا سارے टोटल कुल सारा सारे',
    tareekh: 'tareekh tarikh date تاریخ तारीख',
    waqt: 'waqt time baje bajay waqat وقت ٹائم بجے वक्त टाइम बजे',
    // small talk
    salam: 'salam salaam assalam assalamualaikum assalamoalaikum hello hi hey سلام السلام ہیلو सलाम नमस्ते हेलो हैलो',
    shukriya: 'shukriya shukria thanks thank meharbani شکریہ مہربانی शुक्रिया धन्यवाद',
    call: 'call کال कॉल',
    band: 'band khatam kaat بند ختم کاٹ बंद खत्म काट',
    sakte: 'sakte sakta sakti سکتے سکتا سکتی सकते सकता सकती',
    help: 'help madad مدد मदद',
    tum: 'tum tu aap ap تم تو آپ तुम तू आप',
    // sentence glue
    hai: 'hai hain he hy hoon hun ho ہے ہیں ہوں ہو है हैं हूं हो',
    tha: 'tha thi the thay hoga hogi honge تھا تھی تھے ہوگا ہوگی था थी थे होगा होगी',
    hua: 'hua hui hue huay huwa ہوا ہوئی ہوئے हुआ हुई हुए',
    ko: 'ko کو को',
    se: 'se say سے से',
    ne: 'ne nay نے ने',
    par: 'par pe per pay پر پہ पर पे',
    ka: 'ka ki ke kay کا کی کے का की के',
    aur: 'aur or and اور और',
    keh: 'keh kay کہ कि that',
    // answers, teaching and housekeeping
    yes: 'han haan haa ji jee g yes bilkul zaroor theek thik ok okay sahi acha achha ہاں جی بالکل ضرور ٹھیک صحیح اچھا हां जी बिल्कुल जरूर ठीक सही अच्छा',
    no: 'nahi nahin nai no mat نہیں مت नहीं मत',
    teach: 'gino ginna ginein shamil samjho samjhein گنو گنیں شامل سمجھو سمجھیں गिनो गिनें शामिल समझो समझें',
    matlab: 'matlab means مطلب मतलब',
    naam: 'naam name نام नाम',
    khuda: 'khuda allah خدا اللہ खुदा अल्लाह',
    backup: 'backup بیک بیکاپ बैकअप',
    lao: 'lao laao restore bahal لاؤ لاو بحال लाओ बहाल',
    stop: 'mein main me men mai mene maine meine bhi to tou ye yeh wo woh mera meri mere mujhe mujhay mujh hum hamara hamari tumhe tumhen apna apni apne na kar karo kardo karna karen kiya kiye kiyay kardiya de dein den dena dedo lo len lena lelo gaya gayi gaye raha rahi rahe wala wali wale liye abhi phir zara please plz bhai yaar kuch koi sab bohat bahut boht bara bari chota choti naya nayi sahab sahib saab jaan sirf taqreeban kareeb lagbhag ek aik the a an of on in for my i it was ' +
          'میں نے بھی تو یہ وہ میرا میری میرے مجھے مجھ ہم ہمارا ہماری تمہیں اپنا اپنی اپنے نہ کر کرو کرنا کریں کیے دے دیں دینا لو لیں لینا گیا گئی گئے رہا رہی رہے والا والی والے ابھی پھر ذرا بھائی یار کچھ کوئی سب بہت بڑا بڑی چھوٹا چھوٹی نیا نئی صاحب جان صرف تقریبا قریب ' +
          'में मैं मैंने भी तो ये यह वो वह मेरा मेरी मेरे मुझे मुझ हम हमारा हमारी तुम्हें अपना अपनी अपने न कर करो करना करें किया किए दे दें देना लो लें लेना गया गई गए रहा रही रहे वाला वाली वाले अभी फिर जरा भाई यार कुछ कोई सब बहुत बड़ा बड़ी छोटा छोटी नया नई साहब जान सिर्फ करीब लगभग'
  };

  // category id -> spoken labels and the words that point to it
  var CATS = {
    petrol: { ro: 'petrol', ur: 'پیٹرول', hi: 'पेट्रोल',
      w: 'petrol patrol diesel fuel cng پیٹرول پٹرول ڈیزل فیول पेट्रोल डीजल फ्यूल' },
    khana: { ro: 'khanay', ur: 'کھانے', hi: 'खाने',
      w: 'khana khane khanay khaana roti nashta nashte nashtay chai chaye chae biryani hotel restaurant lunch dinner food pizza burger samosa samose کھانا کھانے روٹی ناشتہ ناشتے چائے بریانی ہوٹل ریسٹورنٹ لنچ ڈنر پیزا برگر سموسے खाना खाने रोटी नाश्ता नाश्ते चाय बिरयानी होटल रेस्टोरेंट लंच डिनर पिज्जा बर्गर समोसे' },
    rashan: { ro: 'rashan', ur: 'راشن', hi: 'राशन',
      w: 'rashan ration sauda saudah sabzi sabziyan doodh dudh atta aata cheeni chawal daal gosht chicken murghi anday ande phal fruit grocery groceries kiryana kiryane سودا راشن سبزی سبزیاں دودھ آٹا چینی چاول دال گوشت چکن مرغی انڈے پھل فروٹ گروسری کریانہ सौदा राशन सब्जी दूध आटा चीनी चावल दाल गोश्त चिकन मुर्गी अंडे फल फ्रूट ग्रोसरी किराना' },
    kiraya: { ro: 'kiraye', ur: 'کرائے', hi: 'किराए',
      w: 'kiraya kiraye kiraaya kiraay rent کرایہ کرائے رینٹ किराया किराए रेंट' },
    safar: { ro: 'safar', ur: 'سفر', hi: 'सफर',
      w: 'safar rickshaw riksha rikshe bus taxi careem uber bykea indrive ticket sawari savari سفر رکشہ رکشے بس ٹیکسی کریم اوبر بائیکیا ٹکٹ سواری सफर रिक्शा रिक्शे बस टैक्सी टिकट सवारी' },
    bill: { ro: 'bill', ur: 'بل', hi: 'बिल',
      w: 'bill bills bijli gas pani paani internet wifi بل بجلی گیس پانی انٹرنیٹ बिल बिजली गैस पानी इंटरनेट' },
    mobile: { ro: 'mobile', ur: 'موبائل', hi: 'मोबाइल',
      w: 'mobile load easyload package recharge sim موبائل لوڈ پیکج ریچارج سم मोबाइल लोड पैकेज रिचार्ज सिम' },
    ilaj: { ro: 'ilaj', ur: 'علاج', hi: 'इलाज',
      w: 'ilaj ilaaj dawa dawai dawaiyan dawaiyon doctor hospital haspatal clinic medicine medical علاج دوا دوائی دوائیاں ڈاکٹر ہسپتال اسپتال کلینک میڈیسن इलाज दवा दवाई दवाइयां डॉक्टर अस्पताल हॉस्पिटल क्लिनिक' },
    kapre: { ro: 'kapron', ur: 'کپڑوں', hi: 'कपड़ों',
      w: 'kapre kapray kapde kapra kapron joote jootay juta shoes shirt suit darzi کپڑے کپڑا کپڑوں جوتے جوتا شرٹ سوٹ درزی कपड़े कपड़ा कपड़ों जूते जूता शर्ट सूट दर्जी' },
    parhai: { ro: 'parhai', ur: 'پڑھائی', hi: 'पढ़ाई',
      w: 'parhai fees fee school college tuition kitab kitaben kitabon books university پڑھائی فیس اسکول سکول کالج ٹیوشن کتاب کتابیں یونیورسٹی पढ़ाई फीस स्कूल कॉलेज ट्यूशन किताब किताबें' },
    gaari: { ro: 'gaari ki marammat', ur: 'گاڑی کی مرمت', hi: 'गाड़ी की मरम्मत',
      w: 'mechanic mistri puncture tyre tire marammat repair مکینک مستری پنکچر ٹائر مرمت मैकेनिक मिस्त्री पंक्चर टायर मरम्मत' }
  };

  // number words; a trailing "?" marks words that are only numbers when a
  // unit or "rupay" follows them (e.g. "do" is also "give", "ek" is also "a").
  var NUMS = {
    0: 'sifar zero صفر शून्य', 1: 'ek? aik? ایک? एक?', 2: 'do? دو? दो?', 3: 'teen tin تین तीन', 4: 'char chaar چار चार',
    5: 'panch paanch پانچ पांच', 6: 'chhe che cheh chhay چھ چھے छह छे छः', 7: 'saat سات सात', 8: 'aath ath آٹھ आठ',
    9: 'nau نو नौ', 10: 'das dus دس दस', 11: 'gyarah gyara giyara giyarah گیارہ ग्यारह', 12: 'barah baara بارہ बारह',
    13: 'terah tairah تیرہ तेरह', 14: 'chaudah choda chauda chodah چودہ चौदह', 15: 'pandrah pandra pandarah پندرہ पंद्रह',
    16: 'solah sola سولہ सोलह', 17: 'satrah satra سترہ सत्रह', 18: 'atharah athara atthara اٹھارہ अठारह',
    19: 'unnees unnis اونیس انیس उन्नीस', 20: 'bees bis بیس बीस', 21: 'ikkees ikkis اکیس इक्कीस', 22: 'baees bais بائیس बाईस',
    23: 'teis taees تئیس तेईस', 24: 'chaubees chaubis chobees چوبیس चौबीस', 25: 'pachees pachis pacchis پچیس पच्चीस',
    26: 'chhabbees chabbis chabbees چھبیس छब्बीस', 27: 'sattaees sattais ستائیس सत्ताईस', 28: 'atthaees athais atthais اٹھائیس अट्ठाईस',
    29: 'untees untis انتیس उनतीस', 30: 'tees tis تیس तीस', 31: 'ikattees ikattis اکتیس इकतीस', 32: 'battees battis بتیس बत्तीस',
    33: 'taintees taintis تینتیس तैंतीस', 34: 'chauntees chauntis چونتیس चौंतीस', 35: 'paintees paintis پینتیس पैंतीस',
    36: 'chhattees chattis chattees چھتیس छत्तीस', 37: 'saintees saintis سینتیس सैंतीस', 38: 'artees adtis artis اڑتیس अड़तीस',
    39: 'untalees untalis انتالیس उनतालीस', 40: 'chalees chalis چالیس चालीस', 41: 'iktalees iktalis اکتالیس इकतालीस',
    42: 'bayalees bayalis بیالیس बयालीस', 43: 'taintalees taintalis تینتالیس तैंतालीस', 44: 'chawalees chawalis چوالیس चवालीस',
    45: 'paintalees paintalis پینتالیس पैंतालीस', 46: 'chhiyalees chiyalis چھیالیس छियालीस', 47: 'saintalees saintalis سینتالیس सैंतालीस',
    48: 'artalees adtalis artalis اڑتالیس अड़तालीस', 49: 'unchas انچاس उनचास', 50: 'pachas pachaas پچاس पचास',
    51: 'ikyawan ikawan اکیاون इक्यावन', 52: 'bawan باون बावन', 53: 'tirpan ترپن तिरपन', 54: 'chawwan chauwan chawan چون चौवन',
    55: 'pachpan پچپن पचपन', 56: 'chhappan chappan چھپن छप्पन', 57: 'sattawan ستاون सत्तावन', 58: 'atthawan athawan اٹھاون अट्ठावन',
    59: 'unsath انسٹھ उनसठ', 60: 'saath? sath? ساٹھ साठ', 61: 'iksath اکسٹھ इकसठ', 62: 'basath باسٹھ बासठ',
    63: 'tirsath ترسٹھ तिरसठ', 64: 'chaunsath چونسٹھ चौंसठ', 65: 'painsath پینسٹھ पैंसठ', 66: 'chhiyasath chiyasath چھیاسٹھ छियासठ',
    67: 'sarsath سڑسٹھ सड़सठ', 68: 'arsath اڑسٹھ अड़सठ', 69: 'unhattar انہتر उनहत्तर', 70: 'sattar ستر सत्तर',
    71: 'ikhattar اکہتر इकहत्तर', 72: 'bahattar بہتر बहत्तर', 73: 'tihattar تہتر तिहत्तर', 74: 'chauhattar چوہتر चौहत्तर',
    75: 'pachhattar pachattar پچھتر पचहत्तर', 76: 'chhihattar chihattar چھہتر छिहत्तर', 77: 'satattar ستتر सतहत्तर',
    78: 'athattar اٹھہتر अठहत्तर', 79: 'unasi اناسی उनासी', 80: 'assi اسی? अस्सी', 81: 'ikyasi ikasi اکیاسی इक्यासी',
    82: 'bayasi بیاسی बयासी', 83: 'tirasi تراسی तिरासी', 84: 'chaurasi چوراسی चौरासी', 85: 'pachasi پچاسی पचासी',
    86: 'chhiyasi chiyasi چھیاسی छियासी', 87: 'sattasi ستاسی सत्तासी', 88: 'atthasi athasi اٹھاسی अट्ठासी',
    89: 'nawasi نواسی नवासी', 90: 'nawwe nabbe nawe navve نوے नब्बे', 91: 'ikyanwe ikanwe اکیانوے इक्यानवे',
    92: 'banwe بانوے बानवे', 93: 'tiranwe ترانوے तिरानवे', 94: 'chauranwe چورانوے चौरानवे', 95: 'pachanwe پچانوے पचानवे',
    96: 'chhiyanwe chiyanwe چھیانوے छियानवे', 97: 'sattanwe ستانوے सत्तानवे', 98: 'atthanwe athanwe اٹھانوے अट्ठानवे',
    99: 'ninyanwe ninanwe ننانوے निन्यानवे'
  };
  var MULTS = {
    100: 'sau sao soo hundred سو सौ',
    1000: 'hazar hazaar hzar thousand k ہزار हजार',
    100000: 'lakh laakh lac lacs لاکھ लाख',
    10000000: 'crore karor caror کروڑ करोड़'
  };
  var FRACS = { 1.5: 'dedh derh daidh ڈیڑھ डेढ़', 2.5: 'dhai dhaai ڈھائی ढाई' };
  var HALF = 'saadhe sadhe sarhe saarhe ساڑھے साढ़े';

  var LEX = Object.create(null);      // normalised spelling -> canonical word
  var CATOF = Object.create(null);    // normalised spelling -> category id
  var NUMOF = Object.create(null);    // normalised spelling -> {v, kind, shaky}

  function each(str, fn) { str.split(/\s+/).forEach(function (w) { if (w) fn(w); }); }
  Object.keys(WORDS).forEach(function (c) { each(WORDS[c], function (w) { LEX[normalise(w)] = c; }); });
  Object.keys(CATS).forEach(function (id) { each(CATS[id].w, function (w) { CATOF[normalise(w)] = id; }); });
  function addNum(table, kind) {
    Object.keys(table).forEach(function (v) {
      each(table[v], function (w) {
        var shaky = w.slice(-1) === '?';
        NUMOF[normalise(shaky ? w.slice(0, -1) : w)] = { v: Number(v), kind: kind, shaky: shaky };
      });
    });
  }
  addNum(NUMS, 'unit'); addNum(MULTS, 'mult'); addNum(FRACS, 'frac');
  each(HALF, function (w) { NUMOF[normalise(w)] = { v: 0.5, kind: 'half', shaky: false }; });

  // ------------------------------------------------------------ learning ---

  // What this user has taught it, kept inside their saved data (db.learned).
  var LEARN = { cats: {}, alias: {}, kinds: {} };
  function own(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }

  // A misheard or misspelt word may stand in for these, never for anything
  // that deletes, hangs up, or changes what kind of entry is booked.
  var BYE = {};
  ['hafiz', 'bye', 'goodbye', 'حافظ', 'हाफिज', 'बाय'].forEach(function (w) { BYE[normalise(w)] = 1; });
  var FUZZY_OK = { kharch: 1, cur: 1, paise: 1, incomen: 1, loan: 1, bachat: 1, hisaab: 1, zyada: 1, kitna: 1, hafta: 1, mahina: 1, tareekh: 1, shukriya: 1 };
  var FUZZY = Object.create(null);

  /** True when the two words differ by one letter added, dropped, changed or swapped with its neighbour. */
  function oneOff(a, b) {
    if (Math.abs(a.length - b.length) > 1 || a === b) return false;
    if (a.length === b.length) {
      for (var k = 0; k + 1 < a.length; k++) {
        if (a[k] !== b[k]) return (a[k] === b[k + 1] && a[k + 1] === b[k] && a.slice(k + 2) === b.slice(k + 2)) || a.slice(k + 1) === b.slice(k + 1);
      }
    }
    var i = 0, j = 0, edits = 0;
    while (i < a.length && j < b.length) {
      if (a[i] === b[j]) { i++; j++; continue; }
      if (++edits > 1) return false;
      if (a.length > b.length) i++; else if (b.length > a.length) j++; else { i++; j++; }
    }
    return edits + (a.length - i) + (b.length - j) === 1;
  }

  /** Meaning of one word: taught words first, then the word lists, then a near miss. */
  function lookup(n) {
    if (own(LEARN.cats, n)) return { cat: LEARN.cats[n] };
    if (own(LEARN.alias, n)) return { c: LEARN.alias[n].c || undefined, cat: LEARN.alias[n].cat || undefined };
    if (LEX[n] || CATOF[n]) return { c: LEX[n], cat: CATOF[n] };
    if (n.length < 5 || /\d/.test(n) || NUMOF[n]) return {};
    if (!FUZZY[n]) {
      var found = {}, clash = false;
      function consider(key, value) {
        if (key.length < 5 || key[0] !== n[0] || !oneOff(n, key)) return;
        if ((found.c || found.cat) && (found.c !== value.c || found.cat !== value.cat)) clash = true;
        found = value;
      }
      Object.keys(LEX).forEach(function (k) { if (FUZZY_OK[LEX[k]]) consider(k, { c: LEX[k] }); });
      Object.keys(CATOF).forEach(function (k) { consider(k, { cat: CATOF[k] }); });
      FUZZY[n] = clash ? {} : found;
    }
    return FUZZY[n];
  }

  // -------------------------------------------------------------- tokens ---

  /** Split a sentence into word tokens; runs of number words become one number. */
  function tokenise(text) {
    var s = normalise(text).replace(/(\d),(?=\d)/g, '$1');
    var rawParts = String(text == null ? '' : text).normalize('NFC').replace(/(\d),(?=\d)/g, '$1')
      .match(/[\d۰-۹٠-٩०-९]+(?:\.[\d۰-۹٠-٩०-९]+)?|[^\s\d۰-۹٠-٩०-९.,!?؟،۔।;:"'()\-\/]+/g) || [];
    var parts = s.match(/\d+(?:\.\d+)?|[^\s\d.,!?؟،۔।;:"'()\-\/]+/g) || [];
    var words = parts.map(function (n, i) {
      var meaning = lookup(n);
      return { n: n, raw: rawParts.length === parts.length ? rawParts[i] : n,
               c: meaning.c, cat: meaning.cat, digit: /^\d/.test(n) ? parseFloat(n) : null };
    });

    function numInfo(w) {
      if (!w) return null;
      if (w.digit !== null) return { v: w.digit, kind: 'digit', shaky: false };
      return NUMOF[w.n] || null;
    }
    function supports(w) {            // what may follow a shaky number word
      var info = numInfo(w);
      return !!w && ((info && (info.kind === 'mult')) || w.c === 'cur');
    }

    var out = [];
    for (var i = 0; i < words.length; i++) {
      var info = numInfo(words[i]);
      var starts = info && info.kind !== 'mult' && !(info.shaky && !supports(words[i + 1]));
      // a bare "sau"/"hazar" counts as one hundred / one thousand when money follows
      if (info && info.kind === 'mult' && words[i].n !== 'k' && words[i + 1] && words[i + 1].c === 'cur') starts = true;
      if (!starts) { out.push(words[i]); continue; }

      var total = 0, cur = 0, half = false, j = i, used = false;
      for (; j < words.length; j++) {
        var x = numInfo(words[j]);
        if (!x) break;
        if (x.shaky && !supports(words[j + 1])) break;
        if (x.kind === 'half') { if (used) break; half = true; }
        else if (x.kind === 'frac') { if (cur) break; cur = x.v; }
        else if (x.kind === 'mult') {
          if (x.v === 100) cur = (cur || 1) * 100;
          else { total += (cur || 1) * x.v; cur = 0; }
        } else {
          var fits = cur === 0 || (x.kind === 'unit' && cur % 100 === 0 && x.v < 100 && cur >= 100);
          if (!fits) break;
          cur += x.v + (half ? 0.5 : 0); half = false;
        }
        used = true;
      }
      if (j === i) { out.push(words[i]); continue; }
      out.push({ num: true, value: total + cur, n: String(total + cur), raw: String(total + cur) });
      i = j - 1;
    }
    return out;
  }

  function has(tokens, c) { return tokens.some(function (t) { return t.c === c; }); }
  function hasAny(tokens, list) { return list.some(function (c) { return has(tokens, c); }); }
  function firstNum(tokens) { for (var i = 0; i < tokens.length; i++) if (tokens[i].num) return tokens[i].value; return null; }
  function firstCat(tokens) { for (var i = 0; i < tokens.length; i++) if (tokens[i].cat) return tokens[i]; return null; }
  /** Words we have no special meaning for: names, things bought, note keywords. */
  function unknowns(tokens) {
    return tokens.filter(function (t) { return !t.num && !t.c && !t.cat && !NUMOF[t.n]; });
  }

  // --------------------------------------------------------------- dates ---

  function iso(d) {
    return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
  }
  function addDays(d, n) { var x = new Date(d.getFullYear(), d.getMonth(), d.getDate() + n); return x; }

  var PERIODS = {
    today: ['Aaj', 'آج', 'आज'], yesterday: ['Kal', 'کل', 'कल'], daybefore: ['Parson', 'پرسوں', 'परसों'],
    week: ['Is hafte', 'اس ہفتے', 'इस हफ्ते'], lastweek: ['Pichle hafte', 'پچھلے ہفتے', 'पिछले हफ्ते'],
    month: ['Is mahine', 'اس مہینے', 'इस महीने'], lastmonth: ['Pichle mahine', 'پچھلے مہینے', 'पिछले महीने'],
    year: ['Is saal', 'اس سال', 'इस साल'], lastyear: ['Pichle saal', 'پچھلے سال', 'पिछले साल'],
    all: ['Ab tak', 'اب تک', 'अब तक']
  };

  /** Which stretch of time a question is about. Defaults to this month. */
  function periodOf(tokens, now) {
    var prev = has(tokens, 'pichla'), key = 'month';
    var abTak = tokens.some(function (t, i) { return t.c === 'ab' && tokens[i + 1] && tokens[i + 1].c === 'tak'; });
    if (has(tokens, 'aaj')) key = 'today';
    else if (has(tokens, 'parso')) key = 'daybefore';
    else if (has(tokens, 'kal')) key = 'yesterday';
    else if (has(tokens, 'hafta')) key = prev ? 'lastweek' : 'week';
    else if (has(tokens, 'mahina')) key = prev ? 'lastmonth' : 'month';
    else if (has(tokens, 'saal')) key = prev ? 'lastyear' : 'year';
    else if (abTak || has(tokens, 'total')) key = 'all';

    var y = now.getFullYear(), m = now.getMonth(), from, to;
    var monday = addDays(now, -((now.getDay() + 6) % 7));
    switch (key) {
      case 'today': from = to = now; break;
      case 'yesterday': from = to = addDays(now, -1); break;
      case 'daybefore': from = to = addDays(now, -2); break;
      case 'week': from = monday; to = addDays(monday, 6); break;
      case 'lastweek': from = addDays(monday, -7); to = addDays(monday, -1); break;
      case 'month': from = new Date(y, m, 1); to = new Date(y, m + 1, 0); break;
      case 'lastmonth': from = new Date(y, m - 1, 1); to = new Date(y, m, 0); break;
      case 'year': from = new Date(y, 0, 1); to = new Date(y, 11, 31); break;
      case 'lastyear': from = new Date(y - 1, 0, 1); to = new Date(y - 1, 11, 31); break;
      default: from = new Date(1970, 0, 1); to = new Date(9999, 0, 1);
    }
    var l = PERIODS[key];
    return { key: key, from: iso(from), to: iso(to), label: { ro: l[0], ur: l[1], hi: l[2] } };
  }

  /** The day a new entry belongs to: "kal"/"parso" look back, otherwise today. */
  function entryDate(tokens, now) {
    if (has(tokens, 'parso')) return iso(addDays(now, -2));
    if (has(tokens, 'kal')) return iso(addDays(now, -1));
    return iso(now);
  }

  // ------------------------------------------------------------- replies ---

  function R(ro, ur, hi) { return { ro: ro, ur: ur, hi: hi }; }
  function join() {
    var parts = Array.prototype.slice.call(arguments).filter(Boolean), out = { ro: [], ur: [], hi: [] };
    parts.forEach(function (p) { out.ro.push(p.ro); out.ur.push(p.ur); out.hi.push(p.hi); });
    return R(out.ro.join(' '), out.ur.join(' '), out.hi.join(' '));
  }
  function money(n) {
    var v = Math.round(n * 100) / 100;
    return { show: v.toLocaleString('en-US'), say: String(v) };
  }
  /** How an entry's subject is named, per script. */
  function labelOf(e) {
    if (e.cat && CATS[e.cat]) return R(CATS[e.cat].ro, CATS[e.cat].ur, CATS[e.cat].hi);
    var t = e.item || 'mutafarriq';
    return R(t, e.item || 'متفرق', e.item || 'अन्य');
  }
  function title(s) { return /^[a-z]/.test(s) ? s.charAt(0).toUpperCase() + s.slice(1) : s; }

  var SAY = {
    unknown: R('Maaf kijiye, samajh nahi aaya. Dobara boliye, jaise: paanch sau ka petrol dala.',
               'معاف کیجیے، سمجھ نہیں آیا۔ دوبارہ بولیے، جیسے: پانچ سو کا پیٹرول ڈالا۔',
               'माफ कीजिए, समझ नहीं आया। दोबारा बोलिए, जैसे: पांच सौ का पेट्रोल डाला।'),
    salam: R('Wa alaikum assalam! Bataiye, kya likhna hai?', 'وعلیکم السلام! بتائیے، کیا لکھنا ہے؟', 'वालेकुम अस्सलाम! बताइए, क्या लिखना है?'),
    thanks: R('Koi baat nahi.', 'کوئی بات نہیں۔', 'कोई बात नहीं।'),
    fine: R('Main theek hoon, shukriya. Aap sunaiye?', 'میں ٹھیک ہوں، شکریہ۔ آپ سنائیے؟', 'मैं ठीक हूं, शुक्रिया। आप सुनाइए?'),
    who: R('Main aap ka munshi hoon. Mujhe kharcha, aamdani, udhar ya koi bhi baat bata dein, main yaad rakhoon ga. Phir jab chahein pooch lein.',
           'میں آپ کا منشی ہوں۔ مجھے خرچہ، آمدنی، ادھار یا کوئی بھی بات بتا دیں، میں یاد رکھوں گا۔ پھر جب چاہیں پوچھ لیں۔',
           'मैं आपका मुंशी हूं। मुझे खर्चा, आमदनी, उधार या कोई भी बात बता दें, मैं याद रखूंगा। फिर जब चाहें पूछ लें।'),
    bye: R('Khuda hafiz!', 'خدا حافظ!', 'खुदा हाफिज!'),
    noted: R('Theek hai, yaad rakh liya.', 'ٹھیک ہے، یاد رکھ لیا۔', 'ठीक है, याद रख लिया।'),
    noteWhat: R('Kya yaad rakhna hai? Poori baat boliye.', 'کیا یاد رکھنا ہے؟ پوری بات بولیے۔', 'क्या याद रखना है? पूरी बात बोलिए।'),
    noNote: R('Is baare mein mujhe kuch yaad nahi.', 'اس بارے میں مجھے کچھ یاد نہیں۔', 'इस बारे में मुझे कुछ याद नहीं।'),
    noNotes: R('Abhi tak aap ne koi baat yaad nahi karwayi.', 'ابھی تک آپ نے کوئی بات یاد نہیں کروائی۔', 'अभी तक आपने कोई बात याद नहीं करवाई।'),
    nothingToDelete: R('Mitane ke liye kuch nahi hai.', 'مٹانے کے لیے کچھ نہیں ہے۔', 'मिटाने के लिए कुछ नहीं है।'),
    noLoans: R('Kisi ka koi udhar nahi likha.', 'کسی کا کوئی ادھار نہیں لکھا۔', 'किसी का कोई उधार नहीं लिखा।'),
    whoLoan: R('Kis ka udhar? Naam ke sath dobara boliye.', 'کس کا ادھار؟ نام کے ساتھ دوبارہ بولیے۔', 'किसका उधार? नाम के साथ दोबारा बोलिए।'),
    written: R('Likh liya.', 'لکھ لیا۔', 'लिख लिया।'),
    ok: R('Theek hai,', 'ٹھیک ہے،', 'ठीक है,'),
    okShort: R('Theek hai.', 'ٹھیک ہے۔', 'ठीक है।'),
    dropped: R('Theek hai, chhor diya.', 'ٹھیک ہے، چھوڑ دیا۔', 'ठीक है, छोड़ दिया।'),
    askAmount: R('Kitne rupay?', 'کتنے روپے؟', 'कितने रुपये?'),
    askKind: R('Yeh kharcha tha ya aamdani?', 'یہ خرچہ تھا یا آمدنی؟', 'यह खर्चा था या आमदनी?'),
    askNote: R('Yeh mujhe samajh nahi aaya. Kya ise yaad rakh loon?', 'یہ مجھے سمجھ نہیں آیا۔ کیا اسے یاد رکھ لوں؟', 'यह मुझे समझ नहीं आया। क्या इसे याद रख लूं?'),
    noName: R('Aap ne abhi tak apna naam nahi bataya.', 'آپ نے ابھی تک اپنا نام نہیں بتایا۔', 'आपने अभी तक अपना नाम नहीं बताया।'),
    toIncome: R('Theek hai, ise aamdani mein kar diya.', 'ٹھیک ہے، اسے آمدنی میں کر دیا۔', 'ठीक है, इसे आमदनी में कर दिया।'),
    toExpense: R('Theek hai, ise kharche mein kar diya.', 'ٹھیک ہے، اسے خرچے میں کر دیا۔', 'ठीक है, इसे खर्चे में कर दिया।')
  };

  // ---------------------------------------------------------------- data ---

  function emptyDb() {
    return { v: 2, seq: 0, entries: [], notes: [], learned: { cats: {}, alias: {}, kinds: {} }, profile: {}, ctx: {} };
  }
  function isDb(d) { return !!d && typeof d === 'object' && Array.isArray(d.entries) && Array.isArray(d.notes); }
  /** Fill in anything an older save file does not have. */
  function migrate(db) {
    if (!db.learned || typeof db.learned !== 'object') db.learned = {};
    ['cats', 'alias', 'kinds'].forEach(function (k) { if (!db.learned[k] || typeof db.learned[k] !== 'object') db.learned[k] = {}; });
    if (!db.profile || typeof db.profile !== 'object') db.profile = {};
    if (!db.ctx || typeof db.ctx !== 'object') db.ctx = {};
    if (typeof db.seq !== 'number') db.seq = 0;
    db.v = 2;
  }
  function entryById(db, id) { return db.entries.filter(function (e) { return e.id === id; })[0] || null; }
  function added(db, e, now) { db.entries.push(e); db.ctx.lastAdd = { id: e.id, ts: now.getTime() }; }
  function nextId(db) { db.seq = (db.seq || 0) + 1; return db.seq; }
  function inRange(e, p) { return e.date >= p.from && e.date <= p.to; }
  function sum(list) { return list.reduce(function (a, e) { return a + e.amount; }, 0); }
  function ofType(db, type) { return db.entries.filter(function (e) { return e.type === type; }); }

  function balances(db) {
    var map = {}, order = [];
    ofType(db, 'loan').forEach(function (e) {
      if (!map[e.personKey]) { map[e.personKey] = { name: e.person, bal: 0 }; order.push(e.personKey); }
      map[e.personKey].bal += e.delta;
    });
    return { map: map, order: order };
  }
  function loanState(name, bal) {
    var m = money(Math.abs(bal));
    if (bal > 0) return R(name + ' ne aap ko ' + m.show + ' rupay dene hain.', name + ' نے آپ کو ' + m.say + ' روپے دینے ہیں۔', name + ' ने आपको ' + m.say + ' रुपये देने हैं।');
    if (bal < 0) return R('Aap ne ' + name + ' ko ' + m.show + ' rupay dene hain.', 'آپ نے ' + name + ' کو ' + m.say + ' روپے دینے ہیں۔', 'आपने ' + name + ' को ' + m.say + ' रुपये देने हैं।');
    return R(name + ' ke sath hisaab barabar hai.', name + ' کے ساتھ حساب برابر ہے۔', name + ' के साथ हिसाब बराबर है।');
  }

  // ------------------------------------------------------------- intents ---

  /** Is this a question? Urdu "کیا" also means "did", so position decides. */
  function isQuestion(text, tokens) {
    if (/[?؟]/.test(text)) return true;
    if (hasAny(tokens, ['kitna', 'kya', 'kab', 'kahan', 'kaun', 'kis', 'batao', 'kaise'])) return true;
    return tokens.some(function (t, i) {
      if (t.c !== 'kia') return false;
      var next = tokens[i + 1];
      return i === 0 || (next && (next.c === 'hai' || next.c === 'tha' || next.c === 'hua'));
    });
  }

  function addExpense(db, tokens, now) {
    var amount = firstNum(tokens), catTok = firstCat(tokens);
    var item = unknowns(tokens).map(function (t) { return t.raw; }).join(' ') || (catTok ? catTok.raw : '');
    var e = { id: nextId(db), type: 'expense', amount: amount, cat: catTok ? catTok.cat : null,
              item: item, date: entryDate(tokens, now), ts: now.getTime() };
    added(db, e, now);
    var m = money(amount), l = labelOf(e);
    var said = R(l.ro + ' ke ' + m.show + ' rupay likh liye.', l.ur + ' کے ' + m.say + ' روپے لکھ لیے۔', l.hi + ' के ' + m.say + ' रुपये लिख लिए।');
    return { said: said, date: e.date };
  }

  function dayTotal(db, date, now) {
    var t = money(sum(ofType(db, 'expense').filter(function (e) { return e.date === date; })));
    if (date === iso(now)) return R('Aaj ka kul kharcha ' + t.show + ' rupay ho gaya.', 'آج کا کل خرچہ ' + t.say + ' روپے ہو گیا۔', 'आज का कुल खर्चा ' + t.say + ' रुपये हो गया।');
    return R('Us din ka kul kharcha ' + t.show + ' rupay ho gaya.', 'اس دن کا کل خرچہ ' + t.say + ' روپے ہو گیا۔', 'उस दिन का कुल खर्चा ' + t.say + ' रुपये हो गया।');
  }

  function addIncome(db, tokens, now) {
    var amount = firstNum(tokens);
    var item = unknowns(tokens).map(function (t) { return t.raw; }).join(' ');
    added(db, { id: nextId(db), type: 'income', amount: amount, item: item, date: entryDate(tokens, now), ts: now.getTime() }, now);
    var m = money(amount);
    return R(m.show + ' rupay aamdani mein likh liye.', m.say + ' روپے آمدنی میں لکھ لیے۔', m.say + ' रुपये आमदनी में लिख लिए।');
  }

  /** The name sitting just before ko / se / ne / ka, else the first unknown word. */
  function personOf(tokens) {
    var order = ['ko', 'se', 'ne', 'ka'];
    for (var k = 0; k < order.length; k++) {
      for (var i = 1; i < tokens.length; i++) {
        var p = tokens[i - 1];
        if (tokens[i].c === order[k] && !p.num && !p.c && !p.cat && !NUMOF[p.n]) return p;
      }
    }
    return unknowns(tokens)[0] || null;
  }

  function addLoan(db, tokens, now) {
    var amount = firstNum(tokens), p = personOf(tokens);
    if (!p) return SAY.whoLoan;
    var delta;                                   // positive: they owe me more
    if (has(tokens, 'wapas')) delta = has(tokens, 'ko') && !has(tokens, 'ne') ? amount : -amount;
    else if (has(tokens, 'se') && !has(tokens, 'ko')) delta = -amount;
    else if (has(tokens, 'took') && !has(tokens, 'ko')) delta = -amount;
    else delta = amount;
    var name = title(p.raw);
    added(db, { id: nextId(db), type: 'loan', amount: amount, delta: delta, person: name, personKey: p.n,
                date: entryDate(tokens, now), ts: now.getTime() }, now);
    return loanState(name, balances(db).map[p.n].bal);
  }

  function saveNote(db, text, now) {
    var clean = String(text).trim();
    if (!clean) return SAY.noteWhat;
    db.notes.push({ id: nextId(db), text: clean, ts: now.getTime() });
    return SAY.noted;
  }

  /** Drop the "yaad rakho ke ..." wrapper and keep what the user wants saved. */
  function stripRemember(text) {
    var drop = { yaad: 1, rakh: 1, note: 1, likh: 1, save: 1 };
    var helper = /^(kar|karo|kardo|lo|len|lena|do|dena|کر|کرو|لو|لیں|لینا|دو|कर|करो|लो|लें|लेना|दो)$/;
    function bare(w) { return normalise(w).replace(/[.,!?؟،۔।:]/g, ''); }
    var words = String(text).trim().split(/\s+/), keep = [], cut = false;
    words.forEach(function (w) {
      var n = bare(w);
      if (drop[LEX[n]]) { cut = true; return; }
      if (cut && (helper.test(n) || LEX[n] === 'keh' || n === 'ke' || n === 'kay')) return;   // glue right after the trigger
      cut = false;
      keep.push(w);
    });
    return keep.join(' ');
  }

  function isRemember(tokens) {
    if (has(tokens, 'yaad') && has(tokens, 'rakh')) return true;
    return hasAny(tokens, ['note', 'likh', 'save']) && !hasAny(tokens, ['kitna', 'kya', 'kab', 'kahan', 'kaun']);
  }

  function findNote(db, tokens) {
    var keys = unknowns(tokens).map(function (t) { return t.n; });
    tokens.forEach(function (t) { if (t.cat) keys.push(t.n); if (t.num) keys.push(t.n); });
    if (!keys.length) return null;
    var best = null, bestScore = 0;
    db.notes.forEach(function (note) {
      var words = tokenise(note.text).map(function (t) { return t.n; });
      var score = keys.filter(function (k) { return words.indexOf(k) !== -1; }).length;
      if (score > 0 && score >= bestScore) { best = note; bestScore = score; }
    });
    return best;
  }

  var YOU = {};
  [['mera', 'aap ka'], ['meri', 'aap ki'], ['mere', 'aap ke'], ['mujhe', 'aap ko'], ['mujhay', 'aap ko'],
   ['میرا', 'آپ کا'], ['میری', 'آپ کی'], ['میرے', 'آپ کے'], ['مجھے', 'آپ کو'],
   ['मेरा', 'आपका'], ['मेरी', 'आपकी'], ['मेरे', 'आपके'], ['मुझे', 'आपको']].forEach(function (p) { YOU[normalise(p[0])] = p[1]; });
  /** "meri gaari ..." is said back as "aap ki gaari ...". */
  function toYou(text) {
    return String(text).split(/\s+/).map(function (w) { var k = normalise(w); return own(YOU, k) ? YOU[k] : w; }).join(' ');
  }

  function answerNote(db, tokens) {
    var note = findNote(db, tokens);
    if (note) { var back = toYou(note.text); return R(back.charAt(0).toUpperCase() + back.slice(1), back, back); }
    if (has(tokens, 'yaad') && !unknowns(tokens).length) {
      if (!db.notes.length) return SAY.noNotes;
      var last = db.notes[db.notes.length - 1], n = db.notes.length;
      return R('Aap ki ' + n + ' baatein yaad hain. Aakhri yeh thi: ' + last.text,
               'آپ کی ' + n + ' باتیں یاد ہیں۔ آخری یہ تھی: ' + last.text,
               'आपकी ' + n + ' बातें याद हैं। आखिरी यह थी: ' + last.text);
    }
    return null;
  }

  /** Narrow expenses to a category or to a thing the user has logged before. */
  function expenseFilter(db, tokens) {
    var catTok = firstCat(tokens);
    if (catTok) {
      return { label: labelOf({ cat: catTok.cat }), test: function (e) { return e.cat === catTok.cat; } };
    }
    var keys = unknowns(tokens).map(function (t) { return t.n; });
    var hits = keys.filter(function (k) {
      return ofType(db, 'expense').some(function (e) { return normalise(e.item).split(/\s+/).indexOf(k) !== -1; });
    });
    if (!hits.length) return null;
    var raw = unknowns(tokens).filter(function (t) { return hits.indexOf(t.n) !== -1; }).map(function (t) { return t.raw; }).join(' ');
    return { label: R(raw, raw, raw), test: function (e) {
      var words = normalise(e.item).split(/\s+/);
      return hits.every(function (k) { return words.indexOf(k) !== -1; });
    } };
  }

  function byGroup(list) {
    var map = {}, order = [];
    list.forEach(function (e) {
      var key = e.cat || ('item:' + normalise(e.item));
      if (!map[key]) { map[key] = { label: labelOf(e), total: 0 }; order.push(key); }
      map[key].total += e.amount;
    });
    return order.map(function (k) { return map[k]; }).sort(function (a, b) { return b.total - a.total; });
  }

  function answerExpense(db, tokens, now) {
    var p = periodOf(tokens, now), P = p.label;
    var filter = expenseFilter(db, tokens);
    var list = ofType(db, 'expense').filter(function (e) { return inRange(e, p) && (!filter || filter.test(e)); });

    if (has(tokens, 'last')) {
      var all = ofType(db, 'expense');
      if (!all.length) return R('Abhi tak koi kharcha nahi likha.', 'ابھی تک کوئی خرچہ نہیں لکھا۔', 'अभी तक कोई खर्चा नहीं लिखा।');
      var e = all[all.length - 1], l = labelOf(e), m = money(e.amount);
      return R('Aakhri kharcha ' + l.ro + ' ka tha, ' + m.show + ' rupay.', 'آخری خرچہ ' + l.ur + ' کا تھا، ' + m.say + ' روپے۔', 'आखिरी खर्चा ' + l.hi + ' का था, ' + m.say + ' रुपये।');
    }
    if (!list.length) {
      if (filter) return R(P.ro + ' ' + filter.label.ro + ' par koi kharcha nahi likha.', P.ur + ' ' + filter.label.ur + ' پر کوئی خرچہ نہیں لکھا۔', P.hi + ' ' + filter.label.hi + ' पर कोई खर्चा नहीं लिखा।');
      return R(P.ro + ' koi kharcha nahi likha.', P.ur + ' کوئی خرچہ نہیں لکھا۔', P.hi + ' कोई खर्चा नहीं लिखा।');
    }
    var t = money(sum(list));
    if (filter) return R(P.ro + ' ' + filter.label.ro + ' par ' + t.show + ' rupay kharch hue.', P.ur + ' ' + filter.label.ur + ' پر ' + t.say + ' روپے خرچ ہوئے۔', P.hi + ' ' + filter.label.hi + ' पर ' + t.say + ' रुपये खर्च हुए।');

    var groups = byGroup(list);
    if (has(tokens, 'zyada')) {
      var g = groups[0], gm = money(g.total);
      return R(P.ro + ' sab se zyada kharcha ' + g.label.ro + ' par hua, ' + gm.show + ' rupay.', P.ur + ' سب سے زیادہ خرچہ ' + g.label.ur + ' پر ہوا، ' + gm.say + ' روپے۔', P.hi + ' सबसे ज्यादा खर्चा ' + g.label.hi + ' पर हुआ, ' + gm.say + ' रुपये।');
    }
    var totalLine = R(P.ro + ' kul kharcha ' + t.show + ' rupay hai.', P.ur + ' کل خرچہ ' + t.say + ' روپے ہے۔', P.hi + ' कुल खर्चा ' + t.say + ' रुपये है।');
    if (has(tokens, 'detail') || (has(tokens, 'kis') && has(tokens, 'par'))) {
      var top = groups.slice(0, 4).map(function (x) {
        var xm = money(x.total);
        return R(x.label.ro + ' ' + xm.show, x.label.ur + ' ' + xm.say, x.label.hi + ' ' + xm.say);
      });
      return join(totalLine, R(top.map(function (x) { return x.ro; }).join(', ') + '.', top.map(function (x) { return x.ur; }).join('، ') + '۔', top.map(function (x) { return x.hi; }).join(', ') + '।'));
    }
    return totalLine;
  }

  function answerIncome(db, tokens, now) {
    var p = periodOf(tokens, now), P = p.label;
    var t = money(sum(ofType(db, 'income').filter(function (e) { return inRange(e, p); })));
    return R(P.ro + ' kul aamdani ' + t.show + ' rupay hai.', P.ur + ' کل آمدنی ' + t.say + ' روپے ہے۔', P.hi + ' कुल आमदनी ' + t.say + ' रुपये है।');
  }

  function answerBalance(db, tokens, now) {
    var p = periodOf(tokens, now), P = p.label;
    var inc = sum(ofType(db, 'income').filter(function (e) { return inRange(e, p); }));
    var exp = sum(ofType(db, 'expense').filter(function (e) { return inRange(e, p); }));
    var i = money(inc), x = money(exp), b = money(Math.abs(inc - exp));
    if (inc - exp >= 0) return R(P.ro + ' aamdani ' + i.show + ', kharcha ' + x.show + ', bachat ' + b.show + ' rupay.', P.ur + ' آمدنی ' + i.say + '، خرچہ ' + x.say + '، بچت ' + b.say + ' روپے۔', P.hi + ' आमदनी ' + i.say + ', खर्चा ' + x.say + ', बचत ' + b.say + ' रुपये।');
    return R(P.ro + ' aamdani ' + i.show + ', kharcha ' + x.show + '. Kharcha aamdani se ' + b.show + ' rupay zyada hai.', P.ur + ' آمدنی ' + i.say + '، خرچہ ' + x.say + '۔ خرچہ آمدنی سے ' + b.say + ' روپے زیادہ ہے۔', P.hi + ' आमदनी ' + i.say + ', खर्चा ' + x.say + '। खर्चा आमदनी से ' + b.say + ' रुपये ज्यादा है।');
  }

  function answerLoans(db, tokens) {
    var b = balances(db);
    var named = unknowns(tokens).filter(function (t) { return b.map[t.n]; })[0];
    if (named) return loanState(b.map[named.n].name, b.map[named.n].bal);
    var open = b.order.filter(function (k) { return b.map[k].bal !== 0; });
    if (!open.length) return SAY.noLoans;
    return join.apply(null, open.slice(0, 4).map(function (k) { return loanState(b.map[k].name, b.map[k].bal); }));
  }

  function answerClock(tokens, now) {
    if (has(tokens, 'tareekh')) {
      var opt = { day: 'numeric', month: 'long', year: 'numeric' };
      return R('Aaj ' + now.toLocaleDateString('en-GB', opt) + ' hai.', 'آج ' + now.toLocaleDateString('ur-PK', opt) + ' ہے۔', 'आज ' + now.toLocaleDateString('hi-IN', opt) + ' है।');
    }
    var h = now.getHours() % 12 || 12, mi = now.getMinutes();
    return R('Abhi ' + h + ':' + ('0' + mi).slice(-2) + ' baje hain.', 'ابھی ' + h + ' بج کر ' + mi + ' منٹ ہوئے ہیں۔', 'अभी ' + h + ' बजकर ' + mi + ' मिनट हुए हैं।');
  }

  function deleteLast(db) {
    var e = db.entries[db.entries.length - 1], n = db.notes[db.notes.length - 1];
    if (!e && !n) return SAY.nothingToDelete;
    if (n && (!e || n.id > e.id)) {
      db.notes.pop();
      return R('Theek hai, aakhri baat mita di.', 'ٹھیک ہے، آخری بات مٹا دی۔', 'ठीक है, आखिरी बात मिटा दी।');
    }
    db.entries.pop();
    var m = money(e.amount);
    return R('Theek hai, ' + m.show + ' rupay wali aakhri entry mita di.', 'ٹھیک ہے، ' + m.say + ' روپے والی آخری انٹری مٹا دی۔', 'ठीक है, ' + m.say + ' रुपये वाली आखिरी एंट्री मिटा दी।');
  }

  function smallTalk(tokens) {
    if (has(tokens, 'kaise') && hasAny(tokens, ['hai', 'tum'])) return SAY.fine;
    if (has(tokens, 'help') || (has(tokens, 'kaun') && hasAny(tokens, ['tum', 'hai'])) || has(tokens, 'sakte')) return SAY.who;
    if (has(tokens, 'shukriya')) return SAY.thanks;
    if (has(tokens, 'salam')) return SAY.salam;
    return null;
  }

  /** Record one money sentence (it is known to contain an amount). */
  function recordMoney(db, tokens, now) {
    if (hasAny(tokens, ['loan', 'wapas'])) return { said: addLoan(db, tokens, now) };
    var endsHai = tokens.length && tokens[tokens.length - 1].c === 'hai';
    var acted = hasAny(tokens, ['kharch', 'spendv', 'gave', 'took']);
    if (has(tokens, 'incomev') && !has(tokens, 'kharch') && !has(tokens, 'spendv')) return { said: addIncome(db, tokens, now) };
    if (has(tokens, 'incomen') && !acted && !endsHai) return { said: addIncome(db, tokens, now) };
    if (endsHai && !acted) return null;                         // a fact, not a payment
    var taught = tokens.filter(function (t) { return own(LEARN.kinds, t.n); })[0];
    if (taught && !acted && !firstCat(tokens)) {
      return LEARN.kinds[taught.n] === 'income' ? { said: addIncome(db, tokens, now) } : addExpense(db, tokens, now);
    }
    var words = tokens.filter(function (t) { return !t.num && t.c !== 'stop'; }).length;
    if (acted || hasAny(tokens, ['cur', 'paise']) || firstCat(tokens) || words <= 4) return addExpense(db, tokens, now);
    return null;
  }

  // ------------------------------------------------------ conversation ---

  var RECENT = 3 * 60 * 1000;      // how long "the last thing we talked about" stays in mind
  var PERIOD_WORDS = { aaj: 1, kal: 1, parso: 1, hafta: 1, mahina: 1, saal: 1, pichla: 1, is: 1, ab: 1, tak: 1, total: 1 };
  var GLUE = { hai: 1, tha: 1, hua: 1, ka: 1, ko: 1, se: 1, ne: 1, par: 1, keh: 1, stop: 1, aur: 1, yes: 1, no: 1 };
  var MY = {};
  ['mera', 'میرا', 'मेरा'].forEach(function (w) { MY[normalise(w)] = 1; });

  function ack(db) {
    var v = [['Theek hai,', 'ٹھیک ہے،', 'ठीक है,'], ['Ji,', 'جی،', 'जी,'], ['Acha,', 'اچھا،', 'अच्छा,']][db.seq % 3];
    return R(v[0], v[1], v[2]);
  }

  /** The greeting that opens a call. */
  function greet(db) {
    var n = db && db.profile && db.profile.name ? ' ' + db.profile.name : '';
    return R('Assalam o alaikum' + n + '! Bataiye.', 'السلام علیکم' + n + '! بتائیے۔', 'अस्सलाम अलैकुम' + n + '! बताइए।');
  }

  /** "nahi, 600 the": fix the amount of the entry that was just booked. */
  function amend(e, amount) {
    var o = money(e.amount), n = money(amount);
    e.amount = amount;
    if (e.type === 'loan') e.delta = e.delta < 0 ? -amount : amount;
    return R('Theek hai, ' + o.show + ' ki jagah ' + n.show + ' rupay kar diye.', 'ٹھیک ہے، ' + o.say + ' کی جگہ ' + n.say + ' روپے کر دیے۔', 'ठीक है, ' + o.say + ' की जगह ' + n.say + ' रुपये कर दिए।');
  }

  function learnKind(db, text, kind) {
    unknowns(tokenise(text)).slice(0, 2).forEach(function (t) { db.learned.kinds[t.n] = kind; });
  }

  /** "nahi, yeh aamdani thi": move the entry and remember its words for next time. */
  function convert(db, e, kind) {
    learnKind(db, e.item || '', kind);
    e.type = kind;
    if (kind === 'income') { delete e.cat; return SAY.toIncome; }
    var c = firstCat(tokenise(e.item || ''));
    e.cat = c ? c.cat : null;
    return SAY.toExpense;
  }

  /** The user is answering a question we asked a moment ago. */
  function answerPending(pend, tokens, db, now) {
    var amount = firstNum(tokens);
    if (pend.type === 'amount') {
      var extra = tokens.filter(function (t) { return !t.num && !GLUE[t.c] && t.c !== 'cur'; }).length;
      if (amount !== null && !extra) return route(pend.text + ' ' + amount, db, now, 1);
      if (has(tokens, 'no') && tokens.length <= 3) return { said: SAY.dropped };
      return null;
    }
    if (pend.type === 'kind') {
      var pt = tokenise(pend.text), r = null;
      if (has(tokens, 'kharch')) { learnKind(db, pend.text, 'expense'); r = addExpense(db, pt, now); return { said: join(ack(db), r.said, dayTotal(db, r.date, now)) }; }
      if (hasAny(tokens, ['incomen', 'incomev'])) { learnKind(db, pend.text, 'income'); return { said: join(ack(db), addIncome(db, pt, now)) }; }
      if (hasAny(tokens, ['yaad', 'note'])) return { said: saveNote(db, pend.text, now) };
      if (has(tokens, 'no') && tokens.length <= 3) return { said: SAY.dropped };
      return null;
    }
    if (pend.type === 'note' && tokens.length <= 4) {
      if (hasAny(tokens, ['yes', 'rakh', 'yaad'])) return { said: saveNote(db, pend.text, now) };
      if (has(tokens, 'no')) return { said: SAY.dropped };
    }
    return null;
  }

  /** "aur kal?" / "aur petrol par?": the last question again with one part swapped. */
  function mergeFollowUp(lastText, fresh) {
    var newPeriod = fresh.some(function (t) { return PERIOD_WORDS[t.c]; });
    var newSubject = fresh.some(function (t) { return t.cat || (!t.c && !t.num); });
    var kept = tokenise(lastText).filter(function (t) {
      if (newPeriod && PERIOD_WORDS[t.c]) return false;
      if (newSubject && (t.cat || (!t.c && !t.num))) return false;
      return true;
    });
    return kept.concat(fresh).map(function (t) { return t.raw; }).join(' ');
  }

  /** "X ka matlab Y hai" and "X ko Y mein gino": the user teaching a word. */
  function teach(db, tokens) {
    if (firstNum(tokens) !== null || has(tokens, 'yaad')) return null;
    var at = -1;
    tokens.forEach(function (t, i) { if (t.c === 'matlab' && at < 0) at = i; });
    if (at > 0) {
      var x = unknowns(tokens.slice(0, at))[0];
      var y = tokens.slice(at + 1).filter(function (t) { return t.cat || (t.c && !GLUE[t.c]); })[0];
      if (!x || !y) return null;
      db.learned.alias[x.n] = { c: y.c || null, cat: y.cat || null };
      return R('Theek hai, ab ' + x.raw + ' ka matlab samajh gaya.', 'ٹھیک ہے، اب ' + x.raw + ' کا مطلب سمجھ گیا۔', 'ठीक है, अब ' + x.raw + ' का मतलब समझ गया।');
    }
    if (!hasAny(tokens, ['teach', 'rakh'])) return null;
    var cats = tokens.filter(function (t) { return t.cat; }), unk = unknowns(tokens), word, target;
    if (unk.length && cats.length) { word = unk[0]; target = cats[cats.length - 1]; }
    else if (cats.length >= 2 && cats[0].cat !== cats[cats.length - 1].cat) { word = cats[0]; target = cats[cats.length - 1]; }
    else return null;
    db.learned.cats[word.n] = target.cat;
    db.entries.forEach(function (e) {                      // past entries follow the new rule too
      if (e.type === 'expense' && normalise(e.item || '').split(/\s+/).indexOf(word.n) !== -1) e.cat = target.cat;
    });
    var L = labelOf({ cat: target.cat });
    return R('Theek hai, ab ' + word.raw + ' ko ' + L.ro + ' ke kharche mein ginoon ga.', 'ٹھیک ہے، اب ' + word.raw + ' کو ' + L.ur + ' کے خرچے میں گنوں گا۔', 'ठीक है, अब ' + word.raw + ' को ' + L.hi + ' के खर्चे में गिनूंगा।');
  }

  function myNameAt(tokens) {
    for (var i = 0; i + 1 < tokens.length; i++) if (MY[tokens[i].n] && tokens[i + 1].c === 'naam') return i + 2;
    return -1;
  }
  function setName(db, tokens) {
    var at = myNameAt(tokens);
    var name = at < 0 ? null : unknowns(tokens.slice(at))[0];
    if (!name) return null;
    var n = db.profile.name = title(name.raw);
    return R('Theek hai ' + n + ', yaad rakh liya.', 'ٹھیک ہے ' + n + '، یاد رکھ لیا۔', 'ठीक है ' + n + ', याद रख लिया।');
  }

  // ---------------------------------------------------------------- main ---

  function route(text, db, now, depth) {
    var tokens = tokenise(text), ctx = db.ctx, t = now.getTime();
    if (!tokens.length) return { said: SAY.unknown };
    var question = isQuestion(text, tokens), amount = firstNum(tokens), said = null;

    if (depth === 0) {
      var pend = ctx.pending && t - ctx.pending.ts < RECENT ? ctx.pending : null;
      ctx.pending = null;
      var answered = pend ? answerPending(pend, tokens, db, now) : null;
      if (answered) return answered;                       // otherwise treat it as a fresh sentence
    }

    // hanging up ("Hafiz" is also a name, so it needs "khuda" or to stand alone)
    var bye = tokens.some(function (tk) { return BYE[tk.n]; });
    if (amount === null && ((bye && (has(tokens, 'khuda') || tokens.length <= 2)) ||
        (has(tokens, 'band') && hasAny(tokens, ['call', 'stop']) && tokens.length <= 4))) {
      return { said: SAY.bye, action: 'hangup' };
    }
    if (has(tokens, 'backup')) return { said: SAY.okShort, action: hasAny(tokens, ['wapas', 'lao']) ? 'restore' : 'backup' };

    // fixing what was just booked
    var last = depth === 0 && ctx.lastAdd && t - ctx.lastAdd.ts < RECENT ? entryById(db, ctx.lastAdd.id) : null;
    if (last && !question && tokens.length <= 5 && (tokens[0].c === 'no' || has(tokens, 'ghalat'))) {
      if (amount !== null && !firstCat(tokens) && !unknowns(tokens).length) return { said: amend(last, amount) };
      if (amount === null && last.type === 'expense' && hasAny(tokens, ['incomen', 'incomev'])) return { said: convert(db, last, 'income') };
      if (amount === null && last.type === 'income' && has(tokens, 'kharch')) return { said: convert(db, last, 'expense') };
    }
    if (has(tokens, 'del') || (has(tokens, 'ghalat') && tokens.length <= 5)) return { said: deleteLast(db) };

    if (depth === 0 && tokens[0].c === 'aur' && tokens.length > 1 && tokens.length <= 5 && amount === null &&
        ctx.lastQuery && t - ctx.lastQuery.ts < RECENT) {
      return route(mergeFollowUp(ctx.lastQuery.text, tokens.slice(1)), db, now, 1);
    }

    if (!question) {
      said = teach(db, tokens) || setName(db, tokens);
      if (said) return { said: said };
      // "yaad rakho ..." - a money sentence is still booked as money
      if (isRemember(tokens)) {
        var inner = stripRemember(text), innerTokens = tokenise(inner);
        var booked = firstNum(innerTokens) !== null ? recordMoney(db, innerTokens, now) : null;
        if (booked) return { said: join(ack(db), booked.said, booked.date ? dayTotal(db, booked.date, now) : null) };
        return { said: saveNote(db, inner, now) };
      }
    }

    if (question) {
      var finance = hasAny(tokens, ['kharch', 'spendv', 'loan', 'bachat', 'incomev', 'incomen', 'zyada', 'hisaab', 'kitna', 'paise', 'detail']);
      if (!finance) {
        if (hasAny(tokens, ['tareekh', 'waqt'])) return { said: answerClock(tokens, now) };
        if (myNameAt(tokens) >= 0) {
          var nm = db.profile.name;
          return { said: nm ? R('Aap ka naam ' + nm + ' hai.', 'آپ کا نام ' + nm + ' ہے۔', 'आपका नाम ' + nm + ' है।') : SAY.noName };
        }
        said = smallTalk(tokens) || answerNote(db, tokens);
        if (said) return { said: said };
        if (!firstCat(tokens)) return { said: SAY.noNote };
      }
      ctx.lastQuery = { text: text, ts: t };
      if (has(tokens, 'loan')) return { said: answerLoans(db, tokens) };
      if (has(tokens, 'bachat')) return { said: answerBalance(db, tokens, now) };
      if (hasAny(tokens, ['incomev', 'incomen']) && !has(tokens, 'kharch')) return { said: answerIncome(db, tokens, now) };
      return { said: answerExpense(db, tokens, now) };
    }

    var endsHai = hasAny([tokens[tokens.length - 1]], ['hai', 'tha']);

    // statements with an amount; "500 ka petrol aur 200 ki chai" books both
    if (amount !== null) {
      var clauses = [[]];
      tokens.forEach(function (tk) { if (tk.c === 'aur') clauses.push([]); else clauses[clauses.length - 1].push(tk); });
      if (!clauses.every(function (c) { return firstNum(c) !== null; })) clauses = [tokens];
      var parts = [], lastDate = null;
      clauses.forEach(function (c) {
        var shared = c === tokens ? c : c.concat(tokens.filter(function (tk) { return tk.c === 'kal' || tk.c === 'parso'; }));
        var r = recordMoney(db, shared, now);
        if (!r) return;
        parts.push(r.said); if (r.date) lastDate = r.date;
      });
      if (parts.length) return { said: join.apply(null, [ack(db)].concat(parts, lastDate ? [dayTotal(db, lastDate, now)] : [])) };
      if (!endsHai) {                                      // money, but which way did it go?
        ctx.pending = { type: 'kind', text: text, ts: t };
        return { said: SAY.askKind };
      }
    }

    said = smallTalk(tokens);
    if (said) return { said: said };

    // "petrol dalwaya" - a payment with no amount: ask for it
    var paid = hasAny(tokens, ['spendv', 'kharch']) || (hasAny(tokens, ['gave', 'took']) && !endsHai);
    if (amount === null && paid && (firstCat(tokens) || hasAny(tokens, ['cur', 'paise']))) {
      ctx.pending = { type: 'amount', text: text, ts: t };
      return { said: SAY.askAmount };
    }

    // a plain statement of fact ("meri gaari ka number ... hai") is remembered
    if (tokens.length >= 3 && hasAny(tokens, ['hai', 'tha'])) return { said: saveNote(db, text, now) };

    if (tokens.length >= 3 && unknowns(tokens).length >= 2) {
      ctx.pending = { type: 'note', text: text, ts: t };
      return { said: SAY.askNote };
    }
    return { said: SAY.unknown };
  }

  /** Everything that must survive a restart; the short-term context is left out. */
  function lasting(db) { return JSON.stringify(db, function (k, v) { return k === 'ctx' ? undefined : v; }); }

  /**
   * Understand one sentence. Mutates `db` and returns
   * { show, speak: {ro, ur, hi}, action, changed }.
   */
  function handle(text, db, now) {
    now = now || new Date();
    migrate(db);
    LEARN = db.learned;
    var before = lasting(db);
    var r = route(String(text == null ? '' : text), db, now, 0);
    return { show: r.said.ro, speak: r.said, action: r.action || null, changed: before !== lasting(db) };
  }

  return { handle: handle, greet: greet, emptyDb: emptyDb, isDb: isDb, lasting: lasting, tokenise: tokenise,
           normalise: normalise, balances: balances, labelOf: labelOf, CATS: CATS, iso: iso };
});
