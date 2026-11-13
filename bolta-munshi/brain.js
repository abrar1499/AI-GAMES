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
    // sums
    plus: 'jama plus + جمع پلس जमा प्लस',
    minus: 'minus manfi nafi tafreeq منفی تفریق مائنس मनफी माइनस',
    times: 'guna zarb multiply into × * گنا ضرب गुना जरब',
    divide: 'taqseem divide ÷ تقسیم तकसीम',
    percent: 'percent feesad fisad fisadi % فیصد پرسنٹ प्रतिशत परसेंट',
    minusv: 'nikalo nikal ghatao ghata نکالو گھٹاؤ گھٹاو निकालो घटाओ',
    // "jab main kahun ... to kehna ..."
    isay: 'kahun kahoon kahu bolun boloon کہوں بولوں कहूं बोलूं',
    yousay: 'kehna kahna bolna kaho کہنا بولنا کہو कहना बोलना कहो',
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

  /** True when the two words differ by exactly one letter added, dropped or changed. */
  function oneOff(a, b) {
    if (Math.abs(a.length - b.length) > 1 || a === b) return false;
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
    if (n.length < 6 || /\d/.test(n) || NUMOF[n]) return {};       // short words are too easily another real word
    if (!FUZZY[n]) {
      var found = {}, clash = false;
      function consider(key, value) {
        if (key.length < 6 || key[0] !== n[0] || !oneOff(n, key)) return;
        if ((found.c || found.cat) && (found.c !== value.c || found.cat !== value.cat)) clash = true;
        found = value;
      }
      Object.keys(LEX).forEach(function (k) { if (FUZZY_OK[LEX[k]]) consider(k, { c: LEX[k] }); });
      Object.keys(CATOF).forEach(function (k) { consider(k, { cat: CATOF[k] }); });
      FUZZY[n] = clash ? {} : found;
    }
    return FUZZY[n];
  }

  var SUM_SIGNS = { plus: 1, minus: 1, times: 1, divide: 1, percent: 1 };

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
      return !!w && ((info && (info.kind === 'mult')) || w.c === 'cur' || SUM_SIGNS[w.c] === 1);
    }
    function firm(k) {                // may words[k], a shaky number word, be read as a number here?
      return supports(words[k + 1]) || (k > 0 && SUM_SIGNS[words[k - 1].c] === 1);
    }

    var out = [];
    for (var i = 0; i < words.length; i++) {
      var info = numInfo(words[i]);
      var starts = info && info.kind !== 'mult' && !(info.shaky && !firm(i));
      // a bare "sau"/"hazar" counts as one hundred / one thousand when money follows
      if (info && info.kind === 'mult' && words[i].n !== 'k' && words[i + 1] && words[i + 1].c === 'cur') starts = true;
      if (!starts) { out.push(words[i]); continue; }

      var total = 0, cur = 0, half = false, j = i, used = false;
      for (; j < words.length; j++) {
        var x = numInfo(words[j]);
        if (!x) break;
        if (x.shaky && !firm(j)) break;
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
    noteWhat: R('Kya yaad rakhna hai? Poori baat boliye.', 'کیا یاد رکھنا ہے؟ پوری بات بولیے۔', 'क्या याद रखना है? पूरी बात बोलिए।'),
    learnt: R('Acha, shukriya.', 'اچھا، شکریہ۔', 'अच्छा, शुक्रिया।'),
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
    noName: R('Aap ne abhi tak apna naam nahi bataya.', 'آپ نے ابھی تک اپنا نام نہیں بتایا۔', 'आपने अभी तक अपना नाम नहीं बताया।'),
    toIncome: R('Theek hai, ise aamdani mein kar diya.', 'ٹھیک ہے، اسے آمدنی میں کر دیا۔', 'ठीक है, इसे आमदनी में कर दिया।'),
    toExpense: R('Theek hai, ise kharche mein kar diya.', 'ٹھیک ہے، اسے خرچے میں کر دیا۔', 'ठीक है, इसे खर्चे में कर दिया।')
  };

  // ---------------------------------------------------------------- data ---

  function emptyDb() {
    return { v: 2, seq: 0, entries: [], notes: [], learned: { cats: {}, alias: {}, kinds: {}, heard: {}, replies: [] }, profile: {}, log: [], ctx: {} };
  }
  function isDb(d) { return !!d && typeof d === 'object' && Array.isArray(d.entries) && Array.isArray(d.notes); }
  /** Fill in anything an older save file does not have. */
  function migrate(db) {
    if (!db.learned || typeof db.learned !== 'object') db.learned = {};
    if (!Array.isArray(db.learned.replies)) db.learned.replies = [];
    if (!Array.isArray(db.log)) db.log = [];
    ['cats', 'alias', 'kinds', 'heard'].forEach(function (k) { if (!db.learned[k] || typeof db.learned[k] !== 'object') db.learned[k] = {}; });
    if (!db.profile || typeof db.profile !== 'object') db.profile = {};
    if (!db.ctx || typeof db.ctx !== 'object') db.ctx = {};
    if (typeof db.seq !== 'number') db.seq = 0;
    db.v = 2;
  }
  function entryById(db, id) { return db.entries.filter(function (e) { return e.id === id; })[0] || null; }
  function added(db, e, now) { db.entries.push(e); db.ctx.lastAdd = { id: e.id, ts: now.getTime() }; }
  function nextId(db) { db.seq = (db.seq || 0) + 1; return db.seq; }
  function inRange(e, p) { return e.date >= p.from && e.date <= p.to; }
  function total(list) { return list.reduce(function (a, e) { return a + e.amount; }, 0); }
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
    var t = money(total(ofType(db, 'expense').filter(function (e) { return e.date === date; })));
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
    keep(db, clean, now, false);
    return agree(db);
  }
  /**
   * Put something in memory. `quiet` marks what was picked up from ordinary
   * talk rather than asked for: it can be recalled later, but "mita do" will
   * not reach for it and it is the first to go when memory is full.
   */
  function keep(db, text, now, quiet) {
    var same = normalise(text);
    if (db.notes.some(function (n) { return normalise(n.text) === same; })) return;
    var note = { id: nextId(db), text: text, ts: now.getTime() };
    if (quiet) note.quiet = true;
    db.notes.push(note);
    var extra = db.notes.filter(function (n) { return n.quiet; }).length - 400;
    if (extra > 0) db.notes = db.notes.filter(function (n) { return !n.quiet || extra-- <= 0; });
  }
  /** A plain "all right", the way a person acknowledges being told something. */
  function agree(db) {
    var v = [['Theek hai.', 'ٹھیک ہے۔', 'ठीक है।'], ['Ji, zaroor.', 'جی، ضرور۔', 'जी, जरूर।'], ['Acha, theek hai.', 'اچھا، ٹھیک ہے۔', 'अच्छा, ठीक है।']][(db.ctx.agreed = (db.ctx.agreed || 0) + 1) % 3];
    return R(v[0], v[1], v[2]);
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
      var words = tokenise(note.text).map(function (t) { return t.n; }).concat(note.keys || []);
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
      var recent = db.notes.slice(-3).reverse().map(function (n) { return toYou(n.text); });
      return R('Ji. ' + recent.join('. Aur, ') + '.', 'جی۔ ' + recent.join('۔ اور، ') + '۔', 'जी। ' + recent.join('। और, ') + '।');
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
    var t = money(total(list));
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
    var t = money(total(ofType(db, 'income').filter(function (e) { return inRange(e, p); })));
    return R(P.ro + ' kul aamdani ' + t.show + ' rupay hai.', P.ur + ' کل آمدنی ' + t.say + ' روپے ہے۔', P.hi + ' कुल आमदनी ' + t.say + ' रुपये है।');
  }

  function answerBalance(db, tokens, now) {
    var p = periodOf(tokens, now), P = p.label;
    var inc = total(ofType(db, 'income').filter(function (e) { return inRange(e, p); }));
    var exp = total(ofType(db, 'expense').filter(function (e) { return inRange(e, p); }));
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
    var e = db.entries[db.entries.length - 1], asked = db.notes.filter(function (x) { return !x.quiet; }), n = asked[asked.length - 1];
    if (!e && !n) return SAY.nothingToDelete;
    if (n && (!e || n.id > e.id)) {
      db.notes = db.notes.filter(function (x) { return x !== n; });
      return R('Theek hai, aakhri baat mita di.', 'ٹھیک ہے، آخری بات مٹا دی۔', 'ठीक है, आखिरी बात मिटा दी।');
    }
    db.entries.pop();
    var m = money(e.amount);
    return R('Theek hai, ' + m.show + ' rupay wali aakhri entry mita di.', 'ٹھیک ہے، ' + m.say + ' روپے والی آخری انٹری مٹا دی۔', 'ठीक है, ' + m.say + ' रुपये वाली आखिरी एंट्री मिटा दी।');
  }

  function smallTalk(tokens) {
    if (has(tokens, 'kaise') && hasAny(tokens, ['hai', 'tum'])) return SAY.fine;
    if (has(tokens, 'help') || (has(tokens, 'kaun') && has(tokens, 'tum') && tokens.length <= 4) || has(tokens, 'sakte')) return SAY.who;
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

  /** A set of words, in any of the three scripts, for quick "is one of these present" checks. */
  function W(str) { var o = Object.create(null); each(str, function (w) { o[normalise(w)] = 1; }); return o; }
  function hit(tokens, set) { return tokens.some(function (t) { return set[t.n] === 1; }); }

  var RECENT = 3 * 60 * 1000;      // how long "the last thing we talked about" stays in mind
  var PERIOD_WORDS = { aaj: 1, kal: 1, parso: 1, hafta: 1, mahina: 1, saal: 1, pichla: 1, is: 1, ab: 1, tak: 1, total: 1 };
  var GLUE = { hai: 1, tha: 1, hua: 1, ka: 1, ko: 1, se: 1, ne: 1, par: 1, keh: 1, stop: 1, aur: 1, yes: 1, no: 1 };
  var MINE = W('mera meri mere میرا میری میرے मेरा मेरी मेरे');
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

  var ANSWER_WORD = W('jawab jawaab answer جواب जवाब');
  /** "is ka jawab hai Islamabad" -> "Islamabad". */
  function stripAnswer(text) {
    var words = stripRemember(text).split(/\s+/), at = -1;
    words.forEach(function (w, i) { if (at < 0 && ANSWER_WORD[normalise(w).replace(/[.,!?؟،۔।:]/g, '')] === 1) at = i; });
    var rest = at < 0 ? words : words.slice(at + 1);
    while (rest.length > 1 && /^(hai|he|hy|yeh|ye|keh|ke|ہے|یہ|کہ|है|यह|कि)$/.test(normalise(rest[0]))) rest.shift();
    return rest.join(' ').trim();
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
    if (pend.type === 'answer') {
      // only when the user says so ("jawab hai ...", "yaad rakho ..."): anything else is just the next thing they said
      if (has(tokens, 'ghalat') || (!hit(tokens, ANSWER_WORD) && !isRemember(tokens))) return null;   // "jawab ghalat tha" is a complaint, not an answer
      var told = stripAnswer(pend.heard);
      if (!told) return null;
      db.notes.push({ id: nextId(db), text: told, keys: contentKeys(tokenise(pend.text)), ts: now.getTime() });
      return { said: SAY.learnt };
    }
    if (pend.type === 'riddle') {
      var rd = RIDDLES[pend.i], right = hit(tokens, rd.ans);
      if (isQuestion(pend.heard, tokens) && !right && !hit(tokens, GIVE_UP)) return null;
      return { said: right ? R('Bilkul sahi! Shabash.', 'بالکل صحیح! شاباش۔', 'बिल्कुल सही! शाबाश।')
                           : R('Jawab hai: ' + rd.a[0] + '.', 'جواب ہے: ' + rd.a[1] + '۔', 'जवाब है: ' + rd.a[2] + '।') };
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
    return R('Aap se mil kar khushi hui, ' + n + '.', 'آپ سے مل کر خوشی ہوئی، ' + n + '۔', 'आपसे मिलकर खुशी हुई, ' + n + '।');
  }

  // ---------------------------------------------------------------- sums ---

  /** "25 guna 4", "500 ka 10 percent", "1000 mein se 250 nikalo", "12 + 30". */
  function sum(tokens, text) {
    if (firstCat(tokens) || hasAny(tokens, ['kharch', 'spendv', 'gave', 'took', 'loan', 'incomev', 'incomen'])) return null;
    var nums = [], a = null, b = null, op = null;
    tokens.forEach(function (t, i) { if (t.num) nums.push(i); });
    if (nums.length < 2) return null;
    var pc = -1, sign = -1;
    tokens.forEach(function (t, i) {
      if (t.c === 'percent' && pc < 0) pc = i;
      if (SUM_SIGNS[t.c] && t.c !== 'percent' && sign < 0 && i > nums[0] && i < nums[nums.length - 1]) sign = i;
    });
    if (pc > 0 && tokens[pc - 1].num) {                    // the number just before "percent" is the rate
      var base = nums.filter(function (i) { return i !== pc - 1; })[0];
      a = tokens[base].value; b = tokens[pc - 1].value; op = 'percent';
    } else if (sign > 0) {
      a = tokens[nums.filter(function (i) { return i < sign; }).pop()].value;
      b = tokens[nums.filter(function (i) { return i > sign; })[0]].value;
      op = tokens[sign].c;
    } else if (has(tokens, 'minusv')) {
      a = tokens[nums[0]].value; b = tokens[nums[1]].value; op = 'minus';
    } else if (tokens.length <= 6) {                       // written signs the tokeniser treats as punctuation
      var m = normalise(text).replace(/(\d),(?=\d)/g, '$1').match(/(\d+(?:\.\d+)?)\s*([-\/x])\s*(\d+(?:\.\d+)?)/);
      if (!m) return null;
      a = parseFloat(m[1]); b = parseFloat(m[3]); op = m[2] === '-' ? 'minus' : m[2] === '/' ? 'divide' : 'times';
    } else return null;

    if (op === 'divide' && b === 0) return R('Sifar se taqseem nahi ho sakta.', 'صفر سے تقسیم نہیں ہو سکتا۔', 'शून्य से तकसीम नहीं हो सकता।');
    var v = op === 'plus' ? a + b : op === 'minus' ? a - b : op === 'times' ? a * b : op === 'divide' ? a / b : a * b / 100;
    var r = money(v);
    return R('Jawab ' + r.show + ' hai.', 'جواب ' + r.say + ' ہے۔', 'जवाब ' + r.say + ' है।');
  }

  // ---------------------------------------------------------------- chat ---

  function contentKeys(tokens) {
    return tokens.filter(function (t) { return t.cat || (!t.c && !t.num && !NUMOF[t.n]); }).map(function (t) { return t.n; });
  }

  var I_AM = W('main میں मैं'), TO_ME = W('mujhe mujhay مجھے मुझे');
  /** Is the speaker talking about themselves? ("main" also means "in", so it only counts up front.) */
  function aboutMe(tokens) { return tokens.length <= 3 || I_AM[tokens[0].n] === 1 || hit(tokens, TO_ME); }
  var YOUR = W('tumhara tumhari tumhare aapka apka tera teri تمہارا تمہاری تمہارے تیرا तुम्हारा तुम्हारी तुम्हारे तेरा');
  var GIVE_UP = W('pata maloom haar jawab batao bata پتا معلوم ہار جواب بتاؤ बता पता मालूम हार जवाब बताओ');
  var NO_NET = R('Yeh main nahi bata sakta, mere paas internet nahi hai.', 'یہ میں نہیں بتا سکتا، میرے پاس انٹرنیٹ نہیں ہے۔', 'यह मैं नहीं बता सकता, मेरे पास इंटरनेट नहीं है।');

  // When a question has no answer: say so plainly. Every third time, mention how to teach it.
  var DONT_KNOW = [
    ['Yeh mujhe nahi pata.', 'یہ مجھے نہیں پتا۔', 'यह मुझे नहीं पता।'],
    ['Is ka jawab mere paas nahi hai.', 'اس کا جواب میرے پاس نہیں ہے۔', 'इसका जवाब मेरे पास नहीं है।'],
    ['Maaf kijiye, yeh main nahi janta. Sikhana ho to kahiye: jawab hai, aur phir jawab bol dein.', 'معاف کیجیے، یہ میں نہیں جانتا۔ سکھانا ہو تو کہیے: جواب ہے، اور پھر جواب بول دیں۔', 'माफ कीजिए, यह मैं नहीं जानता। सिखाना हो तो कहिए: जवाब है, और फिर जवाब बोल दें।']
  ];
  // When someone is simply talking: keep them company without pretending to have understood.
  var LISTENING = [
    ['Acha. Aur bataiye.', 'اچھا۔ اور بتائیے۔', 'अच्छा। और बताइए।'],
    ['Ji, main sun raha hoon.', 'جی، میں سن رہا ہوں۔', 'जी, मैं सुन रहा हूं।'],
    ['Hmm. Phir kya hua?', 'ہمم۔ پھر کیا ہوا؟', 'हम्म। फिर क्या हुआ?'],
    ['Theek hai. Kuch likhna ya yaad rakhna ho to bata dijiye ga.', 'ٹھیک ہے۔ کچھ لکھنا یا یاد رکھنا ہو تو بتا دیجیے گا۔', 'ठीक है। कुछ लिखना या याद रखना हो तो बता दीजिएगा।'],
    ['Ji ji, boliye.', 'جی جی، بولیے۔', 'जी जी, बोलिए।']
  ];
  function nextOf(db, list, counter) {
    var n = db.ctx[counter] || 0; db.ctx[counter] = n + 1;
    return R.apply(null, list[n % list.length]);
  }

  var JOKES = [
    ['Ustad ne poocha: sab se zyada barish kahan hoti hai? Shagird bola: zameen par, sir!', 'استاد نے پوچھا: سب سے زیادہ بارش کہاں ہوتی ہے؟ شاگرد بولا: زمین پر، سر!', 'उस्ताद ने पूछा: सबसे ज्यादा बारिश कहां होती है? शागिर्द बोला: जमीन पर, सर!'],
    ['Doctor ne kaha: aap ko aaram ki zaroorat hai. Mareez bola: to phir bill thora kam kar dein, aaram aa jaye ga!', 'ڈاکٹر نے کہا: آپ کو آرام کی ضرورت ہے۔ مریض بولا: تو پھر بل تھوڑا کم کر دیں، آرام آ جائے گا!', 'डॉक्टर ने कहा: आपको आराम की जरूरत है। मरीज बोला: तो फिर बिल थोड़ा कम कर दें, आराम आ जाएगा!'],
    ['Ek aadmi ne munshi se poocha: mera kharcha kam kaise ho? Munshi bola: mujhe batana band kar dein, kam lagne lage ga!', 'ایک آدمی نے منشی سے پوچھا: میرا خرچہ کم کیسے ہو؟ منشی بولا: مجھے بتانا بند کر دیں، کم لگنے لگے گا!', 'एक आदमी ने मुंशी से पूछा: मेरा खर्चा कम कैसे हो? मुंशी बोला: मुझे बताना बंद कर दें, कम लगने लगेगा!'],
    ['Ustad: batao, pani ko garam karein to kya banta hai? Shagird: garam pani, sir!', 'استاد: بتاؤ، پانی کو گرم کریں تو کیا بنتا ہے؟ شاگرد: گرم پانی، سر!', 'उस्ताद: बताओ, पानी को गर्म करें तो क्या बनता है? शागिर्द: गर्म पानी, सर!'],
    ['Bacha bola: Ammi, das rupay de dein, ek buzurg ko dene hain. Ammi: shabash, kahan hain woh? Bacha: bahar qulfi bech rahe hain!', 'بچہ بولا: امی، دس روپے دے دیں، ایک بزرگ کو دینے ہیں۔ امی: شاباش، کہاں ہیں وہ؟ بچہ: باہر قلفی بیچ رہے ہیں!', 'बच्चा बोला: अम्मी, दस रुपये दे दें, एक बुजुर्ग को देने हैं। अम्मी: शाबाश, कहां हैं वो? बच्चा: बाहर कुल्फी बेच रहे हैं!']
  ];
  var SAYINGS = [
    ['Qatra qatra darya banta hai. Thori thori bachat hi bari raqam banti hai.', 'قطرہ قطرہ دریا بنتا ہے۔ تھوڑی تھوڑی بچت ہی بڑی رقم بنتی ہے۔', 'कतरा कतरा दरिया बनता है। थोड़ी थोड़ी बचत ही बड़ी रकम बनती है।'],
    ['Jitni chadar ho, utne hi paon phailane chahiyein.', 'جتنی چادر ہو، اتنے ہی پاؤں پھیلانے چاہییں۔', 'जितनी चादर हो, उतने ही पांव फैलाने चाहिए।'],
    ['Aaj ka kaam kal par mat chhoriye.', 'آج کا کام کل پر مت چھوڑیے۔', 'आज का काम कल पर मत छोड़िए।'],
    ['Sabr ka phal meetha hota hai.', 'صبر کا پھل میٹھا ہوتا ہے۔', 'सब्र का फल मीठा होता है।'],
    ['Mehnat kabhi zaya nahi jati.', 'محنت کبھی ضائع نہیں جاتی۔', 'मेहनत कभी जाया नहीं जाती।']
  ];
  var RIDDLES = [
    { q: ['Paheli suniye: woh kya hai jo jitna sukhata hai, utna hi khud geela hota jata hai?', 'پہیلی سنیے: وہ کیا ہے جو جتنا سکھاتا ہے، اتنا ہی خود گیلا ہوتا جاتا ہے؟', 'पहेली सुनिए: वह क्या है जो जितना सुखाता है, उतना ही खुद गीला होता जाता है?'],
      a: ['tauliya', 'تولیہ', 'तौलिया'], ans: W('tauliya tolia toliya towel تولیہ तौलिया') },
    { q: ['Paheli suniye: woh kya hai jis ke daant hain, magar woh kaat-ta nahi?', 'پہیلی سنیے: وہ کیا ہے جس کے دانت ہیں، مگر وہ کاٹتا نہیں؟', 'पहेली सुनिए: वह क्या है जिसके दांत हैं, मगर वह काटता नहीं?'],
      a: ['kanghi', 'کنگھی', 'कंघी'], ans: W('kanghi kangha kanga comb کنگھی کنگھا कंघी कंघा') },
    { q: ['Paheli suniye: raat ko aate hain, din mein chale jate hain, magar kabhi zameen par nahi utarte. Kaun?', 'پہیلی سنیے: رات کو آتے ہیں، دن میں چلے جاتے ہیں، مگر کبھی زمین پر نہیں اترتے۔ کون؟', 'पहेली सुनिए: रात को आते हैं, दिन में चले जाते हैं, मगर कभी जमीन पर नहीं उतरते। कौन?'],
      a: ['sitare', 'ستارے', 'सितारे'], ans: W('sitare sitara tare taray star stars ستارے ستارہ تارے सितारे सितारा तारे') },
    { q: ['Paheli suniye: woh kya hai jo hamesha aane wala hota hai, magar kabhi aata nahi?', 'پہیلی سنیے: وہ کیا ہے جو ہمیشہ آنے والا ہوتا ہے، مگر کبھی آتا نہیں؟', 'पहेली सुनिए: वह क्या है जो हमेशा आने वाला होता है, मगर कभी आता नहीं?'],
      a: ['kal', 'کل', 'कल'], ans: W('kal tomorrow کل कल') }
  ];

  // Things people say that are not hisaab. Every word set in `need` must be
  // present. `q`: only as a question. `me`: only when said about oneself, so
  // "Ali bimar hai" is still remembered as a note rather than answered.
  var CHAT = [
    { need: ['lateefa latifa lateefay joke chutkula hansao hansa لطیفہ لطیفے جوک چٹکلا ہنساؤ ہنسا लतीफा जोक चुटकुला हंसाओ हंसा'], pool: JOKES },
    { need: ['paheli bujharat پہیلی بجھارت पहेली'], riddle: true },
    { need: ['naseehat nasihat aqwal qaul kahawat نصیحت قول کہاوت नसीहत कहावत'], pool: SAYINGS },
    { need: ['subah صبح सुबह morning', 'bakhair بخیر बखैर good'], say: [['Subah bakhair! Allah kare aaj ka din acha guzre.', 'صبح بخیر! اللہ کرے آج کا دن اچھا گزرے۔', 'सुबह बखैर! अल्लाह करे आज का दिन अच्छा गुजरे।']] },
    { need: ['shab شب शब night', 'bakhair بخیر बखैर good'], say: [['Shab bakhair! Aaram se soiye.', 'شب بخیر! آرام سے سوئیے۔', 'शब बखैर! आराम से सोइए।']] },
    { need: ['mubarak مبارک मुबारक'], say: [['Aap ko bhi bohat bohat mubarak!', 'آپ کو بھی بہت بہت مبارک!', 'आपको भी बहुत बहुत मुबारक!']] },
    { need: ['haal حال हाल'], q: true, say: [['Allah ka shukar hai, main theek hoon. Aap sunaiye?', 'اللہ کا شکر ہے، میں ٹھیک ہوں۔ آپ سنائیے؟', 'अल्लाह का शुक्र है, मैं ठीक हूं। आप सुनाइए?']] },
    { need: ['rahe raha rahi رہے رہا رہی रहे रहा रही', 'kar کر कर'], q: true, max: 6, say: [['Aap ki baat sun raha hoon. Bataiye, kya likhna hai?', 'آپ کی بات سن رہا ہوں۔ بتائیے، کیا لکھنا ہے؟', 'आपकी बात सुन रहा हूं। बताइए, क्या लिखना है?']] },
    { need: ['naam name نام नाम', 'tumhara tumhari aapka apka tera تمہارا تمہاری تیرا तुम्हारा तुम्हारी तेरा'], say: [['Mera naam Munshi hai.', 'میرا نام منشی ہے۔', 'मेरा नाम मुंशी है।']] },
    { need: ['banaya banaaya بنایا बनाया'], q: true, say: [['Mujhe aap ke liye code likh kar banaya gaya hai. Main qaidon par chalne wala munshi hoon.', 'مجھے آپ کے لیے کوڈ لکھ کر بنایا گیا ہے۔ میں قاعدوں پر چلنے والا منشی ہوں۔', 'मुझे आपके लिए कोड लिखकर बनाया गया है। मैं कायदों पर चलने वाला मुंशी हूं।']] },
    { need: ['umar umr عمر उम्र', 'tumhari tumhara aapki teri تمہاری تمہارا تیری तुम्हारी तुम्हारा तेरी'], say: [['Meri koi umar nahi, main to bas ek program hoon.', 'میری کوئی عمر نہیں، میں تو بس ایک پروگرام ہوں۔', 'मेरी कोई उम्र नहीं, मैं तो बस एक प्रोग्राम हूं।']] },
    { need: ['rehte rehta rehti رہتے رہتا رہتی रहते रहता रहती', 'tum aap tu تم آپ तुम आप'], q: true, say: [['Main aap ke phone ke andar rehta hoon.', 'میں آپ کے فون کے اندر رہتا ہوں۔', 'मैं आपके फोन के अंदर रहता हूं।']] },
    { need: ['insaan insan robot machine انسان روبوٹ مشین इंसान रोबोट मशीन', 'tum aap tu تم آپ तुम आप'], say: [['Main insaan nahi, ek program hoon. Lekin aap ki baat ghaur se sunta hoon.', 'میں انسان نہیں، ایک پروگرام ہوں۔ لیکن آپ کی بات غور سے سنتا ہوں۔', 'मैं इंसान नहीं, एक प्रोग्राम हूं। लेकिन आपकी बात गौर से सुनता हूं।']] },
    { need: ['jante jaante pehchante jaanta جانتے پہچانتے جانتا जानते पहचानते जानता'], q: true, name: true },
    { need: ['thak thaka thaki thakan تھک تھکا تھکی تھکن थक थका थकी थकान'], me: true, say: [['Thori der aaram kar lijiye. Pani pi lein to acha lage ga.', 'تھوڑی دیر آرام کر لیجیے۔ پانی پی لیں تو اچھا لگے گا۔', 'थोड़ी देर आराम कर लीजिए। पानी पी लें तो अच्छा लगेगा।']] },
    { need: ['udaas udas pareshan dukhi tension اداس پریشان دکھی ٹینشن उदास परेशान दुखी टेंशन'], me: true, say: [['Mujhe afsos hai. Kisi apne se baat kar lein, dil halka ho jata hai.', 'مجھے افسوس ہے۔ کسی اپنے سے بات کر لیں، دل ہلکا ہو جاتا ہے۔', 'मुझे अफसोस है। किसी अपने से बात कर लें, दिल हल्का हो जाता है।'], ['Allah aap ki mushkil aasan kare. Chahein to mujhe bata dein, main sun raha hoon.', 'اللہ آپ کی مشکل آسان کرے۔ چاہیں تو مجھے بتا دیں، میں سن رہا ہوں۔', 'अल्लाह आपकी मुश्किल आसान करे। चाहें तो मुझे बता दें, मैं सुन रहा हूं।']] },
    { need: ['khush khushi خوش خوشی खुश खुशी'], me: true, say: [['Yeh sun kar bohat acha laga! Allah aap ko hamesha khush rakhe.', 'یہ سن کر بہت اچھا لگا! اللہ آپ کو ہمیشہ خوش رکھے۔', 'यह सुनकर बहुत अच्छा लगा! अल्लाह आपको हमेशा खुश रखे।']] },
    { need: ['bore bor بور बोर'], me: true, pool: JOKES, lead: ['Chaliye, ek lateefa suniye.', 'چلیے، ایک لطیفہ سنیے۔', 'चलिए, एक लतीफा सुनिए।'] },
    { need: ['neend نیند नींद', 'nahi nahin نہیں नहीं'], say: [['Phone rakh kar aankhein band kar lein aur gehri saans lein. Neend aa jaye gi.', 'فون رکھ کر آنکھیں بند کر لیں اور گہری سانس لیں۔ نیند آ جائے گی۔', 'फोन रखकर आंखें बंद कर लें और गहरी सांस लें। नींद आ जाएगी।']] },
    { need: ['neend نیند नींद'], me: true, say: [['To phir so jaiye. Shab bakhair!', 'تو پھر سو جائیے۔ شب بخیر!', 'तो फिर सो जाइए। शब बखैर!']] },
    { need: ['bhook bhuk بھوک भूख'], me: true, say: [['To kuch kha lijiye. Aur haan, kharcha mujhe bata dijiye ga.', 'تو کچھ کھا لیجیے۔ اور ہاں، خرچہ مجھے بتا دیجیے گا۔', 'तो कुछ खा लीजिए। और हां, खर्चा मुझे बता दीजिएगा।']] },
    { need: ['bimar beemar bukhar tabiyat بیمار بخار طبیعت बीमार बुखार तबीयत'], me: true, say: [['Allah aap ko shifa de. Aaram karein, aur zaroorat ho to doctor ko dikhayein.', 'اللہ آپ کو شفا دے۔ آرام کریں، اور ضرورت ہو تو ڈاکٹر کو دکھائیں۔', 'अल्लाह आपको शिफा दे। आराम करें, और जरूरत हो तो डॉक्टर को दिखाएं।']] },
    { need: ['shabash zabardast kamaal great wah شاباش زبردست کمال واہ शाबाश जबरदस्त कमाल वाह'], max: 5, say: [['Shukriya! Aap ki meharbani.', 'شکریہ! آپ کی مہربانی۔', 'शुक्रिया! आपकी मेहरबानी।']] },
    { need: ['bewaqoof bewakoof pagal nalaiq stupid gadha بیوقوف بے وقوف پاگل نالائق گدھا बेवकूफ पागल नालायक गधा'], max: 6, say: [['Maaf kijiye agar ghalti hui. Main behtar samajhne ki koshish karoon ga.', 'معاف کیجیے اگر غلطی ہوئی۔ میں بہتر سمجھنے کی کوشش کروں گا۔', 'माफ कीजिए अगर गलती हुई। मैं बेहतर समझने की कोशिश करूंगा।']] },
    { need: ['pyar pyaar mohabbat love پیار محبت प्यार मोहब्बत', 'tum aap tumse تم آپ तुम आप'], max: 6, say: [['Shukriya. Main to bas aap ka hisaab rakhne wala munshi hoon.', 'شکریہ۔ میں تو بس آپ کا حساب رکھنے والا منشی ہوں۔', 'शुक्रिया। मैं तो बस आपका हिसाब रखने वाला मुंशी हूं।']] },
    { need: ['sorry maaf maazrat معاف معذرت माफ'], max: 5, say: [['Koi baat nahi.', 'کوئی بات نہیں۔', 'कोई बात नहीं।']] },
    { need: ['dua دعا दुआ'], max: 6, say: [['Allah aap ke liye aasaniyan paida kare. Ameen.', 'اللہ آپ کے لیے آسانیاں پیدا کرے۔ آمین۔', 'अल्लाह आपके लिए आसानियां पैदा करे। आमीन।']] },
    { need: ['haha hahaha hehe ہاہا ہاہاہا हाहा'], say: [['Aap ko hansta sun kar acha laga.', 'آپ کو ہنستا سن کر اچھا لگا۔', 'आपको हंसता सुनकर अच्छा लगा।']] },
    { need: ['garmi garam گرمی گرم गर्मी गर्म'], s: true, max: 8, say: [['Ji, garmi mein pani zyada piyein aur dhoop se bachein.', 'جی، گرمی میں پانی زیادہ پئیں اور دھوپ سے بچیں۔', 'जी, गर्मी में पानी ज्यादा पिएं और धूप से बचें।']] },
    { need: ['sardi thand thandi سردی ٹھنڈ ٹھنڈی सर्दी ठंड ठंडी'], s: true, max: 8, say: [['Sardi mein garam kapre pehnein aur chai ka maza lein.', 'سردی میں گرم کپڑے پہنیں اور چائے کا مزہ لیں۔', 'सर्दी में गर्म कपड़े पहनें और चाय का मजा लें।']] },
    { need: ['barish baarish بارش बारिश'], s: true, max: 8, say: [['Barish ka apna hi maza hai. Bheegne se bachiye ga.', 'بارش کا اپنا ہی مزہ ہے۔ بھیگنے سے بچیے گا۔', 'बारिश का अपना ही मजा है। भीगने से बचिएगा।']] },
    { need: ['mausam barish garmi sardi موسم بارش گرمی سردی मौसम बारिश गर्मी सर्दी'], q: true, fixed: NO_NET },
    { need: ['ghar گھر घर', 'aa aaya agaya aagaya pohanch pohancha pohnch آ آیا آگیا پہنچ پہنچا आ आया आगया पहुंच पहुंचा'], s: true, max: 7, say: [['Khush aamdeed! Ab thora aaram kijiye.', 'خوش آمدید! اب تھوڑا آرام کیجیے۔', 'खुश आमदीद! अब थोड़ा आराम कीजिए।']] },
    { need: ['kaam office daftar dukaan duty کام آفس دفتر دکان ڈیوٹی काम ऑफिस दफ्तर दुकान ड्यूटी', 'ja jaa jana nikal جا جانا نکل जा जाना निकल'], me: true, s: true, say: [['Theek hai. Allah kare din acha guzre.', 'ٹھیک ہے۔ اللہ کرے دن اچھا گزرے۔', 'ठीक है। अल्लाह करे दिन अच्छा गुजरे।']] },
    { need: ['masroof busy مصروف बिजी मसरूफ'], s: true, say: [['Kaam ke sath thora aaram bhi zaroori hai.', 'کام کے ساتھ تھوڑا آرام بھی ضروری ہے۔', 'काम के साथ थोड़ा आराम भी जरूरी है।']] },
    { need: ['dar khauf ghabrahat ghabra ڈر خوف گھبراہٹ گھبرا डर खौफ घबराहट घबरा'], me: true, say: [['Ghabraiye nahi. Gehri saans lijiye, sab theek ho jaye ga.', 'گھبرائیے نہیں۔ گہری سانس لیجیے، سب ٹھیک ہو جائے گا۔', 'घबराइए नहीं। गहरी सांस लीजिए, सब ठीक हो जाएगा।']] },
    { need: ['gussa ghussa غصہ गुस्सा'], me: true, say: [['Thora pani pi lein aur das tak ginein. Gussa thanda ho jaye ga.', 'تھوڑا پانی پی لیں اور دس تک گنیں۔ غصہ ٹھنڈا ہو جائے گا۔', 'थोड़ा पानी पी लें और दस तक गिनें। गुस्सा ठंडा हो जाएगा।']] },
    { need: ['akela akeli tanha tanhai اکیلا اکیلی تنہا تنہائی अकेला अकेली तन्हा तन्हाई'], say: [['Main yahin hoon. Aap mujh se baat kar sakte hain.', 'میں یہیں ہوں۔ آپ مجھ سے بات کر سکتے ہیں۔', 'मैं यहीं हूं। आप मुझसे बात कर सकते हैं।']] },
    { need: ['din دن दिन', 'bura kharab mushkil bekar برا خراب مشکل بیکار बुरा खराब मुश्किल बेकार'], s: true, say: [['Koi baat nahi. Kal ka din behtar hoga, inshallah.', 'کوئی بات نہیں۔ کل کا دن بہتر ہوگا، انشاءاللہ۔', 'कोई बात नहीं। कल का दिन बेहतर होगा, इंशाअल्लाह।']] },
    { need: ['din دن दिन', 'acha achha zabardast behtareen shandar اچھا زبردست بہترین شاندار अच्छा जबरदस्त बेहतरीन शानदार'], s: true, say: [['Yeh to bohat achi baat hai!', 'یہ تو بہت اچھی بات ہے!', 'यह तो बहुत अच्छी बात है!']] },
    { need: ['theek thik ٹھیک ठीक', 'hoon hun ہوں हूं'], s: true, max: 5, say: [['Yeh sun kar acha laga.', 'یہ سن کر اچھا لگا۔', 'यह सुनकर अच्छा लगा।']] },
    { need: ['kahan kahaan کہاں कहां', 'ho ہو हो'], q: true, max: 4, say: [['Yahin hoon, aap ke phone mein.', 'یہیں ہوں، آپ کے فون میں۔', 'यहीं हूं, आपके फोन में।']] },
    { need: ['sun sunn سن सुन', 'rahe raha rahi رہے رہا رہی रहे रहा रही'], q: true, max: 6, say: [['Ji, main sun raha hoon. Bataiye.', 'جی، میں سن رہا ہوں۔ بتائیے۔', 'जी, मैं सुन रहा हूं। बताइए।']] },
    { need: ['dost dosti دوست دوستی दोस्त दोस्ती', 'tum aap mere mera تم آپ میرے میرا तुम आप मेरे मेरा'], max: 7, say: [['Ji bilkul, main aap ka dost bhi hoon aur munshi bhi.', 'جی بالکل، میں آپ کا دوست بھی ہوں اور منشی بھی۔', 'जी बिल्कुल, मैं आपका दोस्त भी हूं और मुंशी भी।']] },
    { need: ['chahiye chahiyein chahiay چاہیے چاہئیں चाहिए', 'paise paisa paisay raqam پیسے پیسہ رقم पैसे पैसा रकम'], say: [['Paise mehnat aur bachat se bante hain. Poochiye: is mahine kitni bachat hui.', 'پیسے محنت اور بچت سے بنتے ہیں۔ پوچھیے: اس مہینے کتنی بچت ہوئی۔', 'पैसे मेहनत और बचत से बनते हैं। पूछिए: इस महीने कितनी बचत हुई।']] },
    { need: ['gana gaana geet گانا گیت गाना गीत'], say: [['Gana to mujhe nahi aata, lekin lateefa zaroor suna sakta hoon.', 'گانا تو مجھے نہیں آتا، لیکن لطیفہ ضرور سنا سکتا ہوں۔', 'गाना तो मुझे नहीं आता, लेकिन लतीफा जरूर सुना सकता हूं।']] },
    { need: ['sunao sunaao سناؤ سناو सुनाओ'], max: 4, pool: SAYINGS },
    { need: ['khabar khabren news score match خبر خبریں نیوز اسکور میچ खबर खबरें न्यूज स्कोर मैच'], q: true, fixed: NO_NET },

    // a little general knowledge; questions only
    { need: ['pakistan پاکستان पाकिस्तान', 'darulhukumat darul capital dar دارالحکومت دار کیپیٹل राजधानी'], q: true, say: [['Pakistan ka dar-ul-hukumat Islamabad hai.', 'پاکستان کا دارالحکومت اسلام آباد ہے۔', 'पाकिस्तान की राजधानी इस्लामाबाद है।']] },
    { need: ['qaumi قومی कौमी', 'zabaan zaban زبان जबान'], q: true, say: [['Pakistan ki qaumi zabaan Urdu hai.', 'پاکستان کی قومی زبان اردو ہے۔', 'पाकिस्तान की कौमी जबान उर्दू है।']] },
    { need: ['qaumi قومی कौमी', 'shayar shair شاعر शायर'], q: true, say: [['Pakistan ke qaumi shayar Allama Muhammad Iqbal hain.', 'پاکستان کے قومی شاعر علامہ محمد اقبال ہیں۔', 'पाकिस्तान के कौमी शायर अल्लामा मुहम्मद इकबाल हैं।']] },
    { need: ['qaumi قومی कौमी', 'khel کھیل खेल'], q: true, say: [['Pakistan ka qaumi khel hockey hai.', 'پاکستان کا قومی کھیل ہاکی ہے۔', 'पाकिस्तान का कौमी खेल हॉकी है।']] },
    { need: ['qaumi قومی कौमी', 'phool پھول फूल'], q: true, say: [['Pakistan ka qaumi phool chambeli hai.', 'پاکستان کا قومی پھول چنبیلی ہے۔', 'पाकिस्तान का कौमी फूल चमेली है।']] },
    { need: ['qaumi قومی कौमी', 'janwar جانور जानवर'], q: true, say: [['Pakistan ka qaumi janwar markhor hai.', 'پاکستان کا قومی جانور مارخور ہے۔', 'पाकिस्तान का कौमी जानवर मारखोर है।']] },
    { need: ['pakistan پاکستان पाकिस्तान', 'bani baani banane بانی बानी'], q: true, say: [['Pakistan ke bani Quaid-e-Azam Muhammad Ali Jinnah hain.', 'پاکستان کے بانی قائد اعظم محمد علی جناح ہیں۔', 'पाकिस्तान के बानी कायदे आजम मुहम्मद अली जिन्ना हैं।']] },
    { need: ['pakistan پاکستان पाकिस्तान', 'bana azad azaad azadi بنا آزاد آزادی बना आजाद आजादी'], q: true, say: [['Pakistan 14 August 1947 ko bana.', 'پاکستان 14 اگست 1947 کو بنا۔', 'पाकिस्तान 14 अगस्त 1947 को बना।']] },
    { need: ['pakistan پاکستان पाकिस्तान', 'shehar shehr sheher شہر शहर', 'bara بڑا बड़ा'], q: true, say: [['Pakistan ka sab se bara shehar Karachi hai.', 'پاکستان کا سب سے بڑا شہر کراچی ہے۔', 'पाकिस्तान का सबसे बड़ा शहर कराची है।']] },
    { need: ['pahar choti پہاڑ چوٹی पहाड़ चोटी', 'ooncha uncha unchi oonchi bara اونچا اونچی بڑا ऊंचा ऊंची बड़ा'], q: true, say: [['Duniya ka sab se ooncha pahar Mount Everest hai, aur Pakistan ka K2.', 'دنیا کا سب سے اونچا پہاڑ ماؤنٹ ایورسٹ ہے، اور پاکستان کا کے ٹو۔', 'दुनिया का सबसे ऊंचा पहाड़ माउंट एवरेस्ट है, और पाकिस्तान का के टू।']] },
    { need: ['suraj سورج सूरज', 'nikalta niklta tulu نکلتا طلوع निकलता'], q: true, say: [['Suraj mashriq se nikalta hai aur maghrib mein doobta hai.', 'سورج مشرق سے نکلتا ہے اور مغرب میں ڈوبتا ہے۔', 'सूरज पूरब से निकलता है और पश्चिम में डूबता है।']] },
    { need: ['hafta hafte haftay ہفتہ ہفتے हफ्ता हफ्ते', 'din دن दिन'], q: true, kitna: true, say: [['Ek hafte mein 7 din hote hain.', 'ایک ہفتے میں 7 دن ہوتے ہیں۔', 'एक हफ्ते में 7 दिन होते हैं।']] },
    { need: ['saal سال साल', 'mahine mahinay maheene مہینے महीने'], q: true, kitna: true, say: [['Ek saal mein 12 mahine hote hain.', 'ایک سال میں 12 مہینے ہوتے ہیں۔', 'एक साल में 12 महीने होते हैं।']] },
    { need: ['saal سال साल', 'din دن दिन'], q: true, kitna: true, say: [['Ek saal mein 365 din hote hain, aur leap ke saal mein 366.', 'ایک سال میں 365 دن ہوتے ہیں، اور لیپ کے سال میں 366۔', 'एक साल में 365 दिन होते हैं, और लीप के साल में 366।']] },
    { need: ['din دن दिन', 'ghante ghantay ghanta گھنٹے گھنٹہ घंटे घंटा'], q: true, kitna: true, say: [['Ek din mein 24 ghante hote hain.', 'ایک دن میں 24 گھنٹے ہوتے ہیں۔', 'एक दिन में 24 घंटे होते हैं।']] },
    { need: ['ghante ghantay ghanta گھنٹے گھنٹہ घंटे घंटा', 'minute mint منٹ मिनट'], q: true, kitna: true, say: [['Ek ghante mein 60 minute hote hain.', 'ایک گھنٹے میں 60 منٹ ہوتے ہیں۔', 'एक घंटे में 60 मिनट होते हैं।']] },
    { need: ['kilo kilogram کلو کلوگرام किलो', 'gram گرام ग्राम'], q: true, kitna: true, say: [['Ek kilo mein 1000 gram hote hain.', 'ایک کلو میں 1000 گرام ہوتے ہیں۔', 'एक किलो में 1000 ग्राम होते हैं।']] },
    { need: ['kilometer km کلومیٹر किलोमीटर', 'meter میٹر मीटर'], q: true, kitna: true, say: [['Ek kilometer mein 1000 meter hote hain.', 'ایک کلومیٹر میں 1000 میٹر ہوتے ہیں۔', 'एक किलोमीटर में 1000 मीटर होते हैं।']] },
    { need: ['foot fut فٹ फुट', 'inch انچ इंच'], q: true, kitna: true, say: [['Ek foot mein 12 inch hote hain.', 'ایک فٹ میں 12 انچ ہوتے ہیں۔', 'एक फुट में 12 इंच होते हैं।']] },
    { need: ['darjan dozen درجن दर्जन'], q: true, kitna: true, say: [['Ek darjan mein 12 cheezein hoti hain.', 'ایک درجن میں 12 چیزیں ہوتی ہیں۔', 'एक दर्जन में 12 चीजें होती हैं।']] },
    { need: ['tola tole تولہ تولے तोला तोले', 'gram گرام ग्राम'], q: true, kitna: true, say: [['Ek tola taqreeban 11.66 gram ka hota hai.', 'ایک تولہ تقریباً 11.66 گرام کا ہوتا ہے۔', 'एक तोला लगभग 11.66 ग्राम का होता है।']] }
  ];
  CHAT.forEach(function (c) { c.need = c.need.map(W); });
  var DAY_WORD = W('din دن दिन day');
  var WAS = W('tha thi the thay تھا تھی تھے था थी थे');
  var DAYS_RO = ['Itwar', 'Peer', 'Mangal', 'Budh', 'Jumerat', 'Juma', 'Hafta'];

  /** Small talk, moods, jokes, riddles and a few facts. Returns null when none fit. */
  function chat(db, tokens, question, now) {
    var ctx = db.ctx, kitna = has(tokens, 'kitna');
    ctx.turn = (ctx.turn || 0) + 1;

    // "aaj kaun sa din hai", "kal kaun sa din hoga"
    if (question && !kitna && hit(tokens, DAY_WORD) && hasAny(tokens, ['aaj', 'kal']) && has(tokens, 'kaun')) {
      var past = hit(tokens, WAS), d = has(tokens, 'kal') ? addDays(now, past ? -1 : 1) : now, o = { weekday: 'long' };
      var w = has(tokens, 'kal') ? (past ? ['Kal', 'کل', 'कल', 'tha', 'تھا', 'था'] : ['Kal', 'کل', 'कल', 'ho ga', 'ہو گا', 'होगा']) : ['Aaj', 'آج', 'आज', 'hai', 'ہے', 'है'];
      return R(w[0] + ' ' + DAYS_RO[d.getDay()] + ' ' + w[3] + '.', w[1] + ' ' + d.toLocaleDateString('ur-PK', o) + ' ' + w[4] + '۔', w[2] + ' ' + d.toLocaleDateString('hi-IN', o) + ' ' + w[5] + '।');
    }

    for (var i = 0; i < CHAT.length; i++) {
      var c = CHAT[i];
      if (c.q && !question) continue;
      if (c.s && question) continue;
      if (!c.kitna && kitna) continue;                     // "kitna" questions belong to the hisaab
      if (c.me && !aboutMe(tokens)) continue;
      if (c.max && tokens.length > c.max) continue;
      if (!c.need.every(function (set) { return hit(tokens, set); })) continue;

      if (c.fixed) return c.fixed;
      if (c.name) {
        var nm = db.profile.name;
        return nm ? R('Ji bilkul, aap ' + nm + ' hain.', 'جی بالکل، آپ ' + nm + ' ہیں۔', 'जी बिल्कुल, आप ' + nm + ' हैं।') : SAY.noName;
      }
      if (c.riddle) {
        var k = ctx.turn % RIDDLES.length;
        ctx.pending = { type: 'riddle', i: k, ts: now.getTime() };
        return R.apply(null, RIDDLES[k].q);
      }
      var list = c.pool || c.say, line = list[ctx.turn % list.length];
      return c.lead ? join(R.apply(null, c.lead), R.apply(null, line)) : R.apply(null, line);
    }
    // a bare "theek hai" / "acha" / "nahi" with nothing pending
    if (tokens.length <= 2 && tokens.every(function (t) { return t.c === 'yes' || t.c === 'hai'; })) return R('Ji.', 'جی۔', 'जी।');
    if (tokens.length <= 2 && tokens.every(function (t) { return t.c === 'no'; })) return SAY.okShort;
    return null;
  }

  // ---------------------------------------------------- replies you teach ---

  /** "jab main kahun chai to kehna abhi laya": the user scripting a reply. */
  function teachReply(db, tokens) {
    var say = -1, then = -1;
    tokens.forEach(function (t, i) {
      if (t.c === 'isay' && say < 0) say = i;
      if (say >= 0 && then < 0 && i > say && (t.n === 'to' || t.n === normalise('تو') || t.n === normalise('तो') || t.n === 'tou')) then = i;
    });
    if (say < 0 || then < 0) return null;
    var keys = tokens.slice(0, then).filter(function (t) { return t.c !== 'isay' && t.c !== 'stop' && t.n !== 'jab' && t.n !== normalise('جب') && t.n !== normalise('जब'); }).map(function (t) { return t.n; });
    var words = tokens.slice(then + 1).filter(function (t) { return t.c !== 'yousay'; }).map(function (t) { return t.raw; });
    if (!keys.length || !words.length) return null;
    db.learned.replies = db.learned.replies.filter(function (r) { return r.keys.join(' ') !== keys.join(' '); });
    db.learned.replies.push({ keys: keys, say: words.join(' ') });
    return R('Theek hai, ab aap yeh kahein ge to main yahi kahoon ga.', 'ٹھیک ہے، اب آپ یہ کہیں گے تو میں یہی کہوں گا۔', 'ठीक है, अब आप यह कहेंगे तो मैं यही कहूंगा।');
  }

  function taughtReply(db, tokens) {
    if (has(tokens, 'isay') || firstNum(tokens) !== null) return null;   // a lesson, or hisaab
    var said = tokens.map(function (t) { return t.n; }), best = null;
    db.learned.replies.forEach(function (r) {
      var all = r.keys.every(function (k) { return said.indexOf(k) !== -1; });
      if (all && said.length <= r.keys.length + 3 && (!best || r.keys.length > best.keys.length)) best = r;
    });
    return best ? R(best.say.charAt(0).toUpperCase() + best.say.slice(1), best.say, best.say) : null;
  }

  // ---------------------------------------------------------------- main ---

  function route(text, db, now, depth) {
    var tokens = tokenise(text), ctx = db.ctx, t = now.getTime();
    if (!tokens.length) return { said: SAY.unknown };
    var question = isQuestion(text, tokens), amount = firstNum(tokens), said = null;

    if (depth === 0) {
      var pend = ctx.pending && t - ctx.pending.ts < RECENT ? ctx.pending : null;
      ctx.pending = null;
      if (pend) pend.heard = text;
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

    // "yeh jawab ghalat tha": mark the last exchange in the record, so it can be found and fixed later
    if (has(tokens, 'ghalat') && hit(tokens, ANSWER_WORD) && amount === null) {
      if (db.log.length) db.log[db.log.length - 1].bad = true;
      return { said: R('Maaf kijiye. Main ne nishan laga diya hai ke yeh jawab ghalat tha.', 'معاف کیجیے۔ میں نے نشان لگا دیا ہے کہ یہ جواب غلط تھا۔', 'माफ कीजिए। मैंने निशान लगा दिया है कि यह जवाब गलत था।'), mark: true };
    }

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

    said = taughtReply(db, tokens) || sum(tokens, text);
    if (said) return { said: said };
    if (amount === null && !isRemember(tokens) && myNameAt(tokens) < 0) {
      said = chat(db, tokens, question, now);
      if (said) return { said: said };
    }

    if (!question) {
      said = teachReply(db, tokens) || teach(db, tokens) || setName(db, tokens);
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
      var finance = hasAny(tokens, ['kharch', 'spendv', 'loan', 'bachat', 'incomev', 'incomen', 'zyada', 'hisaab', 'paise', 'detail']);
      // "kitna" alone usually means money, unless it is about something the user told us
      if (!finance && has(tokens, 'kitna')) finance = !findNote(db, tokens.filter(function (tk) { return !tk.cat && !tk.num; }));
      if (!finance) {
        if (hasAny(tokens, ['tareekh', 'waqt'])) return { said: answerClock(tokens, now) };
        if (myNameAt(tokens) >= 0) {
          var nm = db.profile.name;
          return { said: nm ? R('Aap ka naam ' + nm + ' hai.', 'آپ کا نام ' + nm + ' ہے۔', 'आपका नाम ' + nm + ' है।') : SAY.noName };
        }
        said = smallTalk(tokens) || answerNote(db, tokens);
        if (said) return { said: said };
        if (!firstCat(tokens)) { ctx.pending = { type: 'answer', text: text, ts: t }; return { said: nextOf(db, DONT_KNOW, 'unsure'), weak: true }; }
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

    // What people tell it in passing is kept quietly, the way a person would remember it:
    // no announcement, just an ordinary reply. It can be asked about later.
    var telling = hasAny(tokens, ['hai', 'tha', 'hua']) || tokens.length >= 5;
    if (tokens.length >= 3 && telling) {
      keep(db, text, now, true);
      return { said: amount !== null && hit(tokens, MINE) ? agree(db) : nextOf(db, LISTENING, 'heard'), weak: true };
    }
    if (tokens.length >= 3 || tokens.some(function (tk) { return !!tk.c || !!tk.cat; })) return { said: nextOf(db, LISTENING, 'heard'), weak: true };
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
    return { show: r.said.ro, speak: r.said, action: r.action || null, weak: !!r.weak, mark: !!r.mark, changed: before !== lasting(db) };
  }

  // -------------------------------------------------------------- accent ---

  /** 2: understood. 1: it had to ask what was meant. 0: no idea. */
  function grade(reply, db) {
    if (reply.speak === SAY.unknown) return 0;
    return reply.weak || (db.ctx.pending && db.ctx.pending.type === 'kind') ? 1 : 2;
  }

  /** Number of single-letter edits between two words, capped just above `max`. */
  function editDistance(a, b, max) {
    if (Math.abs(a.length - b.length) > max) return max + 1;
    var prev = [], i, j;
    for (j = 0; j <= b.length; j++) prev[j] = j;
    for (i = 1; i <= a.length; i++) {
      var row = [i];
      for (j = 1; j <= b.length; j++) {
        row[j] = Math.min(prev[j] + 1, row[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
      }
      prev = row;
    }
    return prev[b.length];
  }

  /**
   * Is `right` the same sentence as `wrong` with one misheard word put right?
   * Only when exactly one word differs, the wrong one means nothing to us and
   * is not a word this user has used before, the two sound alike, and the
   * right one is harmless (a category or a plain hisaab word, never an
   * amount, a delete or a hang-up).
   */
  function mishearing(db, wrong, right) {
    var a = tokenise(wrong), b = tokenise(right), at = -1;
    if (a.length !== b.length) return null;
    for (var i = 0; i < a.length; i++) {
      if (a[i].n === b[i].n) continue;
      if (at !== -1) return null;
      at = i;
    }
    if (at === -1) return null;
    var w = a[at], r = b[at];
    if (w.num || w.c || w.cat || NUMOF[w.n] || !(r.cat || FUZZY_OK[r.c])) return null;
    if (w.n.length < 4 || editDistance(w.n, r.n, 2) > 2) return null;
    var used = db.entries.some(function (e) {
      return e.personKey === w.n || normalise(e.item || '').split(/\s+/).indexOf(w.n) !== -1;
    });
    return used ? null : { heard: w, meant: r };
  }

  var LOG_LIMIT = 400;
  /**
   * Keep a record of the conversation inside the saved data: what the
   * recogniser heard (all its guesses), which one was used, what was said
   * back, and whether that reply was a real answer. It travels with the
   * backup file, so a bad answer can be traced to the exact words heard.
   */
  function record(db, guesses, pick, reply, now) {
    if (reply.mark) return;                                // the complaint itself is not an exchange
    var entry = { at: now.toISOString(), heard: guesses[pick], said: reply.show };
    if (guesses.length > 1) entry.guesses = guesses;
    if (pick > 0) entry.picked = pick;
    if (reply.speak === SAY.unknown) entry.result = 'not understood';
    else if (reply.weak) entry.result = 'no real answer';
    db.log.push(entry);
    if (db.log.length > LOG_LIMIT) db.log.splice(0, db.log.length - LOG_LIMIT);
  }

  /**
   * The speech recogniser offers several guesses at what was said, best first.
   * Its top guess is used unless another guess repairs a misheard word in it,
   * or the top guess makes no sense and another does. A word misheard the
   * same way twice is learned, so this speaker's way of saying it is
   * understood from then on.
   */
  function handleBest(texts, db, now) {
    now = now || new Date();
    var guesses = (Array.isArray(texts) ? texts : [texts]).map(function (x) { return String(x == null ? '' : x).trim(); })
      .filter(function (x, i, all) { return x && all.indexOf(x) === i; }).slice(0, 5);
    if (!guesses.length) return handle('', db, now);
    migrate(db);
    LEARN = db.learned;
    var grades = guesses.map(function (g) {
      var copy = JSON.parse(JSON.stringify(db));
      return grade(handle(g, copy, now), copy);
    });
    LEARN = db.learned;
    var pick = 0, fix = null;
    for (var i = 1; i < guesses.length && !fix; i++) {
      if (grades[i] < 2) continue;
      fix = mishearing(db, guesses[0], guesses[i]);
      if (fix) pick = i;
    }
    if (!fix && grades[0] < 2) {                           // top guess is no use: take the best of the rest
      grades.forEach(function (g, k) { if (g > grades[pick]) pick = k; });
    }
    if (fix) {
      var seen = db.learned.heard[fix.heard.n];
      if (!seen || seen.as !== fix.meant.n) seen = db.learned.heard[fix.heard.n] = { as: fix.meant.n, n: 0 };
      if (++seen.n >= 2) {
        db.learned.alias[fix.heard.n] = { c: fix.meant.c || null, cat: fix.meant.cat || null };
        delete db.learned.heard[fix.heard.n];
      }
    }
    var reply = handle(guesses[pick], db, now);
    record(db, guesses, pick, reply, now);
    reply.changed = true;                                  // the record, and anything learned, has to be saved
    return reply;
  }

  return { handle: handle, handleBest: handleBest, greet: greet, emptyDb: emptyDb, isDb: isDb, lasting: lasting, tokenise: tokenise,
           normalise: normalise, balances: balances, labelOf: labelOf, CATS: CATS, iso: iso };
});
