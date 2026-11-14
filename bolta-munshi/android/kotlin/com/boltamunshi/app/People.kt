package com.boltamunshi.app

import java.util.Locale

/**
 * One person, one name: "Ali", "ali", "Ali bhai", "Ali ko", "علی" and "علی بھائی" are the same
 * Ali, so a repayment always meets its loan. Honorifics and the little words after a name
 * (ko, se, ne) are dropped; Urdu script is read in Roman letters; spellings are folded
 * ("doodh wale" = "Doodh wala"). Surnames and roles stay: Ali, Ali Raza and Ali driver are three people.
 */
object People {

    private val TAIL = setOf("ko", "se", "sy", "say", "ne", "nay", "ka", "ke", "ki", "kay", "کو", "سے", "نے", "کا", "کے", "کی")
    private val HONOR = setOf("bhai", "bhaijan", "bhaijaan", "bhaiya", "bhaiyya", "bhayya", "bhae", "sahab", "sahib", "saab", "saheb", "sb",
        "ji", "jee", "janab", "mian", "miyan", "baji", "baaji", "apa", "aapa", "appi", "uncle", "aunty", "auntie", "anty", "mr", "mrs",
        "haji", "hajji", "بھائی", "بھیا", "صاحب", "صاب", "جی", "جناب", "میاں", "باجی", "آپا", "آپی", "انکل", "آنٹی", "حاجی")

    private val KIN = HONOR + setOf("ammi", "ami", "amma", "abbu", "abu", "abba", "khala", "phupho", "phuphu", "chachi", "chacha", "mamu", "mumani",
        "nani", "dadi", "nana", "dada", "beta", "beti", "امی", "ابو", "ابا", "اماں", "خالہ", "پھپھو", "چچی", "چاچا", "ماموں", "ممانی", "نانی", "دادی", "نانا", "دادا", "بیٹا", "بیٹی")

    /** The name to show: no honorifics or "ko/se", Roman words title-cased. "" stays "". */
    fun clean(name: String): String {
        var w = Words.norm(name).replace(Regex("[^\\p{L}\\p{M}\\d ]"), " ").split(' ').filter { it.isNotBlank() }.toMutableList()
        while (w.size > 1 && w.last() in TAIL) w.removeAt(w.lastIndex)
        // "jaan" after a family word or honorific is love, not a name: Ammi jaan = Ammi, Baji jaan = Baji (Ali Jan stays)
        for (i in w.indices.reversed()) if (i > 0 && w[i] in setOf("jan", "jaan", "جان") && w[i - 1] in KIN) w.removeAt(i)
        if (w.any { it !in HONOR }) w = w.filter { it !in HONOR }.toMutableList()
        return w.joinToString(" ") { p -> if (Words.hasUrdu(p)) p else p.replaceFirstChar { it.uppercase() } }
    }

    /** What two spellings of one person share: Roman letters, folded vowels, no spaces. */
    fun key(name: String): String {
        var k = Words.roman(clean(name)).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), "")
        k = k.replace("aa", "a").replace("ee", "i").replace("oo", "u").replace(Regex("(.)\\1+"), "$1").replace(" ", "")
        k = k.replace(Regex("[ae]?wal(?:a|e|ay|ey|y|i)$"), "wala")
        // spellings of one sound: o and u, e and i (Umar/Omar, Belal/Bilal, dodh/doodh)
        k = k.replace('o', 'u').replace('e', 'i').replace(Regex("(.)\\1+"), "$1")
        return k
    }

    /** The sound of a name: its first letter (or V for a vowel) and its consonants. "Hamza" and "حمزہ" both give "h-mz". */
    fun sound(name: String): String {
        val k = key(name)
        if (k.isEmpty()) return ""
        val first = if (k[0] in "aeiou") "V" else k[0].toString()
        val rest = k.drop(1).replace("sh", "S").replace("kh", "K").replace("ch", "C").replace("gh", "G")
            .replace('q', 'k').replace('c', 'k').replace('v', 'w').filter { it !in "aeiouyh" }
            .fold("") { acc, ch -> if (acc.endsWith(ch)) acc else acc + ch }
        return "$first-$rest"
    }

    /**
     * The name to store for [name], given the people already in the book: an existing person
     * with the same key, or (for a name in Urdu script) the one existing person who sounds the
     * same, or else the cleaned name, in Roman letters.
     */
    fun canonical(name: String, known: Collection<String>): String {
        val c = clean(name)
        if (c.isEmpty()) return ""
        val k = key(c)
        known.firstOrNull { key(it) == k }?.let { return it }
        if (Words.hasUrdu(c)) {
            // a name the dictionary does not know: the one known person who sounds the same and has the same long vowels and ending
            val s = sound(c)
            val same = known.filter { !Words.hasUrdu(it) && sound(it) == s && kotlin.math.abs(key(it).length - k.length) <= 2 && shapeFits(c, it) }.distinct()
            if (same.size == 1) return same[0]
            return Words.roman(c).split(' ').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
        }
        return c
    }

    /**
     * Whether an Urdu-script name can be the Roman one: the same ending (ہ/ا with a, ی with i/ee,
     * a consonant with a consonant) and every long vowel written inside it (ی, و, ا) found in the Roman name.
     */
    private fun shapeFits(urdu: String, roman: String): Boolean {
        var u = Words.norm(urdu).filter { it.isLetter() }
        val r = roman.lowercase(Locale.ROOT).filter { it.isLetter() }
        if (u.isEmpty() || r.isEmpty()) return false
        if (u.length > 2 && (u.last() == 'ع' || u.last() == 'ح')) u = u.dropLast(1)          // Rafi(ع), Saleh(ح): the ending is not heard
        if (u.startsWith("عا")) u = "ا" + u.drop(2)                                         // عاطف = Atif: one vowel at the start
        val endU = when (u.last()) { 'ہ', 'ا', 'ۃ', 'ة' -> 'a'; 'ی', 'ے' -> if (r.last() == 'a') 'a' else 'i'; 'و' -> 'u'; else -> 'c' }   // مصطفیٰ = Mustafa
        val endR = when (r.last()) { 'a', 'h' -> if (r.last() == 'h' && r.length > 1 && r[r.length - 2] !in "aeiou") 'c' else 'a'; 'i', 'y', 'e' -> 'i'; 'u', 'o' -> 'u'; else -> 'c' }
        if (endU != endR && !(urdu.trim().last() in "عح" && endR != 'c')) return false
        val inner = u.drop(1).dropLast(1)
        if ('ی' in inner && r.drop(1).dropLast(1).none { it == 'i' || it == 'e' || it == 'y' }) return false
        if ('و' in inner && r.drop(1).dropLast(1).none { it == 'o' || it == 'u' || it == 'w' }) return false
        if ('ا' in inner && r.drop(1).dropLast(1).none { it == 'a' }) return false
        return true
    }

    /** "Doodh wala" -> "Doodh wale" (how it is said before ko/se). */
    fun oblique(name: String): String = name.replace(Regex("(?i)wala$"), "wale")
}
