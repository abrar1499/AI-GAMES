package com.boltamunshi.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Days and times. The AI is handed a small calendar so it copies dates instead of working
 * them out, and the app then checks every date against the user's own words: when they
 * clearly name a day ("kal", "پرسوں", "3 din pehle", "mangal ko", "10 tareekh"), that day is
 * written, whatever the AI put. "کل" that means "total" ("کل ملا کر") is not a day.
 */
object Dates {
    private const val B = Words.B
    private const val E = Words.E

    // ---------------------------------------------------------------- day maths ---

    private fun cal(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    private fun cal(day: String): Calendar? = runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
        cal(f.parse(day)!!.time)
    }.getOrNull()
    fun day(c: Calendar): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
    fun day(ms: Long): String = day(cal(ms))
    fun plusDays(ms: Long, n: Int): String = day(cal(ms).apply { add(Calendar.DAY_OF_YEAR, n) })
    private fun plusDays(c: Calendar, n: Int) = (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, n) }
    /** Monday = 1 ... Sunday = 7. */
    private fun dow(c: Calendar) = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
    private fun length(c: Calendar) = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    private fun withDay(c: Calendar, d: Int): Calendar? = if (d < 1 || d > length(c)) null else (c.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, d) }
    private fun plusMonths(c: Calendar, n: Int): Calendar = (c.clone() as Calendar).apply {
        val d = get(Calendar.DAY_OF_MONTH); set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, n); set(Calendar.DAY_OF_MONTH, minOf(d, getActualMaximum(Calendar.DAY_OF_MONTH)))
    }

    private val WEEK = listOf("Monday" to "peer/پیر", "Tuesday" to "mangal/منگل", "Wednesday" to "budh/بدھ", "Thursday" to "jumeraat/جمعرات",
        "Friday" to "jumma/جمعہ", "Saturday" to "hafta/ہفتہ", "Sunday" to "itwaar/اتوار")

    /** The calendar the AI copies dates from. */
    fun block(now: Long): String {
        val t = cal(now)
        fun name(c: Calendar) = WEEK[dow(c) - 1].let { it.first + " " + it.second }
        val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(now))
        val sb = StringBuilder("CALENDAR (copy dates from here, never work them out)\n")
        sb.append("Now: ").append(day(t)).append(' ').append(name(t)).append(", ").append(hm).append(". aaj/آج = ").append(day(t)).append('\n')
        if (hm < "04:00") sb.append("It is after midnight: for a reminder, \"kal subah\" means this coming morning, ").append(day(t)).append(".\n")
        sb.append("Days before today (money that was spent, given or received):\n")
        for (i in 1..7) {
            val c = plusDays(t, -i)
            sb.append(day(c)).append(' ').append(name(c))
            when (i) { 1 -> sb.append(" = kal/کل (yesterday)"); 2 -> sb.append(" = parson/پرسوں"); 3 -> sb.append(" = narson/ترسوں") }
            sb.append(" = ").append(i).append(" din pehle")
            if (i == 7) sb.append(" = ek hafta pehle = pichle hafte")
            sb.append('\n')
        }
        val lm = plusMonths(t, -1)
        sb.append("pichle mahine = ").append(day(lm)).append(". \"N tareekh\" for money: N up to ").append(t.get(Calendar.DAY_OF_MONTH))
            .append(" is this month (").append(day(t).take(8)).append("N), a bigger N is last month (").append(day(lm).take(8)).append("N).\n")
        sb.append("Days after today (reminders only): ")
        sb.append((1..7).joinToString("; ") { i -> val c = plusDays(t, i); day(c) + " " + WEEK[dow(c) - 1].first + (if (i == 1) " = kal/کل (tomorrow)" else if (i == 2) " = parson" else "") })
        sb.append('\n')
        return sb.toString()
    }

    // ------------------------------------------------------------- AI's dates ---

    /**
     * A date the AI wrote, in whatever form ("2026-11-13", "2026-11-3", "2026-11-13T00:00:00",
     * "13-11-2026", "13/11/2026", Urdu digits, "kal", "parson", "-3", "3 days ago"), as
     * yyyy-MM-dd; null when it is not a real day.
     */
    fun loose(d: String, now: Long, future: Boolean = false): String? {
        val t = Words.norm(d).replace('/', '-').replace('.', '-').trim()
        if (t.isEmpty() || t == "null") return null
        Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""").find(t)?.let { return real(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        Regex("""^(\d{1,2})-(\d{1,2})-(\d{4})$""").find(t)?.let { return real(it.groupValues[3], it.groupValues[2], it.groupValues[1]) }
        Regex("""^(\d{1,2})-(\d{1,2})-(\d{2})$""").find(t)?.let { return real("20" + it.groupValues[3], it.groupValues[2], it.groupValues[1]) }
        Regex("""^([+-]?\d{1,3})( ?(days?|din))?( ago| pehle)?$""").find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            return plusDays(now, if (m.groupValues[4].isNotEmpty()) -kotlin.math.abs(n) else n)
        }
        return named(t, now, future)?.date
    }

    private fun real(y: String, m: String, d: String): String? {
        val s = y + "-" + m.padStart(2, '0') + "-" + d.padStart(2, '0')
        return cal(s)?.let { day(it) }
    }

    /**
     * A reminder time the AI wrote ("2026-11-15 09:00", "2026-11-15T9:00", "kal 09:00",
     * "aaj 18:30", "parson 9:00", "+30 min", "+2 h") as yyyy-MM-dd HH:mm, or "".
     */
    fun at(a: String, now: Long): String {
        val t = Words.norm(a).replace('T', ' ').trim()
        if (t.isEmpty() || t == "null") return ""
        Regex("""^\+?\s*(\d{1,4})\s*(minutes|minute|mins|min|m|hours|hour|hrs|hr|h|ghante|ghanta|days|day|din|d|weeks|week|hafte|hafta|w|months|month|mahine|mahina)(?![\p{L}])""").find(t)?.let { m ->
            val n = m.groupValues[1].toInt()
            val u = m.groupValues[2]
            val clock = Regex("""(\d{1,2}):(\d{2})""").find(t.substring(m.range.last + 1))?.value?.padStart(5, '0') ?: "09:00"
            return when {
                u.startsWith("mi") || u == "m" -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now + n * 60_000L))
                u.startsWith("h") && u != "hafte" && u != "hafta" || u.startsWith("g") -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now + n * 3_600_000L))
                u.startsWith("d") -> plusDays(now, n) + " " + clock
                u.startsWith("w") || u.startsWith("haf") -> plusDays(now, 7 * n) + " " + clock
                else -> day(plusMonths(cal(now), n)) + " " + clock
            }
        }
        val time = Regex("""(\d{1,2}):(\d{2})""").find(t)
        val hhmm = time?.let { m -> val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt(); if (h > 23 || mi > 59) null else String.format(Locale.US, "%02d:%02d", h, mi) }
        val rest = if (time != null) t.removeRange(time.range).trim() else t
        val dayPart = loose(rest, now, true) ?: if (rest.isEmpty() && hhmm != null) day(now) else null
        if (dayPart == null || hhmm == null) return ""
        return "$dayPart $hhmm"
    }

    // ------------------------------------------------------------- user's words ---

    class Found(val date: String, val exact: Boolean)

    private val NUMS = listOf("ek|aik|ایک", "do|دو", "teen|تین", "char|chaar|چار", "panch|paanch|پانچ", "chhe|che|chay|چھ", "saat|سات", "aath|آٹھ",
        "nau|نو", "das|dus|دس", "gyarah|گیارہ", "barah|بارہ", "terah|تیرہ", "chaudah|چودہ", "pandrah|پندرہ", "solah|سولہ", "satrah|سترہ",
        "atharah|اٹھارہ", "unnees|unees|انیس", "bees|بیس", "ikkees|اکیس", "baees|بائیس", "teis|تئیس", "chaubees|چوبیس", "pachchees|pachees|پچیس",
        "chhabbees|چھبیس", "sattaees|ستائیس", "atthaees|اٹھائیس", "untees|انتیس", "tees|تیس", "ikattees|اکتیس")
    private val N = "(\\d{1,2}|" + NUMS.joinToString("|") + ")"
    private fun num(s: String): Int = s.toIntOrNull() ?: (NUMS.indexOfFirst { alt -> alt.split('|').contains(s) }.let { if (it < 0) -1 else it + 1 })
    private fun has(t: String, alt: String) = Regex("$B(?:$alt)$E").containsMatchIn(t)

    private val WD = listOf(
        Triple(1, "peer|pir|somvar|somwar|monday|پیر|سوموار", false), Triple(2, "mangal|mangalwar|tuesday|منگل", true),
        Triple(3, "budh|budhwar|wednesday|بدھ", false), Triple(4, "jumeraat|jumerat|jumarat|thursday|جمعرات", true),
        Triple(5, "jumma|juma|jumah|jummay|jumme|friday|جمعہ|جمعے", false), Triple(6, "hafta|hafte|sanichar|saturday|ہفتہ|ہفتے", false),
        Triple(7, "itwaar|itwar|itvaar|sunday|اتوار", true))
    private val MONTHS = listOf("january|jan|جنوری", "february|feb|فروری", "march|mar|مارچ", "april|apr|اپریل", "may|مئی", "june|jun|جون",
        "july|jul|جولائی", "august|aug|اگست", "september|sep|sept|ستمبر", "october|oct|اکتوبر", "november|nov|نومبر", "december|dec|دسمبر")

    /** The day the words name, or null. [future]: "kal" is tomorrow and weekdays are the next one. */
    fun named(raw: String, now: Long, future: Boolean): Found? {
        val t = Words.norm(raw)
        val aaj = has(t, "aaj|aj|today|آج") && !has(t, "aaj kal|aj kal|آج کل") && !Regex("$B(?:aaj|aj|آج) (?:se|say|سے) $N ?(?:din|دن)").containsMatchIn(t)
        val abhi = has(t, "abhi|abi|ابھی|just now") && !has(t, "(?:abhi|abi|ابھی) (?:tak|bhi|nahi|nahin|تک|بھی|نہیں)")
        val other = other(t, now, future)
        if (aaj) return if (other == null) Found(day(now), true) else null          // "kal ka petrol aaj likh lo": two days, unclear
        if (abhi && other == null) return Found(day(now), true)                       // "abhi 200 wapis kiye": now ("abhi kal hi" keeps kal)
        return other
    }

    private fun other(t: String, now: Long, future: Boolean): Found? {
        val today = cal(now)
        var m = Regex("$B$N ?(?:din|dino|dinon|days?|دن) ?(?:pehle|pehlay|pahle|phle|ago|qabl|پہلے|قبل)$E").find(t)
        if (m != null && num(m.groupValues[1]) > 0) return Found(day(plusDays(today, -num(m.groupValues[1]))), true)
        m = Regex("$B(ek|do|teen|ایک|دو|تین|\\d) ?(?:hafta|hafte|ہفتہ|ہفتے) ?(?:pehle|pahle|پہلے)$E").find(t)
        if (m != null) return Found(day(plusDays(today, -7 * num(m.groupValues[1]))), true)
        m = Regex("$B(?:(pichle|pichhle|guzishta|پچھلے|گزشتہ) (?:mahine|mahiny|مہینے) (?:ki|کی) )?$N ?(?:tareekh|tarikh|tarik|date|تاریخ)$E").find(t)
            ?: Regex("$B(?:tareekh|tarikh|تاریخ) $N$E").find(t)?.let { mm -> Regex("()$N").find(mm.value.substringAfter(' ').let { " $it" }) }
        if (m != null) {
            val d = num(m.groupValues[2])
            if (d in 1..31) {
                if (m.groupValues[1].isNotEmpty()) { val p = plusMonths(today, -1); return withDay(p, minOf(d, length(p)))?.let { Found(day(it), true) } }
                val dd = today.get(Calendar.DAY_OF_MONTH)
                return if (!future) {
                    (if (d <= dd) withDay(today, d) else withDay(plusMonths(withDay(today, 1)!!, -1), d))?.let { Found(day(it), true) }
                } else {
                    (if (d >= dd) withDay(today, d) else withDay(plusMonths(withDay(today, 1)!!, 1), d))?.let { Found(day(it), true) }
                }
            }
        }
        for ((i, mo) in MONTHS.withIndex()) {
            val mm = Regex("$B$N ?(?:$mo)$E").find(t) ?: continue
            val d = num(mm.groupValues[1]); if (d !in 1..31) continue
            val c = (today.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.MONTH, i) }
            var x = withDay(c, minOf(d, length(c))) ?: continue
            if (!future && x.after(today)) x = (x.clone() as Calendar).apply { add(Calendar.YEAR, -1) }
            if (future && x.before(today) && day(x) != day(today)) x = (x.clone() as Calendar).apply { add(Calendar.YEAR, 1) }
            return Found(day(x), true)
        }
        if (has(t, "narson|narso|tarson|atarson|ترسوں|اترسوں")) return Found(day(plusDays(today, if (future) 3 else -3)), true)
        if (has(t, "kal parson|کل پرسوں")) return null
        if (has(t, "parson|parso|parsu|parsun|prson|parsoon|پرسوں|پرسو")) {
            val early = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) < 4
            return Found(day(plusDays(today, if (future) (if (early) 1 else 2) else -2)), true)
        }
        for ((d, words, bare) in WD) {
            val after = "(?: (?:ko|کو|ke din|کے دن|wale din|والے دن))"
            val r = Regex("$B(pichle|pichhle|guzishta|is|agle|ek|har|\\d+|پچھلے|گزشتہ|اس|اگلے|ایک|ہر)? ?(?:$words)" + (if (bare) "$after?" else after) + E)
            for (mm in r.findAll(t)) {
                if (d == 6 && mm.groupValues[1].isNotEmpty() && !Regex("(ko|کو|کے دن|ke din)$").containsMatchIn(mm.value)) continue   // pichle hafte = last week
                if (d == 1 && Regex("^\\s*(sahab|sahib|saab|صاحب|بابا|baba)").containsMatchIn(t.substring(mm.range.last + 1))) continue  // peer sahab is a person
                val back = (dow(today) - d + 7) % 7
                if (!future && back == 0) {
                    val pre = mm.groupValues[1]
                    return when { pre.isEmpty() -> Found(day(today), false); pre in setOf("is", "اس") -> Found(day(today), true); else -> Found(day(plusDays(today, -7)), true) }
                }
                if (!future) return Found(day(plusDays(today, -back)), true)
                val fwd = (d - dow(today) + 7) % 7
                return Found(day(plusDays(today, if (fwd == 0) 7 else fwd)), true)
            }
        }
        if (has(t, "pichle hafte|pichhle hafte|last week|پچھلے ہفتے|گزشتہ ہفتے")) return Found(day(plusDays(today, -7)), false)
        if (has(t, "pichle mahine|pichhle mahine|last month|پچھلے مہینے|گزشتہ مہینے")) return Found(day(plusMonths(today, -1)), false)
        for (mm in Regex("$B(?:kal|kl|kall|yesterday|tomorrow|کل)$E").findAll(t)) {
            val before = t.substring(0, mm.range.first).trim()
            val after = t.substring(mm.range.last + 1).trim()
            if (Regex("(?:aaj|aj|آج)$").containsMatchIn(before)) continue
            if (Regex("$B(?:کا|کی|کے|سب|تمام|ka|ki|ke)$").containsMatchIn(before)) continue           // "پیٹرول کے کل 500": total
            if (Regex("^(?:ملا|ملاکر|رقم|خرچ|خرچہ|خرچے|خرچا|ٹوٹل|حساب|جمع|کتنا|کتنے|کتنی|بنے|بنا|بنتے|بیلنس|mila|milake|raqam|total|hisaab|hisab|se|سے)$E").containsMatchIn(after)) continue
            if (Regex("^(?:ادھار|udhaar|udhar)$E").containsMatchIn(after) && !has(after, PAST)) continue      // "کل ادھار کتنا ہے": total; "کل ادھار لیا": yesterday
            if (t.contains("کُل")) continue
            val w = mm.value
            val early = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY) < 4
            val sign = when (w) { "yesterday" -> -1; "tomorrow" -> if (early) 0 else 1; else -> if (future) (if (early) 0 else 1) else -1 }
            return Found(day(plusDays(today, sign)), true)
        }
        return null
    }

    private const val PAST = "diye|diya|di|liya|li|kiya|kiye|hua|hue|hui|tha|thi|the|dalwaya|khareeda|khareedi|bheje|bheja|mila|mile|mili|aaya|aaye|gaye|gaya|laga|lage|becha|bhara|chuka|chukaya|lautaye|lauta diye|lauta diya|" +
        "دیے|دئیے|دیا|دی|لیا|لی|کیا|کیے|ہوا|ہوئے|ہوئی|تھا|تھی|تھے|ڈلوایا|خریدا|خریدی|بھیجے|بھیجا|ملا|ملے|ملی|آیا|آئے|گئے|گیا|لگا|لگے|بیچا|بھرا|چکا|لوٹا دیے|لوٹا دیا"
    /** A promise: money that will move later ("dega", "wapis karega", "لوٹا دوں گا"). */
    private const val PROMISE = "\\p{L}+(?:ega|egi|enge|unga|oonga|ungi)|dega|degi|denge|dunga|doonga|lunga|loonga|گا|گی|گے"
    private const val FUTURE = "dena hai|dene hain|deni hai|dene hai|lena hai|lene hain|lene hai|karna hai|karne hain|yaad|remind|hoga|hogi|\\p{L}+na hai|\\p{L}+ni hai|" + PROMISE + "|" +
        "دینا ہے|دینے ہیں|دینی ہے|لینا ہے|لینے ہیں|کرنا ہے|کرنے ہیں|یاد|ہوگا|ہوگی|\\p{L}+نا ہے"
    fun pastWords(t: String) = has(Words.norm(t), PAST)
    fun futureWords(t: String) = has(Words.norm(t), FUTURE)
    fun promiseWords(t: String) = has(Words.norm(t), PROMISE)

    private val DAYWORD = Regex("$B(?:kal|kl|kall|کل|parson|parso|پرسوں|narson|ترسوں|$N ?(?:tareekh|tarikh|تاریخ)|(?:peer|mangal|budh|jumeraat|jumma|jumme|hafte|itwaar|پیر|منگل|بدھ|جمعرات|جمعہ|جمعے|ہفتے|اتوار)(?: (?:ko|کو))?)$E")

    /**
     * The words with the day of a promise taken out: in "Ali ko 500 diye kal wapis karega" the
     * "kal" belongs to the return, not to the 500 given today. A day word whose sentence goes on
     * to a future verb before any past one (or says "kal phir") is blanked.
     */
    fun withoutPromises(raw: String): String {
        var t = Words.norm(raw)
        for (m in DAYWORD.findAll(t).toList().asReversed()) {
            val tail = t.substring(m.range.last + 1)
            val fut = Regex("$B(?:$FUTURE)$E").find(tail)?.range?.first ?: -1
            val past = Regex("$B(?:$PAST)$E").find(tail)?.range?.first ?: -1
            val phir = Regex("^\\s*(?:phir|پھر)$E").containsMatchIn(tail)
            if (phir || fut >= 0 && (past < 0 || fut < past)) t = t.replaceRange(m.range, " ".repeat(m.value.length))
        }
        return t
    }
    private fun remindWords(t: String) = has(Words.norm(t), "yaad dila\\p{L}*|yad dila\\p{L}*|remind\\p{L}*|یاد دلا\\p{L}*|alarm|الارم")

    /** A time in the words: [hhmm] (or null), whether an hour was named, and the part of day said ("am", "noon", "pm", "night" or ""). */
    class Time(val hhmm: String?, val explicit: Boolean, val part: String)

    private val HOURS = listOf("ek|aik|ایک", "do|دو", "teen|تین", "char|chaar|چار", "panch|paanch|پانچ", "chhe|che|chay|چھ", "saat|سات", "aath|آٹھ",
        "nau|نو", "das|dus|دس", "gyarah|گیارہ", "barah|بارہ")

    /** The time the words name ("9 baje", "shaam paanch baje", "saadhe 9", "raat 12 baje", "صبح"). */
    fun timeOf(raw: String): Time {
        val t = Words.norm(raw)
        val part = when {
            has(t, "subah|subha|savere|morning|صبح|سویرے") -> "am"
            has(t, "dopahar|dupehar|dopehar|afternoon|دوپہر") -> "noon"
            has(t, "shaam|sham|evening|شام") -> "pm"
            has(t, "raat|rat|night|رات") -> "night"
            else -> ""
        }
        val hw = "\\d{1,2}|" + HOURS.joinToString("|")
        val unit = "(?:baje|bje|bajay|بجے|o'?clock|am|pm|a\\.m\\.|p\\.m\\.)"
        var h = -1; var mi = 0
        val half = Regex("$B(dedh|derh|ڈیڑھ|dhai|dhaai|ڈھائی) ?$unit$E").find(t)
        val m = Regex("$B(?:(saadhe|sadhe|sarhe|saarhe|ساڑھے|sawa|sava|سوا|paune|pone|پونے) )?($hw)(?:[:.](\\d{2}))? ?$unit$E").find(t)
            ?: Regex("$B()(\\d{1,2}):(\\d{2})$E").find(t)
        if (half != null) { h = if (half.groupValues[1] in setOf("dedh", "derh", "ڈیڑھ")) 1 else 2; mi = 30 }
        else if (m != null) {
            val w = m.groupValues[2]
            h = w.toIntOrNull() ?: (HOURS.indexOfFirst { it.split('|').contains(w) } + 1)
            mi = m.groupValues[3].toIntOrNull() ?: 0
            when (m.groupValues[1]) {
                "saadhe", "sadhe", "sarhe", "saarhe", "ساڑھے" -> mi += 30
                "sawa", "sava", "سوا" -> mi += 15
                "paune", "pone", "پونے" -> { h -= 1; mi += 45 }
            }
        }
        if (h < 0 || h > 23 || mi > 59) return Time(when (part) { "am" -> "09:00"; "noon" -> "13:00"; "pm" -> "18:00"; "night" -> "21:00"; else -> null }, false, part)
        val am = Regex("$B$h(?:[:.]\\d{2})? ?(?:am|a\\.m\\.)$E").containsMatchIn(t)
        val pm = Regex("$B$h(?:[:.]\\d{2})? ?(?:pm|p\\.m\\.)$E").containsMatchIn(t)
        if (h <= 12) {
            when {
                am -> if (h == 12) h = 0
                pm -> if (h < 12) h += 12
                part == "night" -> if (h == 12) h = 0 else if (h >= 6) h += 12         // raat 10 = 22:00, raat 12 = 00:00, raat 2 = 02:00
                part == "pm" -> if (h < 12) h += 12
                part == "noon" -> if (h < 6) h += 12
                part == "am" -> if (h == 12) h = 0
                else -> if (h in 1..6) h += 12                                       // "5 baje" is in the evening
            }
        }
        return Time(String.format(Locale.US, "%02d:%02d", h % 24, mi), true, if (am) "am" else if (pm) "pm" else part)
    }

    /** For old callers: the time as HH:mm, or null. */
    fun timeIn(raw: String): String? = timeOf(raw).hhmm

    private fun fits(hhmm: String, part: String): Boolean {
        val h = hhmm.take(2).toIntOrNull() ?: return false
        return when (part) { "am" -> h in 4..11; "noon" -> h in 11..15; "pm" -> h in 15..20; "night" -> h >= 19 || h <= 4; else -> true }
    }

    /**
     * When a reminder rings: the hour the user named, else the AI's time if it fits the part of
     * day said, else that part's usual hour; the day the words name, else the AI's; never in the past.
     */
    fun remindAt(aiRaw: String, clause: String, words: String, now: Long): String {
        val aiAt = at(aiRaw, now)
        val today = day(now)
        val tc = timeOf(clause).let { if (it.explicit || it.part.isNotEmpty()) it else timeOf(words) }
        val time = when {
            tc.explicit -> tc.hhmm!!
            aiAt.isNotEmpty() && fits(aiAt.substring(11), tc.part) -> aiAt.substring(11)
            tc.hhmm != null -> tc.hhmm
            aiAt.isNotEmpty() -> aiAt.substring(11)
            else -> "09:00"
        }
        val w = named(clause, now, true) ?: named(words, now, true)
        var dayPart = w?.date ?: aiAt.takeIf { it.isNotEmpty() }?.take(10) ?: today
        var whenAt = "$dayPart $time"
        val nowAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))
        if (whenAt <= nowAt) {
            val h = time.take(2).toInt()
            val later = today + " " + String.format(Locale.US, "%02d", h + 12) + time.substring(2)
            // "5 baje" said at 14:00 is 17:00 today; a time with subah/shaam/am said after it is tomorrow
            whenAt = if (dayPart == today && h < 12 && tc.part.isEmpty() && later > nowAt) later else { dayPart = plusDays(now, 1); "$dayPart $time" }
        }
        return whenAt
    }

    // ------------------------------------------------------------------ the guard ---

    private val SPLIT = Regex("[،;!?؟۔]+|(?<!\\d)[.,](?!\\d)|$B(?:aur|or|phir|پھر|اور)$E")

    /**
     * Fixes the dates in the AI's [ops] from the user's own words in [said] (and, when the AI
     * just asked a question, from the sentence before it, [before]). Money that already
     * happened is never dated after today; reminders are never set in the past. [kindOfId]
     * gives the kind of an existing record, for "change". Returns the number of dates changed.
     */
    fun fix(ops: JSONObject, said: String, now: Long, before: String = "", kindOfId: (Int) -> String? = { null }): Int {
        val today = day(now)
        var words = said
        if (named(said, now, false) == null && named(said, now, true) == null && before.isNotBlank()) words = "$before. $said"
        // the days of things that happened: a day that belongs to a promise is left out
        val pastText = withoutPromises(words)
        // the same cuts in both (a blanked day word is only spaces), so clause i is the same part of the sentence in each
        val clauses = pastText.split(SPLIT).map { it.trim() }
        val rawClauses = Words.norm(words).split(SPLIT).map { it.trim() }
        // each clause's day: a clause with no day word keeps the day of the one before it
        val pastDays = mutableListOf<Found?>()
        var carry: Found? = null
        for (c in clauses) { val f = named(c, now, false); if (f != null) carry = f; pastDays += carry }
        val distinct = pastDays.filterNotNull().map { it.date }.distinct()
        val used = mutableSetOf<Int>()
        fun mentions(c: String, w: String) = w.isNotEmpty() && (Words.roman(c).contains(w) || Words.norm(c).contains(w) ||
            w.length >= 3 && Words.roman(c).split(' ').any { People.key(it) == People.key(w) })
        fun clauseFor(a: JSONObject): Int {
            val amount = Words.amountOf(a.opt("amount"))
            val item = Words.str(a, "item").lowercase(Locale.ROOT)
            val person = Words.str(a, "person").lowercase(Locale.ROOT)
            val withAmount = if (amount > 0) clauses.indices.filter { i -> Words.amounts(clauses[i]).any { kotlin.math.abs(it - amount) < 0.01 } } else emptyList()
            // the clause with this amount that also names this thing or person, then one no other record took
            withAmount.firstOrNull { i -> mentions(clauses[i], item) || mentions(clauses[i], person) }?.let { used += it; return it }
            withAmount.firstOrNull { it !in used }?.let { used += it; return it }
            withAmount.firstOrNull()?.let { return it }
            clauses.indices.firstOrNull { i -> mentions(clauses[i], item) }?.let { return it }
            return -1
        }
        fun pastFor(a: JSONObject): Found? {
            val i = clauseFor(a)
            if (i >= 0) return pastDays[i]
            return if (distinct.size <= 1) named(pastText, now, false) else null
        }
        fun clauseText(a: JSONObject): String { val i = clauseFor(a); return if (i >= 0) clauses[i] else pastText }
        fun taskClause(a: JSONObject): String {
            val text = Words.str(a, "text").lowercase(Locale.ROOT)
            rawClauses.indexOfLast { remindWords(it) }.let { if (it >= 0) return rawClauses[it] }
            rawClauses.firstOrNull { c -> c.isNotEmpty() && text.split(' ').filter { it.length >= 3 }.any { mentions(c, it) } }?.let { return it }
            return Words.norm(words)
        }
        var changed = 0
        val adds = ops.optJSONArray("add") ?: ops.optJSONObject("add")?.let { JSONArray().put(it) }?.also { ops.put("add", it) }
        if (adds != null) for (k in 0 until adds.length()) {
            val a = adds.optJSONObject(k) ?: continue
            val kind = Memory.kindOf(Words.str(a, "kind")) ?: continue
            val ai = loose(Words.str(a, "date"), now, kind == "task")
            if (kind in Memory.CASH || kind == "settle") {
                val i = clauseFor(a)
                val raw = if (i >= 0 && i < rawClauses.size) rawClauses[i] else Words.norm(words)
                // money promised for later ("kal wapis karega") has not moved: nothing to write
                if (kind != "settle" && promiseWords(raw) && !pastWords(raw)) {
                    a.put("skip", "promise"); continue
                }
                val w = pastFor(a)
                val pick = when {
                    w != null && (ai == null || ai >= today || w.exact) -> w.date
                    ai == null -> today
                    ai > today -> today
                    else -> ai
                }
                if (pick != Words.str(a, "date")) { a.put("date", pick); if (ai != null && pick != ai) changed++ }
            } else if (kind == "owes_me" || kind == "i_owe") {
                // a debt is written on the day it is told, unless the words clearly say it began on a past day
                val c = clauseText(a)
                val w = if (pastWords(c) && !futureWords(c)) named(c, now, false) else null
                val pick = w?.date ?: if (ai != null && ai <= today) ai else today
                if (pick != Words.str(a, "date")) { a.put("date", pick); if (ai != null && pick != ai) changed++ }
            } else if (kind == "task") {
                val aiRaw = Words.str(a, "remind_at")
                if (at(aiRaw, now).isEmpty() && !remindWords(words)) { a.put("date", today); continue }
                val whenAt = remindAt(aiRaw, taskClause(a), Words.norm(words), now)
                if (whenAt != at(aiRaw, now)) changed++
                a.put("remind_at", whenAt)
                a.put("date", today)
            } else {
                // a note is filed on the day it is told, unless it is about something that already happened on a named day
                val c = clauseText(a)
                val w = if (pastWords(c) && !futureWords(c)) named(c, now, false)?.date else null
                a.put("date", w ?: if (ai != null && ai <= today && ai != today && pastWords(c)) ai else today)
            }
        }
        // "ye kal ka tha": a change follows the same rules as a new record
        val changes = ops.optJSONArray("change") ?: ops.optJSONObject("change")?.let { JSONArray().put(it) }?.also { ops.put("change", it) }
        if (changes != null) for (k in 0 until changes.length()) {
            val c = changes.optJSONObject(k) ?: continue
            val id = Words.str(c, "id").removePrefix("#").toIntOrNull() ?: continue
            val kind = Memory.kindOf(Words.str(c, "kind")) ?: kindOfId(id) ?: continue
            if (kind == "task" && Words.str(c, "remind_at").isNotEmpty()) {
                val whenAt = remindAt(Words.str(c, "remind_at"), Words.norm(words), Words.norm(words), now)
                if (whenAt != at(Words.str(c, "remind_at"), now)) changed++
                c.put("remind_at", whenAt)
            }
            if (!c.has("date")) continue
            val ai = loose(Words.str(c, "date"), now, kind == "task")
            if (ai == null) { c.remove("date"); continue }
            if (kind in Memory.CASH) {
                val w = named(withoutPromises(said), now, false)
                val pick = if (w != null && w.exact) w.date else if (ai > today) today else ai
                if (pick != ai) changed++
                c.put("date", pick)
            } else c.put("date", if (ai > today && kind != "task") today else ai)
        }
        return changed
    }

    /** How a day is said back: "aaj", "kal", "parson", "mangal" (this week), or "10 Nov". */
    fun spoken(date: String, now: Long): String {
        if (date == day(now)) return "aaj"
        if (date == plusDays(now, -1)) return "kal"
        if (date == plusDays(now, -2)) return "parson"
        val c = cal(date) ?: return date
        val diff = ((cal(now).timeInMillis - c.timeInMillis) / 86_400_000L).toInt()
        if (diff in 3..6) return WEEK[dow(c) - 1].second.substringBefore('/').replaceFirstChar { it.uppercase() }
        return SimpleDateFormat("d MMM", Locale.US).format(c.time)
    }

    /** The same in Urdu letters, for the Urdu voice. */
    fun spokenUrdu(date: String, now: Long): String {
        if (date == day(now)) return "آج"
        if (date == plusDays(now, -1)) return "کل"
        if (date == plusDays(now, -2)) return "پرسوں"
        val c = cal(date) ?: return date
        val diff = ((cal(now).timeInMillis - c.timeInMillis) / 86_400_000L).toInt()
        if (diff in 3..6) return WEEK[dow(c) - 1].second.substringAfter('/')
        return c.get(Calendar.DAY_OF_MONTH).toString() + " " + MONTHS[c.get(Calendar.MONTH)].substringAfterLast('|')
    }
}
