package com.boltamunshi.app

import java.util.Locale

/**
 * Roman Urdu in Hindi letters (देवनागरी), so a Hindi voice reads it with its own
 * accent instead of reading Latin letters the English way. Common words come from
 * a word list; names and other words are spelled out by sound. Numbers, Urdu
 * script and punctuation pass through as they are.
 */
object Devanagari {
    private val words = mapOf(
        "aa" to "आ", "aaj" to "आज", "aaj" to "आज", "aakhri" to "आख़िरी", "aamdani" to "आमदनी", "aap" to "आप", "aaye" to "आए",
        "ab" to "अब", "abhi" to "अभी", "adaigi" to "अदायगी", "aisa" to "ऐसा", "aisi" to "ऐसी", "andaazan" to "अंदाज़न", "app" to "ऐप",
        "aur" to "और", "bachat" to "बचत", "badalna" to "बदलना", "barabar" to "बराबर", "bata" to "बता", "bheje" to "भेजे", "bhi" to "भी",
        "bill" to "बिल", "bijli" to "बिजली", "chhor" to "छोड़", "chai" to "चाय", "de" to "दे", "dein" to "दें", "dene" to "देने", "di" to "दी",
        "diya" to "दिया", "diye" to "दिए", "do" to "दो", "doon" to "दूं", "eent" to "ईंट", "entries" to "एंट्रियां", "entry" to "एंट्री",
        "gaye" to "गए", "gayi" to "गई", "gayin" to "गईं", "ghalti" to "ग़लती", "haan" to "हां", "hai" to "है", "hain" to "हैं", "hata" to "हटा",
        "hi" to "ही", "hisaab" to "हिसाब", "hissa" to "हिस्सा", "ho" to "हो", "hua" to "हुआ", "hui" to "हुई", "is" to "इस", "jagah" to "जगह",
        "jaise" to "जैसे", "ka" to "का", "kaam" to "काम", "kahiye" to "कहिए", "kal" to "कल", "kar" to "कर", "karne" to "करने", "kaun" to "कौन",
        "ke" to "के", "khali" to "ख़ाली", "kharcha" to "ख़र्चा", "kharche" to "ख़र्चे", "khareed" to "ख़रीद", "khanay" to "खाने", "ki" to "की",
        "kijiye" to "कीजिए", "kisi" to "किसी", "kitne" to "कितने", "kiye" to "किए", "ko" to "को", "koi" to "कोई", "kuch" to "कुछ",
        "kya" to "क्या", "lao" to "लाओ", "lene" to "लेने", "li" to "ली", "likh" to "लिख", "likha" to "लिखा", "liya" to "लिया", "liye" to "लिए",
        "logon" to "लोगों", "maal" to "माल", "mahine" to "महीने", "mein" to "में", "mila" to "मिला", "mili" to "मिली", "milti" to "मिलती",
        "mit" to "मिट", "mita" to "मिटा", "mitane" to "मिटाने", "mitaya" to "मिटाया", "mitayi" to "मिटाई", "munafa" to "मुनाफ़ा",
        "naam" to "नाम", "nahi" to "नहीं", "ne" to "ने", "nuqsan" to "नुक़सान", "par" to "पर", "pehle" to "पहले", "pese" to "पैसे",
        "petrol" to "पेट्रोल", "raqam" to "रक़म", "rate" to "रेट", "rupay" to "रुपये", "saal" to "साल", "sa" to "सा", "sath" to "साथ",
        "se" to "से", "si" to "सी", "stock" to "स्टॉक", "supplier" to "सप्लायर", "tak" to "तक", "tareekh" to "तारीख़", "tayyar" to "तैयार",
        "tha" to "था", "theek" to "ठीक", "to" to "तो", "total" to "टोटल", "udhar" to "उधार", "wali" to "वाली", "wapas" to "वापस",
        "ya" to "या", "yeh" to "यह", "bikri" to "बिक्री", "safar" to "सफ़र", "kiraye" to "किराए", "ilaj" to "इलाज", "parhai" to "पढ़ाई",
        "khairat" to "ख़ैरात", "saman" to "सामान", "doosre" to "दूसरे", "zaati" to "ज़ाती", "ghar" to "घर", "fees" to "फ़ीस", "mobile" to "मोबाइल",
        "shopping" to "शॉपिंग", "banwai" to "बनवाई", "sale" to "सेल", "purchase" to "परचेज़", "pehle" to "पहले", "kaun" to "कौन",
        "January" to "जनवरी", "February" to "फ़रवरी", "March" to "मार्च", "April" to "अप्रैल", "May" to "मई", "June" to "जून",
        "July" to "जुलाई", "August" to "अगस्त", "September" to "सितंबर", "October" to "अक्टूबर", "November" to "नवंबर", "December" to "दिसंबर",
        // numbers, spelled the way they are said
        "sifar" to "शून्य", "ek" to "एक", "do" to "दो", "teen" to "तीन", "chaar" to "चार", "paanch" to "पांच", "chhe" to "छे", "saat" to "सात",
        "aath" to "आठ", "nau" to "नौ", "das" to "दस", "gyarah" to "ग्यारह", "barah" to "बारह", "terah" to "तेरह", "chaudah" to "चौदह", "pandrah" to "पंद्रह",
        "solah" to "सोलह", "satrah" to "सत्रह", "atharah" to "अठारह", "unnees" to "उन्नीस", "bees" to "बीस", "ikkees" to "इक्कीस", "baees" to "बाईस", "teyees" to "तेईस",
        "chaubees" to "चौबीस", "pachees" to "पच्चीस", "chhabbees" to "छब्बीस", "sattaees" to "सत्ताईस", "athaees" to "अट्ठाईस", "untees" to "उनतीस", "tees" to "तीस", "iktees" to "इकतीस",
        "battees" to "बत्तीस", "tentees" to "तैंतीस", "chauntees" to "चौंतीस", "paintees" to "पैंतीस", "chhattees" to "छत्तीस", "saintees" to "सैंतीस", "artees" to "अड़तीस", "untaalees" to "उनतालीस",
        "chaalees" to "चालीस", "iktaalees" to "इकतालीस", "bayaalees" to "बयालीस", "taintaalees" to "तैंतालीस", "chavaalees" to "चवालीस", "paintaalees" to "पैंतालीस", "chhiyaalees" to "छियालीस", "saintaalees" to "सैंतालीस",
        "artaalees" to "अड़तालीस", "unchaas" to "उनचास", "pachaas" to "पचास", "ikyaavan" to "इक्यावन", "baavan" to "बावन", "tirpan" to "तिरपन", "chavvan" to "चव्वन", "pachpan" to "पचपन",
        "chhappan" to "छप्पन", "sattaavan" to "सत्तावन", "athaavan" to "अट्ठावन", "unsath" to "उनसठ", "saath" to "साठ", "iksath" to "इकसठ", "baasath" to "बासठ", "tirsath" to "तिरसठ",
        "chaunsath" to "चौंसठ", "painsath" to "पैंसठ", "chhiyaasath" to "छियासठ", "sarsath" to "सड़सठ", "arsath" to "अड़सठ", "unhattar" to "उनहत्तर", "sattar" to "सत्तर", "ikhattar" to "इकहत्तर",
        "bahattar" to "बहत्तर", "tihattar" to "तिहत्तर", "chauhattar" to "चौहत्तर", "pachattar" to "पचहत्तर", "chhihattar" to "छिहत्तर", "sathattar" to "सतहत्तर", "athattar" to "अठहत्तर", "unaasi" to "उनासी",
        "assi" to "अस्सी", "ikyaasi" to "इक्यासी", "bayaasi" to "बयासी", "tiraasi" to "तिरासी", "chauraasi" to "चौरासी", "pachaasi" to "पचासी", "chhiyaasi" to "छियासी", "sattaasi" to "सत्तासी",
        "athaasi" to "अट्ठासी", "nawaasi" to "नवासी", "nawway" to "नव्वे", "ikaanway" to "इक्यानवे", "baanway" to "बानवे", "tiraanway" to "तिरानवे", "chauraanway" to "चौरानवे", "pachaanway" to "पचानवे",
        "chhiyaanway" to "छियानवे", "sattaanway" to "सत्तानवे", "athaanway" to "अट्ठानवे", "ninyaanway" to "निन्यानवे", "sau" to "सौ", "hazaar" to "हज़ार", "lakh" to "लाख", "crore" to "करोड़",
        "point" to "पॉइंट", "aadha" to "आधा",
        "parson" to "परसों", "pichhle" to "पिछले", "assalam" to "अस्सलाम", "alaikum" to "अलैकुम", "o" to "ओ", "wa" to "व",
        "bataiye" to "बताइए", "main" to "मैं", "munshi" to "मुंशी", "hoon" to "हूं", "shukriya" to "शुक्रिया", "khuda" to "ख़ुदा",
        "hafiz" to "हाफ़िज़", "allah" to "अल्लाह", "dobara" to "दोबारा", "bolein" to "बोलें", "samajh" to "समझ", "aaya" to "आया",
        "acha" to "अच्छा", "gas" to "गैस", "pani" to "पानी", "khana" to "खाना", "sabzi" to "सब्ज़ी", "rashan" to "राशन", "aata" to "आटा",
        "cheeni" to "चीनी", "anday" to "अंडे", "dawai" to "दवाई", "doctor" to "डॉक्टर", "kiraya" to "किराया", "load" to "लोड",
        "internet" to "इंटरनेट", "kapre" to "कपड़े", "school" to "स्कूल", "rickshaw" to "रिक्शा", "bus" to "बस", "gaari" to "गाड़ी",
        "bike" to "बाइक", "tankhwah" to "तनख़्वाह", "qarz" to "क़र्ज़", "dukaan" to "दुकान", "phal" to "फल", "gosht" to "गोश्त",
        "doodh" to "दूध", "ghee" to "घी", "marammat" to "मरम्मत", "zaroor" to "ज़रूर", "bilkul" to "बिल्कुल", "kaise" to "कैसे",
        "point" to "पॉइंट",
        // everyday words and names, written the way they are said
        "aadmi" to "आदमी", "aahista" to "आहिस्ता", "aamdeed" to "आमदीद", "aana" to "आना", "aane" to "आने", "aankhein" to "आंखें", "aao" to "आओ", "aaram" to "आराम",
        "aasan" to "आसान", "aasaniyan" to "आसानियां", "aate" to "आते", "aayega" to "आएगा", "aayegi" to "आएगी", "abbu" to "अब्बू", "achi" to "अच्छी", "adnan" to "अदनान",
        "afsos" to "अफ़सोस", "agar" to "अगर", "agle" to "अगले", "allama" to "अल्लामा", "ameen" to "आमीन", "ammi" to "अम्मी", "amna" to "आमना", "andar" to "अंदर",
        "apna" to "अपना", "apne" to "अपने", "asif" to "आसिफ़", "awaaz" to "आवाज़", "ayesha" to "आयशा", "azam" to "आज़म", "baad" to "बाद", "baat" to "बात",
        "baatein" to "बातें", "bacha" to "बचा", "bachaya" to "बचाया", "bachein" to "बचें", "bachiye" to "बचिए", "bachne" to "बचने", "bachon" to "बच्चों", "backup" to "बैकअप",
        "badalni" to "बदलनी", "bahar" to "बाहर", "bajri" to "बजरी", "bakhair" to "बख़ैर", "bana" to "बना", "banaya" to "बनाया", "band" to "बंद", "bani" to "बानी",
        "banta" to "बनता", "bante" to "बनते", "banti" to "बनती", "baqi" to "बाक़ी", "bara" to "बड़ा", "barh" to "बढ़", "bari" to "बड़ी", "barish" to "बारिश",
        "bas" to "बस", "batana" to "बताना", "batao" to "बताओ", "bazaar" to "बाज़ार", "bech" to "बेच", "behtar" to "बेहतर", "bhaao" to "भाव", "bhabhi" to "भाभी",
        "bhai" to "भाई", "bheegne" to "भीगने", "bhool" to "भूल", "bohat" to "बहुत", "bol" to "बोल", "bola" to "बोला", "boliye" to "बोलिए", "bonus" to "बोनस",
        "bori" to "बोरी", "budh" to "बुध", "bulana" to "बुलाना", "bushra" to "बुशरा", "buzurg" to "बुज़ुर्ग", "carton" to "कार्टन", "cement" to "सीमेंट", "chabi" to "चाबी",
        "chacha" to "चाचा", "chadar" to "चादर", "chahein" to "चाहें", "chahiye" to "चाहिए", "chahiyein" to "चाहिएं", "chal" to "चल", "chale" to "चले", "chali" to "चली",
        "chaliye" to "चलिए", "chalne" to "चलने", "chambeli" to "चमेली", "chanda" to "चंदा", "charge" to "चार्ज", "chawal" to "चावल", "cheezein" to "चीज़ें", "chhatri" to "छतरी",
        "chhoriye" to "छोड़िए", "chhote" to "छोटे", "code" to "कोड", "daal" to "दाल", "daant" to "दांत", "dafa" to "दफ़ा", "dar" to "दार", "darjan" to "दर्जन",
        "darwaza" to "दरवाज़ा", "darya" to "दरिया", "dena" to "देना", "der" to "देर", "dhobi" to "धोबी", "dhoop" to "धूप", "diesel" to "डीज़ल", "dihari" to "दिहाड़ी",
        "dijiye" to "दीजिए", "dikhayein" to "दिखाएं", "dil" to "दिल", "dila" to "दिला", "din" to "दिन", "doobta" to "डूबता", "dost" to "दोस्त", "dosti" to "दोस्ती",
        "downloads" to "डाउनलोड्स", "duniya" to "दुनिया", "e" to "ए", "eid" to "ईद", "everest" to "एवरेस्ट", "fahad" to "फ़हद", "faisal" to "फ़ैसल", "fatima" to "फ़ातिमा",
        "folder" to "फ़ोल्डर", "foot" to "फ़ुट", "ga" to "गा", "gana" to "गाना", "garam" to "गरम", "garmi" to "गर्मी", "gaya" to "गया", "geela" to "गीला",
        "gehri" to "गहरी", "ghabraiye" to "घबराइए", "ghante" to "घंटे", "ghaur" to "ग़ौर", "gi" to "गी", "ginein" to "गिनें", "grahak" to "ग्राहक", "gram" to "ग्राम",
        "gussa" to "ग़ुस्सा", "guzre" to "गुज़रे", "haar" to "हार", "hafte" to "हफ़्ते", "haji" to "हाजी", "halka" to "हल्का", "hamesha" to "हमेशा", "hamza" to "हमज़ा",
        "hansta" to "हंसता", "hina" to "हिना", "hmm" to "हम्म", "hockey" to "हॉकी", "hoga" to "होगा", "hogi" to "होगी", "hone" to "होने", "hota" to "होता",
        "hote" to "होते", "hoti" to "होती", "hukumat" to "हुकूमत", "iftari" to "इफ़्तारी", "imran" to "इमरान", "inch" to "इंच", "insaan" to "इंसान", "inshallah" to "इंशाअल्लाह",
        "iqbal" to "इक़बाल", "islamabad" to "इस्लामाबाद", "itna" to "इतना", "itwar" to "इतवार", "ja" to "जा", "jaana" to "जाना", "jaiye" to "जाइए", "jaldi" to "जल्दी",
        "jama" to "जमा", "janti" to "जानती", "janwar" to "जानवर", "jata" to "जाता", "jate" to "जाते", "jati" to "जाती", "jaunga" to "जाऊंगा", "jawab" to "जवाब",
        "jaye" to "जाए", "jayega" to "जाएगा", "jazak" to "जज़ाक", "ji" to "जी", "jinnah" to "जिन्नाह", "jis" to "जिस", "jitna" to "जितना", "jitni" to "जितनी",
        "jo" to "जो", "joote" to "जूते", "jumeraat" to "जुमेरात", "jumle" to "जुमले", "jumme" to "जुम्मे", "kaat" to "काट", "kab" to "कब", "kabhi" to "कभी",
        "kaha" to "कहा", "kahan" to "कहां", "kahin" to "कहीं", "kam" to "कम", "kamran" to "कामरान", "karachi" to "कराची", "kare" to "करे", "karega" to "करेगा",
        "karein" to "करें", "karna" to "करना", "karni" to "करनी", "karoon" to "करूं", "karwana" to "करवाना", "karwani" to "करवानी", "keh" to "कह", "kha" to "खा",
        "khala" to "ख़ाला", "kharab" to "ख़राब", "khatam" to "ख़त्म", "khel" to "खेल", "khud" to "ख़ुद", "khush" to "ख़ुश", "khushi" to "ख़ुशी", "kilo" to "किलो",
        "kilometer" to "किलोमीटर", "kiran" to "किरन", "kis" to "किस", "kitabain" to "किताबें", "kitni" to "कितनी", "koshish" to "कोशिश", "laga" to "लगा", "lage" to "लगे",
        "lagne" to "लगने", "lakri" to "लकड़ी", "lateefa" to "लतीफ़ा", "le" to "ले", "leap" to "लीप", "lein" to "लें", "lekin" to "लेकिन", "lena" to "लेना",
        "lete" to "लेते", "lijiye" to "लीजिए", "likhna" to "लिखना", "lo" to "लो", "maaf" to "माफ़", "magar" to "मगर", "maghrib" to "मग़रिब", "mamu" to "मामू",
        "mandi" to "मंडी", "mangal" to "मंगल", "mareez" to "मरीज़", "markhor" to "मारख़ोर", "maryam" to "मरयम", "mashallah" to "माशाअल्लाह", "mashriq" to "मशरिक़", "mat" to "मत",
        "maza" to "मज़ा", "mazdoor" to "मज़दूर", "mazdoori" to "मज़दूरी", "meetha" to "मीठा", "meharbani" to "मेहरबानी", "mehfooz" to "महफ़ूज़", "mehmaan" to "मेहमान", "mehnat" to "मेहनत",
        "mehngai" to "महंगाई", "mehwish" to "महविश", "mera" to "मेरा", "mere" to "मेरे", "meri" to "मेरी", "meter" to "मीटर", "mic" to "माइक", "milte" to "मिलते",
        "minute" to "मिनट", "mistri" to "मिस्त्री", "motor" to "मोटर", "mount" to "माउंट", "mubarak" to "मुबारक", "muhammad" to "मुहम्मद", "mujh" to "मुझ", "mujhe" to "मुझे",
        "murghi" to "मुर्ग़ी", "mushkil" to "मुश्किल", "naai" to "नाई", "nadia" to "नादिया", "naveed" to "नवीद", "naye" to "नए", "neend" to "नींद", "nikaal" to "निकाल",
        "nikalta" to "निकलता", "nikalte" to "निकलते", "ooncha" to "ऊंचा", "order" to "ऑर्डर", "paas" to "पास", "package" to "पैकेज", "pahar" to "पहाड़", "paheli" to "पहेली",
        "paida" to "पैदा", "paisay" to "पैसे", "paise" to "पैसे", "pakistan" to "पाकिस्तान", "pakka" to "पक्का", "pakki" to "पक्की", "panchar" to "पंक्चर", "paon" to "पांव",
        "parega" to "पड़ेगा", "pata" to "पता", "ped" to "पेड़", "pehli" to "पहली", "pehnein" to "पहनें", "phailane" to "फैलाने", "phelao" to "फैलाओ", "phir" to "फिर",
        "phone" to "फ़ोन", "phool" to "फूल", "pi" to "पी", "pichli" to "पिछली", "piyein" to "पिएं", "piyen" to "पिएं", "poocha" to "पूछा", "poochiye" to "पूछिए",
        "pressure" to "प्रेशर", "program" to "प्रोग्राम", "pyaz" to "प्याज़", "qadar" to "क़दर", "qaidon" to "क़ैदों", "qatra" to "क़तरा", "qaumi" to "क़ौमी", "quaid" to "क़ायद",
        "qulfi" to "क़ुल्फ़ी", "raat" to "रात", "rabia" to "राबिया", "raha" to "रहा", "rahe" to "रहे", "rahi" to "रही", "rait" to "रेत", "rakh" to "रख",
        "rakhe" to "रखे", "rakhi" to "रखी", "rakhna" to "रखना", "rakhne" to "रखने", "rakhti" to "रखती", "ramzan" to "रमज़ान", "rang" to "रंग", "rash" to "रश",
        "rashid" to "राशिद", "rehti" to "रहती", "rozana" to "रोज़ाना", "ruk" to "रुक", "saaf" to "साफ़", "saans" to "सांस", "sab" to "सब", "sabr" to "सब्र",
        "sadqa" to "सदक़ा", "sahab" to "साहब", "saima" to "सायमा", "sajid" to "साजिद", "sakte" to "सकते", "sakti" to "सकती", "samajhne" to "समझने", "sana" to "सना",
        "sara" to "सारा", "sardi" to "सर्दी", "sariya" to "सरिया", "sasta" to "सस्ता", "seekh" to "सीख", "sehat" to "सेहत", "sehri" to "सहरी", "shaadi" to "शादी",
        "shab" to "शब", "shabash" to "शाबाश", "shagird" to "शागिर्द", "shahid" to "शाहिद", "shayad" to "शायद", "shayar" to "शायर", "shazia" to "शाज़िया", "shehar" to "शहर",
        "shifa" to "शिफ़ा", "shukar" to "शुक्र", "sikhana" to "सिखाना", "sir" to "सर", "so" to "सो", "soch" to "सोच", "soiye" to "सोइए", "somwar" to "सोमवार",
        "subah" to "सुबह", "sukhata" to "सुखाता", "sun" to "सुन", "suna" to "सुना", "sunaiye" to "सुनाइए", "suniye" to "सुनिए", "sunti" to "सुनती", "suraj" to "सूरज",
        "ta" to "ता", "table" to "टेबल", "tailor" to "टेलर", "tamatar" to "टमाटर", "taqreeban" to "तक़रीबन", "tariq" to "तारिक़", "taxi" to "टैक्सी", "thaila" to "थैला",
        "thaili" to "थैली", "thanda" to "ठंडा", "the" to "थे", "thora" to "थोड़ा", "thori" to "थोड़ी", "tohfa" to "तोहफ़ा", "tola" to "तोला", "tyre" to "टायर",
        "ugte" to "उगते", "ul" to "उल", "umar" to "उम्र", "urdu" to "उर्दू", "usman" to "उस्मान", "ustad" to "उस्ताद", "utaar" to "उतार", "utarte" to "उतरते",
        "uthna" to "उठना", "utna" to "उतना", "utne" to "उतने", "uzma" to "उज़्मा", "waise" to "वैसे", "wala" to "वाला", "walon" to "वालों", "waqas" to "वक़ास",
        "waqt" to "वक़्त", "warna" to "वरना", "woh" to "वो", "yaad" to "याद", "yahin" to "यहीं", "zabaan" to "ज़बान", "zainab" to "ज़ैनब", "zameen" to "ज़मीन",
        "zara" to "ज़रा", "zaroorat" to "ज़रूरत", "zaroori" to "ज़रूरी", "zaya" to "ज़ाया", "zubair" to "ज़ुबैर", "zyada" to "ज़्यादा",
        "smart" to "स्मार्ट", "book" to "बुक", "assistant" to "असिस्टेंट",
        "Ahmed" to "अहमद", "Ali" to "अली", "Bilal" to "बिलाल", "Ashraf" to "अशरफ़", "Abrar" to "अबरार"
    ).mapKeys { it.key.lowercase(Locale.ROOT) }

    // consonants, longest first, and vowels as a letter of their own and as a sign after a consonant
    private val consonants = listOf(
        "chh" to "छ", "kh" to "ख़", "gh" to "घ", "ch" to "च", "jh" to "झ", "th" to "थ", "dh" to "ध", "ph" to "फ", "bh" to "भ", "sh" to "श",
        "zh" to "ज़", "rh" to "ढ़", "k" to "क", "g" to "ग", "j" to "ज", "t" to "त", "d" to "द", "n" to "न", "p" to "प", "f" to "फ़", "b" to "ब",
        "m" to "म", "y" to "य", "r" to "र", "l" to "ल", "w" to "व", "v" to "व", "s" to "स", "h" to "ह", "q" to "क़", "z" to "ज़", "x" to "क्स", "c" to "क"
    )
    private val vowels = listOf(
        Triple("aa", "आ", "ा"), Triple("ai", "ऐ", "ै"), Triple("au", "औ", "ौ"), Triple("ee", "ई", "ी"), Triple("ii", "ई", "ी"),
        Triple("oo", "ऊ", "ू"), Triple("ou", "औ", "ौ"), Triple("a", "अ", ""), Triple("i", "इ", "ि"), Triple("u", "उ", "ु"),
        Triple("e", "ए", "े"), Triple("o", "ओ", "ो")
    )
    private const val VIRAMA = "्"

    fun fromRoman(text: String): String =
        Regex("""[A-Za-z]+""").replace(text) { m -> words[m.value.lowercase(Locale.ROOT)] ?: spell(m.value.lowercase(Locale.ROOT)) }
            .replace(Regex("""(\d),(\d)"""), "$1$2")
            .replace(". ", "। ").let { if (it.endsWith(".")) it.dropLast(1) + "।" else it }

    /** A word written by its sounds: a consonant followed by a vowel sign, or a joining mark before the next consonant. */
    private fun spell(w: String): String {
        val out = StringBuilder()
        var i = 0
        var afterConsonant = false
        while (i < w.length) {
            var v = vowels.firstOrNull { w.startsWith(it.first, i) }
            // a word ending in "a" or "i" ends long in Roman Urdu: "kiraya" is किराया, "pani" is पानी
            if (v != null && i == w.length - 1 && w.length > 1 && v.first == "a") v = vowels.first { it.first == "aa" }.let { Triple("a", it.second, it.third) }
            if (v != null && i == w.length - 1 && w.length > 1 && v.first == "i") v = vowels.first { it.first == "ee" }.let { Triple("i", it.second, it.third) }
            if (v != null) {
                out.append(if (afterConsonant) v.third else v.second)
                i += v.first.length; afterConsonant = false; continue
            }
            val c = consonants.firstOrNull { w.startsWith(it.first, i) }
            if (c != null) {
                if (afterConsonant) out.append(VIRAMA)
                out.append(c.second)
                i += c.first.length; afterConsonant = true; continue
            }
            out.append(w[i]); i++; afterConsonant = false
        }
        return out.toString()
    }
}
