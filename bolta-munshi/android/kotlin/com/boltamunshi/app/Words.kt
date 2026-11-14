package com.boltamunshi.app

import org.json.JSONObject
import java.util.Locale

/**
 * Small helpers for what people say: Urdu digits and letter forms, Urdu script in Roman
 * letters (for matching names and items), amounts written in any way ("1,500", "۵۰۰",
 * "Rs 500", "1.5 lakh", "dhai sau", "ڈیڑھ ہزار"), and JSON fields that may be null.
 */
object Words {

    /** Lowercase, Urdu and Arabic digits as 0-9, one form of each Urdu letter, no short-vowel marks (keeps ُ for کُل). */
    fun norm(s: String): String {
        val b = StringBuilder()
        for (c in s.lowercase(Locale.ROOT)) {
            when {
                c in '۰'..'۹' -> b.append('0' + (c - '۰'))
                c in '٠'..'٩' -> b.append('0' + (c - '٠'))
                c == 'ك' -> b.append('ک')
                c == 'ي' || c == 'ى' -> b.append('ی')
                c == 'ه' -> b.append('ہ')
                c in 'ً'..'ْ' && c != 'ُ' -> {}
                c == 'ٰ' -> {}
                else -> b.append(c)
            }
        }
        return b.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun hasUrdu(s: String) = s.any { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' }

    /** Word edges that see Urdu letters too (Java's \b does not). */
    const val B = "(?<![\\p{L}\\p{M}\\d])"
    const val E = "(?![\\p{L}\\p{M}\\d])"

    private val urduWords = mapOf(
        "پیٹرول" to "petrol", "چائے" to "chai", "دودھ" to "doodh", "سبزی" to "sabzi", "بجلی" to "bijli", "بل" to "bill",
        "کرایہ" to "kiraya", "راشن" to "rashan", "دوائی" to "dawai", "کھانا" to "khana", "ناشتہ" to "nashta", "گیس" to "gas",
        "فیس" to "fees", "تنخواہ" to "tankhwah", "روٹی" to "roti", "گوشت" to "gosht", "کپڑے" to "kapre", "جوتے" to "joote",
        "علی" to "ali", "محمد" to "muhammad", "احمد" to "ahmed", "عثمان" to "usman", "عمر" to "umar", "حمزہ" to "hamza",
        "بلال" to "bilal", "عائشہ" to "ayesha", "فاطمہ" to "fatima", "امی" to "ammi", "ابو" to "abbu", "بھائی" to "bhai",
        "والا" to "wala", "والے" to "wale", "والی" to "wali", "دکان" to "dukaan", "کریانہ" to "kiryana", "کریانے" to "kiryane",
        "ڈرائیور" to "driver", "چاچا" to "chacha", "ماموں" to "mamu", "خالہ" to "khala", "باجی" to "baji"
    )

    private val letters = mapOf(
        'ا' to "a", 'آ' to "aa", 'ب' to "b", 'پ' to "p", 'ت' to "t", 'ٹ' to "t", 'ث' to "s", 'ج' to "j", 'چ' to "ch",
        'ح' to "h", 'خ' to "kh", 'د' to "d", 'ڈ' to "d", 'ذ' to "z", 'ر' to "r", 'ڑ' to "r", 'ز' to "z", 'ژ' to "zh",
        'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "z", 'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh", 'ف' to "f",
        'ق' to "q", 'ک' to "k", 'گ' to "g", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ں' to "n", 'و' to "o",
        'ہ' to "h", 'ھ' to "h", 'ۃ' to "a", 'ة' to "a", 'ی' to "i", 'ے' to "e", 'ۓ' to "e", 'ئ' to "", 'ء' to "", 'ؤ' to "o",
        'أ' to "a", 'إ' to "i"
    )

    private fun romanWord(w: String): String = urduWords[w] ?: buildString {
        w.forEachIndexed { i, c ->
            when {
                c == 'و' && i == 0 -> append("w")
                c == 'ی' && i == 0 -> append("y")
                c == 'ہ' && i == w.lastIndex && i > 0 -> append("a")
                else -> append(letters[c] ?: if (c.isLetterOrDigit()) c.toString() else "")
            }
        }
    }

    /** Urdu script in Roman letters, word by word; Roman text comes back as it is (lowercase). */
    fun roman(s: String): String {
        val t = norm(s)
        if (!hasUrdu(t)) return t
        return t.split(' ').joinToString(" ") { w -> if (hasUrdu(w)) romanWord(w.filter { it.isLetterOrDigit() }) else w }
    }

    // ------------------------------------------------------------------ amounts ---

    private val units = mapOf(
        "ek" to 1.0, "aik" to 1.0, "do" to 2.0, "teen" to 3.0, "char" to 4.0, "chaar" to 4.0, "panch" to 5.0, "paanch" to 5.0,
        "chhe" to 6.0, "chay" to 6.0, "che" to 6.0, "saat" to 7.0, "aath" to 8.0, "nau" to 9.0, "das" to 10.0, "dus" to 10.0,
        "gyarah" to 11.0, "barah" to 12.0, "terah" to 13.0, "chaudah" to 14.0, "pandrah" to 15.0, "solah" to 16.0, "satrah" to 17.0,
        "atharah" to 18.0, "unees" to 19.0, "bees" to 20.0, "pachees" to 25.0, "tees" to 30.0, "chalees" to 40.0, "chalis" to 40.0,
        "pachas" to 50.0, "pachaas" to 50.0, "saath" to 60.0, "sattar" to 70.0, "assi" to 80.0, "nawway" to 90.0,
        "dedh" to 1.5, "derh" to 1.5, "dhai" to 2.5, "dhaai" to 2.5, "adha" to 0.5, "aadha" to 0.5,
        "ایک" to 1.0, "دو" to 2.0, "تین" to 3.0, "چار" to 4.0, "پانچ" to 5.0, "چھ" to 6.0, "سات" to 7.0, "آٹھ" to 8.0,
        "نو" to 9.0, "دس" to 10.0, "بیس" to 20.0, "پچیس" to 25.0, "تیس" to 30.0, "چالیس" to 40.0, "پچاس" to 50.0,
        "ساٹھ" to 60.0, "ستر" to 70.0, "اسی" to 80.0, "ڈیڑھ" to 1.5, "ڈھائی" to 2.5, "آدھا" to 0.5,
        "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0, "seven" to 7.0,
        "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "twenty" to 20.0, "fifty" to 50.0
    )
    private val mults = mapOf(
        "sau" to 100.0, "so" to 100.0, "سو" to 100.0, "hundred" to 100.0,
        "hazar" to 1000.0, "hazaar" to 1000.0, "hazzar" to 1000.0, "ہزار" to 1000.0, "thousand" to 1000.0, "k" to 1000.0,
        "lakh" to 100000.0, "lac" to 100000.0, "lakh." to 100000.0, "لاکھ" to 100000.0,
        "crore" to 10000000.0, "karor" to 10000000.0, "کروڑ" to 10000000.0, "million" to 1000000.0
    )
    private val parts = mapOf("sawa" to 0.25, "sava" to 0.25, "سوا" to 0.25, "paune" to -0.25, "pone" to -0.25, "پونے" to -0.25,
        "sadhe" to 0.5, "saadhe" to 0.5, "sarhe" to 0.5, "saarhe" to 0.5, "ساڑھے" to 0.5)

    /**
     * Every amount said in the text, in order: digits ("1,500", "۵۰۰", "2.5"), digits with a
     * multiplier ("1.5 lakh", "2 hazaar 500"), and number words ("dhai sau", "sawa hazaar",
     * "ڈیڑھ ہزار"). Number words alone ("do", "ek") count only with a multiplier after them,
     * because they also mean other things.
     */
    fun amounts(text: String): List<Double> {
        val toks = norm(text).replace(Regex("(?<=\\d),(?=\\d{3})"), "").replace(Regex("(?<=\\d)(?=[\\p{L}])"), " ")
            .split(Regex("[\\s،,؛;:!?۔()\"']+")).map { it.trimEnd('.') }.filter { it.isNotEmpty() }
        val out = mutableListOf<Double>()
        var i = 0
        while (i < toks.size) {
            var total = 0.0
            var cur = 0.0
            var any = false
            var mult = false
            var j = i
            while (j < toks.size) {
                val w = toks[j]
                val num = w.toDoubleOrNull()
                val part = parts[w]
                when {
                    num != null && !any -> { cur = num; any = true }
                    num != null && cur == 0.0 && total > 0 && num < total -> cur = num        // "2 hazaar 500"
                    num != null -> break
                    part != null -> {
                        val nextIsUnit = toks.getOrNull(j + 1)?.let { it.toDoubleOrNull() != null || units.containsKey(it) } == true
                        cur += if (nextIsUnit || part == 0.5) part else 1.0 + part
                        any = true
                    }
                    units.containsKey(w) && (cur == 0.0 || cur % 1.0 != 0.0 && cur < 1.0) -> { cur += units.getValue(w); any = true }
                    units.containsKey(w) && cur >= 100 -> { total += cur; cur = units.getValue(w) }
                    mults.containsKey(w) && any -> {
                        mult = true
                        val m = mults.getValue(w)
                        if (m == 100.0) cur = (if (cur == 0.0) 1.0 else cur) * 100.0
                        else { total += (if (cur == 0.0) 1.0 else cur) * m; cur = 0.0 }
                    }
                    else -> break
                }
                j++
            }
            if (j == i) { i++; continue }
            val value = total + cur
            val first = toks[i]
            // a lone number word ("do", "ek") is not an amount
            if (value > 0 && (mult || first.toDoubleOrNull() != null)) out += value
            i = maxOf(j, i + 1)
        }
        return out
    }

    /** An amount the AI wrote: a number, or text such as "1,500", "Rs 500", "۵۰۰", "1.5 lakh", "dhai sau"; 0 when there is none. */
    fun amountOf(v: Any?): Double {
        val d = when (v) {
            null, JSONObject.NULL -> 0.0
            is Number -> v.toDouble()
            else -> {
                val s = v.toString().trim()
                s.toDoubleOrNull() ?: amounts(s.replace(Regex("(?i)\\b(rs\\.?|pkr|rupees?|rupay|rupaye)\\b"), " ")).firstOrNull() ?: 0.0
            }
        }
        return if (d.isNaN() || d.isInfinite()) 0.0 else kotlin.math.abs(d)
    }

    /** A JSON string field, "" for a missing field or JSON null (Android's optString gives "null" there). */
    fun str(o: JSONObject, key: String): String {
        val v = o.opt(key)
        return if (v == null || v == JSONObject.NULL) "" else v.toString().trim().let { if (it == "null") "" else it }
    }
}
