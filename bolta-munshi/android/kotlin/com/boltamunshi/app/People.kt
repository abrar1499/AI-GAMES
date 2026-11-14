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

    /** The name to show: no honorifics or "ko/se", Roman words title-cased. "" stays "". */
    fun clean(name: String): String {
        var w = Words.norm(name).replace(Regex("[^\\p{L}\\p{M}\\d ]"), " ").split(' ').filter { it.isNotBlank() }.toMutableList()
        while (w.size > 1 && w.last() in TAIL) w.removeAt(w.lastIndex)
        val i = w.indexOfFirst { it == "bhai" || it == "بھائی" }
        if (i >= 0 && i + 1 < w.size && w[i + 1] in setOf("jan", "jaan", "جان")) w.removeAt(i + 1)
        if (w.any { it !in HONOR }) w = w.filter { it !in HONOR }.toMutableList()
        return w.joinToString(" ") { p -> if (Words.hasUrdu(p)) p else p.replaceFirstChar { it.uppercase() } }
    }

    /** What two spellings of one person share: Roman letters, folded vowels, no spaces. */
    fun key(name: String): String {
        var k = Words.roman(clean(name)).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), "")
        k = k.replace("aa", "a").replace("ee", "i").replace("oo", "u").replace(Regex("(.)\\1+"), "$1").replace(" ", "")
        k = k.replace(Regex("[ae]?wal[ae]$"), "wala")
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
            val s = sound(c)
            val same = known.filter { !Words.hasUrdu(it) && sound(it) == s && kotlin.math.abs(key(it).length - k.length) <= 2 }.distinct()
            if (same.size == 1) return same[0]
            return Words.roman(c).split(' ').joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
        }
        return c
    }

    /** "Doodh wala" -> "Doodh wale" (how it is said before ko/se). */
    fun oblique(name: String): String = name.replace(Regex("(?i)wala$"), "wale")
}
