package com.boltamunshi.app

import java.util.Locale

/**
 * The user's own voice: short recordings of words and phrases, joined to speak a reply.
 * This part has no Android in it. It decides which recordings make up a line, turns
 * numbers into spoken Urdu words, lists what to record, and cleans up a recording.
 */
object MyVoice {

    sealed class Part {
        /** One recording, by its key (the words it says). */
        data class Clip(val key: String) : Part()
        /** A short silence at a comma or full stop. */
        data class Pause(val ms: Int) : Part()
        /** Words with no recording: the phone's voice says these. */
        data class Say(val text: String) : Part()
    }

    class Plan(val parts: List<Part>, val missing: List<String>, val covered: Double) {
        val anyClip: Boolean get() = parts.any { it is Part.Clip }
        val usable: Boolean get() = anyClip && covered >= 0.5
    }

    private const val LONGEST = 8

    // ----------------------------------------------------------------- numbers ---

    private val UNDER_100 = ("sifar ek do teen chaar paanch chhe saat aath nau das gyarah barah terah chaudah pandrah solah satrah atharah unnees " +
        "bees ikkees baees teyees chaubees pachees chhabbees sattaees athaees untees tees iktees battees tentees chauntees paintees chhattees " +
        "saintees artees untaalees chaalees iktaalees bayaalees taintaalees chavaalees paintaalees chhiyaalees saintaalees artaalees unchaas " +
        "pachaas ikyaavan baavan tirpan chavvan pachpan chhappan sattaavan athaavan unsath saath iksath baasath tirsath chaunsath painsath " +
        "chhiyaasath sarsath arsath unhattar sattar ikhattar bahattar tihattar chauhattar pachattar chhihattar sathattar athattar unaasi assi " +
        "ikyaasi bayaasi tiraasi chauraasi pachaasi chhiyaasi sattaasi athaasi nawaasi nawway ikaanway baanway tiraanway chauraanway " +
        "pachaanway chhiyaanway sattaanway athaanway ninyaanway").split(" ")

    /** 1500 → "ek hazaar paanch sau", 250000 → "do lakh pachaas hazaar", 2.5 → "do point paanch". */
    fun numberWords(text: String): String {
        val clean = text.replace(",", "")
        val dot = clean.indexOf('.')
        val whole = (if (dot >= 0) clean.substring(0, dot) else clean).toLongOrNull() ?: return text
        val head = wholeWords(whole)
        if (dot < 0 || dot == clean.length - 1) return head
        val tail = clean.substring(dot + 1).filter { it.isDigit() }.map { UNDER_100[it - '0'] }
        return if (tail.isEmpty()) head else head + " point " + tail.joinToString(" ")
    }

    private fun wholeWords(n: Long): String {
        if (n < 100) return UNDER_100[n.toInt()]
        val out = mutableListOf<String>()
        var left = n
        for ((size, word) in listOf(10_000_000L to "crore", 100_000L to "lakh", 1_000L to "hazaar", 100L to "sau")) {
            if (left >= size) { out += wholeWords(left / size) + " " + word; left %= size }
        }
        if (left > 0) out += UNDER_100[left.toInt()]
        return out.joinToString(" ")
    }

    // ----------------------------------------------------------- planning a line ---

    private fun key(word: String) = word.lowercase(Locale.ROOT).filter { it in 'a'..'z' }

    /** Spoken words and pauses, in order: numbers become words, punctuation becomes a pause. */
    private fun tokens(line: String): List<String> {
        val out = mutableListOf<String>()
        Regex("""\d[\d,]*(?:\.\d+)?|[A-Za-z']+|[.?!।]|[,:;]|[^\sA-Za-z\d.?!।,:;]+""").findAll(line).forEach { m ->
            val t = m.value
            when {
                t[0].isDigit() -> out += numberWords(t).split(" ")
                t[0].isLetter() && t[0] < '\u0080' -> key(t).takeIf { it.isNotEmpty() }?.let { out += it }
                t in setOf(".", "?", "!", "।") -> out += "."
                t in setOf(",", ":", ";") -> out += ","
                t.any { it.isLetter() } -> out += "#" + t   // another script: only the phone's voice can say it
            }
        }
        return out
    }

    /** Which recordings say this line, longest phrases first; whatever is not recorded is left to the phone's voice. */
    fun plan(line: String, recorded: Set<String>): Plan {
        val t = tokens(line)
        val parts = mutableListOf<Part>()
        val missing = mutableListOf<String>()
        val pending = StringBuilder()
        var words = 0; var said = 0
        fun flush() { if (pending.isNotBlank()) parts += Part.Say(pending.toString().trim()); pending.setLength(0) }
        var i = 0
        while (i < t.size) {
            val w = t[i]
            if (w == "." || w == ",") {
                if (pending.isNotEmpty()) pending.append(if (w == ".") ". " else ", ")
                else if (parts.isNotEmpty() && parts.last() !is Part.Pause) parts += Part.Pause(if (w == ".") 320 else 160)
                i++; continue
            }
            var len = 0
            for (l in minOf(LONGEST, t.size - i) downTo 1) {
                val run = t.subList(i, i + l)
                if (run.any { it == "." || it == "," || it.startsWith("#") }) continue
                if (run.joinToString(" ") in recorded) { len = l; break }
            }
            if (len > 0) {
                flush()
                parts += Part.Clip(t.subList(i, i + len).joinToString(" "))
                words += len; said += len; i += len
            } else {
                pending.append(w.removePrefix("#")).append(' ')
                if (!w.startsWith("#")) missing += w
                words++; i++
            }
        }
        flush()
        while (parts.lastOrNull() is Part.Pause) parts.removeAt(parts.size - 1)
        return Plan(parts, missing, if (words == 0) 0.0 else said.toDouble() / words)
    }

    // ------------------------------------------------------------ what to record ---

    /** Everything worth recording, in the order it is asked for: what the munshi says most first. */
    val PHRASES: List<String> = listOf(
        // the test line first ("Petrol ke 1,500 rupay likh liye. Aur kuch?"), so a few recordings already show how it sounds
        "petrol", "ke", "ek", "hazaar", "paanch", "sau", "rupay likh liye", "aur kuch",
        // the khata's own lines
        "likh liya", "likh liye", "likh li", "likh diya", "likh di", "rupay", "ke", "ka", "ki", "ko", "se", "ne", "aur", "ya",
        "rupay likh liye", "rupay diye", "rupay liye", "rupay wapas kiye", "rupay bheje", "rupay aaye", "rupay aamdani likh li",
        "aap ne", "aap ko", "aap", "dene hain", "lene hain", "rupay dene hain", "logon se lene hain", "aap ne dene hain",
        "ke sath hisaab barabar hai", "udhar par", "udhar par likh diya", "ki adaigi ho gayi", "wapas", "kiye", "diye", "liye",
        "aaye", "gaye", "bachat", "aamdani", "kharcha", "kharche", "total", "hisaab", "entry", "entries", "wali entry",
        "rupay wali entry mita doon", "entries mita doon", "haan ya nahi", "mita di", "mita diya", "mita diye", "theek hai",
        "nahi", "haan", "jee", "koi entry nahi", "kuch nahi mila", "aakhri", "andaazan munafa", "andaazan nuqsan",
        "stock mein", "stock mein likh di", "tayyar", "ki bikri", "ki khareed", "aaj", "kal", "parson", "is mahine",
        "pichhle mahine", "is saal", "is hafte", "kitne", "kya", "kaun", "kis ko", "kis se", "kitne rupay", "mein", "par",
        "hai", "hain", "tha", "the", "bhi", "abhi", "pehle", "baad", "sab", "kuch", "koi", "aur kuch",
        // talk
        "assalam o alaikum", "wa alaikum assalam", "bataiye", "jee bataiye", "main aap ki munshi hoon", "shukriya",
        "khuda hafiz", "allah hafiz", "maaf kijiye", "dobara bolein", "samajh nahi aaya", "acha", "bilkul", "zaroor",
        "kaise hain", "main theek hoon",
        // things money goes on
        "petrol", "bijli", "bill", "bijli ka bill", "gas", "gas ka bill", "pani", "chai", "khana", "doodh", "sabzi",
        "rashan", "aata", "cheeni", "ghee", "anday", "gosht", "phal", "dawai", "doctor", "kiraya", "mobile", "load",
        "internet", "kapre", "school", "fees", "rickshaw", "bus", "gaari", "bike", "marammat", "tankhwah", "dukaan",
        "maal", "udhar", "qarz",
        // numbers
        *UNDER_100.drop(1).toTypedArray(), "sau", "hazaar", "lakh", "crore", "point", "aadha",
        // dates
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"
    ).map { it.lowercase(Locale.ROOT) }.distinct()

    /** The file name a recording is kept under. */
    fun fileName(key: String) = key.replace(' ', '_') + ".pcm"
    fun keyOf(fileName: String) = fileName.removeSuffix(".pcm").replace('_', ' ')

    // --------------------------------------------------------- cleaning a recording ---

    /**
     * Cuts the silence before and after the words, softens the edges so joins do not click,
     * and brings the loudness to the same level for every recording. Empty if nothing was said.
     */
    fun clean(pcm: ShortArray, rate: Int): ShortArray {
        val frame = rate / 100                                      // 10 ms
        val frames = pcm.size / frame
        if (frames < 5) return ShortArray(0)
        val rms = DoubleArray(frames) { f ->
            var s = 0.0
            for (j in f * frame until (f + 1) * frame) s += pcm[j].toDouble() * pcm[j]
            kotlin.math.sqrt(s / frame)
        }
        val floor = rms.sorted()[frames / 10]
        val loud = rms.maxOrNull() ?: 0.0
        if (loud < 300 || loud < floor * 3) return ShortArray(0)
        val gate = maxOf(floor * 2.5, loud * 0.08)
        val first = rms.indexOfFirst { it > gate }
        val last = rms.indexOfLast { it > gate }
        val from = maxOf(0, (first - 4) * frame)                    // 40 ms before the voice
        val to = minOf(pcm.size, (last + 8) * frame)                // 80 ms after it
        val out = pcm.copyOfRange(from, to)
        var peak = 1
        for (v in out) peak = maxOf(peak, kotlin.math.abs(v.toInt()))
        val gain = minOf(26000.0 / peak, 8.0)
        val fade = minOf(rate / 100, out.size / 2)
        for (j in out.indices) {
            var g = gain
            if (j < fade) g *= j.toDouble() / fade
            if (j >= out.size - fade) g *= (out.size - 1 - j).toDouble() / fade
            out[j] = (out[j] * g).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    /** How loud a stretch of a recording is: used to stop recording once the user goes quiet. */
    fun level(pcm: ShortArray, from: Int, count: Int): Double {
        var s = 0.0
        for (j in from until from + count) s += pcm[j].toDouble() * pcm[j]
        return kotlin.math.sqrt(s / count)
    }
}
