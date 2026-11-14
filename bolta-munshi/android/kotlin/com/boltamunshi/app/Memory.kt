package com.boltamunshi.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The memory book: everything the user told Smart Book to keep, as plain records. Money
 * (spent, earned, lent, borrowed, paid back, sold, bought) and anything else worth
 * remembering (notes, facts, tasks). The AI decides what to add, change or remove; this
 * class keeps the records, works out the totals exactly, and saves them after every change.
 */
class Memory(private val file: File) {

    class Rec(
        val id: Int,
        val kind: String,          // expense, income, gave, took, got_back, paid_back, sale, purchase, note, task
        val amount: Double,        // 0 when it is not about money
        val person: String,
        val item: String,
        val date: String,          // yyyy-MM-dd: when it happened
        val text: String,          // the user's own words, or the note itself
        val ts: Long,              // when it was written down
        val at: String = ""        // a task's reminder: yyyy-MM-dd HH:mm, or empty
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("amount", amount).put("person", person)
            .put("item", item).put("date", date).put("text", text).put("ts", ts).apply { if (at.isNotEmpty()) put("at", at) }

        /** When the reminder is due, or 0. */
        fun dueMs(): Long = if (at.isEmpty()) 0L else runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(at)!!.time }.getOrDefault(0L)

        /** A few words for the screen: "Petrol · 500", "Ali ka birthday 5 Dec". */
        fun short(): String {
            val what = item.ifBlank { text }.ifBlank { kind.replace('_', ' ') }.let { if (it.length > 34) it.take(33) + "…" else it }
            val who = if (person.isNotBlank() && !what.contains(person, true)) " · $person" else ""
            return what.replaceFirstChar { it.uppercase() } + who + (if (amount != 0.0) " · " + money(amount) else "")
        }

        /** One short line for the AI to read. */
        fun line(): String {
            val parts = mutableListOf("#$id", date, kind)
            if (amount != 0.0) parts += money(amount)
            if (item.isNotBlank()) parts += item
            if (person.isNotBlank()) parts += "person:$person"
            if (text.isNotBlank() && (kind == "note" || kind == "task" || item.isBlank())) parts += "\"$text\""
            if (at.isNotEmpty()) parts += "remind:$at"
            return parts.joinToString(" ")
        }
    }

    val recs = mutableListOf<Rec>()
    private var nextId = 1

    /** What the last [apply] did, for the screen and for Undo. */
    var added = listOf<Rec>(); private set
    var removed = listOf<Rec>(); private set
    var changed = listOf<Rec>(); private set
    /** Money the AI tried to add without an amount (not written). */
    var skipped = 0; private set
    private var before: Pair<List<Rec>, Int>? = null

    /** Puts the book back as it was before the last change. */
    fun undo(): Boolean {
        val b = before ?: return false
        recs.clear(); recs.addAll(b.first); nextId = b.second
        before = null; added = emptyList(); removed = emptyList(); changed = emptyList()
        save()
        return true
    }

    /** Deletes one record (from the memory book screen). */
    fun delete(id: Int): Boolean {
        val snap = recs.toList() to nextId
        if (!recs.removeAll { it.id == id }) return false
        before = snap
        save()
        return true
    }

    init { load() }

    private fun load() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        val arr = o.optJSONArray("recs") ?: JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { recs += of(it) }
        nextId = maxOf(o.optInt("next", 1), (recs.maxOfOrNull { it.id } ?: 0) + 1)
    }

    fun save() {
        val o = JSONObject().put("v", 1).put("next", nextId).put("recs", JSONArray().apply { recs.forEach { put(it.toJson()) } })
        file.writeText(o.toString())
    }

    fun json(): String = file.takeIf { it.exists() }?.readText() ?: "{}"

    /** Replaces everything with a backup's records; false if it is not a memory book. */
    fun restore(text: String): Boolean {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val arr = o.optJSONArray("recs") ?: return false
        recs.clear()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { recs += of(it) }
        nextId = (recs.maxOfOrNull { it.id } ?: 0) + 1
        save()
        return true
    }

    fun clear() { recs.clear(); nextId = 1; save() }

    private fun of(o: JSONObject) = Rec(o.optInt("id"), o.optString("kind", "note"), o.optDouble("amount", 0.0).let { if (it.isNaN()) 0.0 else it },
        o.optString("person").trim(), o.optString("item").trim(), o.optString("date"), o.optString("text"), o.optLong("ts"), atOf(o.optString("at")))

    /**
     * What the AI asked for: {"add":[...], "remove":[ids], "change":[{"id":..}]}.
     * Returns how many records changed. Anything malformed is skipped, never guessed.
     */
    fun apply(ops: JSONObject, now: Long): Int {
        var n = 0
        val today = day(now)
        val snap = recs.toList() to nextId
        val add = mutableListOf<Rec>(); val gone = mutableListOf<Rec>(); val edit = mutableListOf<Rec>()
        skipped = 0
        ops.optJSONArray("remove")?.let { arr ->
            for (i in 0 until arr.length()) {
                val id = arr.optString(i).trim().removePrefix("#").toIntOrNull() ?: continue
                recs.firstOrNull { it.id == id }?.let { recs.remove(it); gone += it; n++ }
            }
        }
        ops.optJSONArray("change")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optString("id").trim().removePrefix("#").toIntOrNull() ?: continue
                val at = recs.indexOfFirst { it.id == id }
                if (at < 0) continue
                val old = recs[at]
                recs[at] = Rec(old.id, kindOf(c.optString("kind", old.kind)), c.optDouble("amount", old.amount).let { if (it.isNaN()) old.amount else kotlin.math.abs(it) },
                    c.optString("person", old.person).trim(), c.optString("item", old.item).trim(), dateOf(c.optString("date", old.date), today),
                    c.optString("text", old.text), old.ts, if (c.has("remind_at")) atOf(c.optString("remind_at")) else old.at)
                edit += recs[at]
                n++
            }
        }
        ops.optJSONArray("add")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val kind = kindOf(a.optString("kind"))
                val amount = a.optDouble("amount", 0.0).let { if (it.isNaN()) 0.0 else kotlin.math.abs(it) }
                if (kind in MONEY && amount <= 0.0) { skipped++; continue }      // money without an amount is not written
                val r = Rec(nextId++, kind, amount, a.optString("person").trim(), a.optString("item").trim(),
                    dateOf(a.optString("date"), today), a.optString("text").trim(), now, atOf(a.optString("remind_at")))
                // the same thing twice in a row (the AI repeating itself) is written once
                if (recs.take(5).any { it.kind == r.kind && it.amount == r.amount && it.item.equals(r.item, true) && it.person.equals(r.person, true)
                        && it.date == r.date && it.text == r.text && now - it.ts < 120_000L }) { nextId--; continue }
                recs.add(0, r); add += r
                n++
            }
        }
        if (n > 0) { before = snap; added = add; removed = gone; changed = edit; save() }
        return n
    }

    /** Tasks with a reminder still to come, soonest first. */
    fun reminders(now: Long): List<Rec> = recs.filter { it.kind == "task" && it.dueMs() > now }.sortedBy { it.dueMs() }

    /**
     * Older records that match the words of what was said (a name, an item, a word of a note),
     * so the AI can answer about things too old to be among the newest ones it reads.
     */
    fun relevant(said: String, skip: Int = 150, max: Int = 40): String {
        val words = said.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in COMMON }.toSet()
        if (words.isEmpty() || recs.size <= skip) return ""
        return recs.drop(skip).filter { r ->
            val hay = (r.item + " " + r.person + " " + r.text).lowercase(Locale.ROOT)
            words.any { hay.contains(it) }
        }.take(max).joinToString("\n") { it.line() }
    }

    // ------------------------------------------------------------------ totals ---

    /** Exact totals the AI reads instead of adding up by itself. */
    fun summary(now: Long): String {
        val today = day(now)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val yesterday = day(now - 86_400_000L)
        val month = today.substring(0, 7)
        val lastMonth = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.MONTH, -1) }.let { SimpleDateFormat("yyyy-MM", Locale.US).format(it.time) }
        val weekStart = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }.let { day(it.timeInMillis) }
        val year = cal.get(Calendar.YEAR).toString()
        fun period(name: String, test: (String) -> Boolean): String {
            val inP = recs.filter { test(it.date) }
            val spent = inP.filter { it.kind == "expense" || it.kind == "purchase" }.sumOf { it.amount }
            val earned = inP.filter { it.kind == "income" || it.kind == "sale" }.sumOf { it.amount }
            val byItem = inP.filter { it.kind == "expense" }.groupBy { it.item.ifBlank { "other" }.lowercase(Locale.ROOT) }
                .mapValues { e -> e.value.sumOf { it.amount } }.toList().sortedByDescending { it.second }.take(8)
            return "$name: spent ${money(spent)}, earned ${money(earned)}" + (if (byItem.isNotEmpty()) " (" + byItem.joinToString(", ") { it.first + " " + money(it.second) } + ")" else "")
        }
        val lines = mutableListOf(
            "Today is $today (${SimpleDateFormat("EEEE", Locale.US).format(Date(now))}), the time is ${SimpleDateFormat("HH:mm", Locale.US).format(Date(now))}.",
            period("Today", { it == today }),
            period("Yesterday", { it == yesterday }),
            period("This week (from $weekStart)", { it >= weekStart && it <= today }),
            period("This month ($month)", { it.startsWith(month) }),
            period("Last month ($lastMonth)", { it.startsWith(lastMonth) }),
            period("This year", { it.startsWith(year) }),
            period("All time", { true })
        )
        // month by month, for questions about any month of the last year
        val months = recs.map { it.date.take(7) }.filter { it.length == 7 && it != month && it != lastMonth }.distinct().sortedDescending().take(10)
        if (months.isNotEmpty()) lines += "Earlier months: " + months.joinToString("; ") { m ->
            val r = recs.filter { it.date.startsWith(m) }
            "$m spent ${money(r.filter { it.kind == "expense" || it.kind == "purchase" }.sumOf { it.amount })}, earned ${money(r.filter { it.kind == "income" || it.kind == "sale" }.sumOf { it.amount })}"
        }
        val due = reminders(now)
        if (due.isNotEmpty()) lines += "Reminders to come: " + due.take(15).joinToString("; ") { "#${it.id} ${it.at} ${it.text.ifBlank { it.item }}" }
        val bal = balances()
        lines += if (bal.isEmpty()) "Loans: none." else "Loans (positive = they owe the user, negative = the user owes them): " +
            bal.joinToString(", ") { it.first + " " + (if (it.second > 0) "+" else "-") + money(kotlin.math.abs(it.second)) }
        return lines.joinToString("\n")
    }

    /** Each person's balance: + they owe the user, - the user owes them. */
    fun balances(): List<Pair<String, Double>> {
        val m = linkedMapOf<String, Pair<String, Double>>()
        for (r in recs.reversed()) {
            if (r.person.isBlank()) continue
            val d = when (r.kind) { "gave", "paid_back" -> r.amount; "took", "got_back" -> -r.amount; else -> 0.0 }
            if (d == 0.0) continue
            val k = r.person.lowercase(Locale.ROOT)
            val cur = m[k]
            m[k] = (cur?.first ?: r.person) to ((cur?.second ?: 0.0) + d)
        }
        return m.values.filter { kotlin.math.abs(it.second) > 0.005 }
    }

    /** The newest records, for the AI to read (it can quote and change them by id). */
    fun recent(max: Int = 150): String = if (recs.isEmpty()) "(nothing written yet)" else recs.take(max).joinToString("\n") { it.line() }

    companion object {
        val MONEY = setOf("expense", "income", "gave", "took", "got_back", "paid_back", "sale", "purchase")
        val KINDS = MONEY + setOf("note", "task")

        fun kindOf(k: String) = k.trim().lowercase(Locale.ROOT).replace(' ', '_').let { if (it in KINDS) it else "note" }

        fun day(ms: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

        /** A yyyy-MM-dd date, or today when the AI gave none or something else. */
        fun dateOf(d: String, today: String) = if (Regex("""\d{4}-\d{2}-\d{2}""").matches(d.trim())) d.trim() else today

        /** A reminder time as yyyy-MM-dd HH:mm, or empty when it is not one. */
        fun atOf(a: String): String {
            val t = a.trim().replace('T', ' ')
            return if (Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(t.take(16)) && t.length >= 16) t.take(16) else ""
        }

        private val COMMON = setOf("aur", "kya", "kitna", "kitne", "kitni", "hai", "hain", "tha", "the", "thi", "mein", "main", "mera", "meri",
            "mere", "kal", "aaj", "liye", "liya", "diya", "diye", "kiya", "karo", "kar", "batao", "bata", "wala", "wali", "rupay", "rupee", "rupees",
            "the", "and", "what", "how", "much", "yaad", "abhi", "pehle", "baad", "hua", "huwa", "gaya", "gaye", "total", "kharcha", "hisaab")

        fun money(v: Double): String {
            val whole = kotlin.math.abs(v - Math.rint(v)) < 0.005
            return if (whole) String.format(Locale.US, "%,d", Math.rint(v).toLong()) else String.format(Locale.US, "%,.2f", v)
        }
    }
}
