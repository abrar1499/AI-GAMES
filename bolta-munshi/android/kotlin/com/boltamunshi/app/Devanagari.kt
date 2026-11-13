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
