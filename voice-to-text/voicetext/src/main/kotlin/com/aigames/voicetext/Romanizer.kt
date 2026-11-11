package com.aigames.voicetext

/**
 * Writes Hindi/Urdu that arrives in Devanagari script the way people type it
 * in Roman letters: "आप कैसे हैं" becomes "aap kaise hain".
 *
 * Devanagari spells every vowel, so this can be done by rule. The one hard
 * part is the "a" that every bare consonant carries: it is silent at the end
 * of a word and in the middle of many words (करना is "karna", not "karana").
 *
 * Text in other scripts is returned unchanged; [VoiceToText] hands that to
 * Android's own transliterator.
 */
object Romanizer {

    /** Very common words whose usual Roman spelling does not follow the rules. */
    private val WORDS = mapOf(
        "वह" to "woh", "वो" to "woh", "यह" to "yeh", "ये" to "yeh", "वे" to "woh",
        "मैं" to "main", "में" to "mein", "नहीं" to "nahi", "हाँ" to "haan", "हां" to "haan",
        "क्यों" to "kyun", "क्यूँ" to "kyun", "क्यूं" to "kyun", "बहुत" to "bohat",
        "बहन" to "behen", "पहले" to "pehle", "पहला" to "pehla", "पहली" to "pehli",
        "कहना" to "kehna", "रहना" to "rehna", "कह" to "keh", "रह" to "reh",
        "कहते" to "kehte", "रहते" to "rehte", "कहा" to "kaha", "शहर" to "sheher",
        "ज़्यादा" to "zyada", "ज्यादा" to "zyada", "तो" to "to", "न" to "na", "व" to "wa",
        "हूँ" to "hoon", "हूं" to "hoon", "हो" to "ho", "है" to "hai", "हैं" to "hain",
        "एक" to "ek", "दो" to "do", "क्या" to "kya", "शुक्रिया" to "shukriya",
    )

    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "w", 'ळ' to "l",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        // Letters with a dot below, written as one character.
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "gh", '\u095B' to "z", '\u095C' to "r", '\u095D' to "rh", '\u095E' to "f", '\u095F' to "y",
    )

    /** The same dotted letters written as a plain letter followed by the dot (nukta). */
    private val DOTTED = mapOf(
        'क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f",
    )

    private val VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )

    private val SIGNS = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )

    private const val NUKTA = '़'
    private const val HALANT = '्'
    private const val ANUSVARA = 'ं'
    private const val CHANDRABINDU = 'ँ'
    private const val VISARGA = 'ः'

    /** One sound in a word: a consonant (maybe empty) and the vowel after it. */
    private class Unit(val consonant: String, var vowel: String, val bare: Boolean) {
        /** The vowel is the unwritten "a" of a bare consonant. */
        val schwa get() = bare && vowel == "a"
        var nasal = ""
    }

    fun isDevanagari(c: Char): Boolean = c in 'ऀ'..'ॿ'

    fun hasDevanagari(text: String): Boolean = text.any { isDevanagari(it) }

    /** Romanizes the Devanagari words in [text] and leaves everything else as it is. */
    fun toRoman(text: String): String {
        if (!hasDevanagari(text)) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                isWordChar(c) -> {
                    var end = i
                    while (end < text.length && isWordChar(text[end])) end++
                    out.append(word(text.substring(i, end)))
                    i = end
                    continue
                }
                c == '।' || c == '॥' -> out.append('.')
                c in '०'..'९' -> out.append('0' + (c - '०'))
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun isWordChar(c: Char): Boolean = isDevanagari(c) && c != '।' && c != '॥' && c !in '०'..'९'

    private fun word(w: String): String {
        WORDS[w]?.let { return it }
        val units = parse(w)
        if (units.isEmpty()) return ""

        // 1. The "a" at the very end of a word is silent (unless it is the whole word).
        val silent = BooleanArray(units.size)
        val last = units.size - 1
        if (units.size > 1 && units[last].schwa && units[last].nasal.isEmpty()) silent[last] = true

        // 2. In the middle, "a" is silent between a sounded vowel and a consonant
        //    that has its own sounded vowel: कर+ना -> kar-na. Going from the end of
        //    the word backwards gets समझना right: sa-majh-na, not sam-jha-na.
        for (k in last - 1 downTo 1) {
            val u = units[k]
            if (!u.schwa || u.nasal.isNotEmpty() || u.consonant.isEmpty()) continue
            val before = units[k - 1]
            val after = units[k + 1]
            val beforeSounded = before.vowel.isNotEmpty()
            val afterSounded = after.consonant.isNotEmpty() && after.vowel.isNotEmpty() && !silent[k + 1]
            if (beforeSounded && afterSounded) silent[k] = true
        }

        val sounded = units.indices.count { units[it].vowel.isNotEmpty() && !silent[it] }
        val out = StringBuilder()
        for (k in units.indices) {
            val u = units[k]
            out.append(u.consonant)
            if (!silent[k]) out.append(spell(u.vowel, isLast = k == last && u.nasal.isEmpty(), onlyVowel = sounded == 1))
            out.append(u.nasal)
        }
        return out.toString()
    }

    /** Picks the everyday spelling of a vowel: "naam" but "khana", "theek" but "pani". */
    private fun spell(vowel: String, isLast: Boolean, onlyVowel: Boolean): String = when (vowel) {
        "aa" -> if (onlyVowel && !isLast) "aa" else "a"
        "ee" -> if (isLast) "i" else "ee"
        "oo" -> if (isLast) "u" else "oo"
        else -> vowel
    }

    private fun parse(w: String): List<Unit> {
        val units = ArrayList<Unit>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val next = w.getOrNull(i + 1)
            when {
                c in CONSONANTS -> {
                    var sound = CONSONANTS.getValue(c)
                    var j = i + 1
                    if (w.getOrNull(j) == NUKTA) {
                        sound = DOTTED[c] ?: sound
                        j++
                    }
                    val sign = w.getOrNull(j)
                    when {
                        sign == HALANT -> {
                            units.add(Unit(sound, "", bare = false))
                            j++
                        }
                        sign != null && sign in SIGNS -> {
                            units.add(Unit(sound, SIGNS.getValue(sign), bare = false))
                            j++
                        }
                        else -> units.add(Unit(sound, "a", bare = true))
                    }
                    i = j
                    continue
                }
                c in VOWELS -> units.add(Unit("", VOWELS.getValue(c), bare = false))
                c == ANUSVARA || c == CHANDRABINDU -> {
                    // A nasal sound: "m" before p/b/m, otherwise "n".
                    val lips = next != null && next in "पफबभम"
                    units.lastOrNull()?.let { it.nasal += if (lips) "m" else "n" }
                }
                c == VISARGA -> units.lastOrNull()?.let { it.nasal += "h" }
                // Anything else (stray signs, ॐ, ...) is skipped.
            }
            i++
        }
        return units
    }
}
