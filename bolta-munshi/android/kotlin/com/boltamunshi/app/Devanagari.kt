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
            val v = vowels.firstOrNull { w.startsWith(it.first, i) }
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
