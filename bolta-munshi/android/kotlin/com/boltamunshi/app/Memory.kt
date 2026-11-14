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
 * (spent, earned, sold, bought), lena-dena (lent, borrowed, paid back, got back, and debts
 * with no money moving yet: "Ali se 1000 lene hain", "Bilal ke 500 dene hain") and anything
 * else worth remembering (notes, tasks with reminders). The AI says what to add, change or
 * remove; this class checks it, keeps the records, works out every total and every person's
 * balance exactly, and saves safely after each change.
 */
class Memory(private val file: File) {

    class Rec(
        val id: Int,
        val kind: String,          // see KINDS
        val amount: Double,        // 0 when it is not about money
        val person: String,
        val item: String,
        val date: String,          // yyyy-MM-dd: when it happened
        val text: String,          // the user's own words, or the note itself
        val ts: Long,              // when it was written down
        val at: String = "",       // a task's reminder: yyyy-MM-dd HH:mm, or empty
        val udhaar: Boolean = false // goods or a bill on credit: expense/purchase (user owes person) or sale (person owes user)
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("amount", amount).put("person", person)
            .put("item", item).put("date", date).put("text", text).put("ts", ts)
            .apply { if (at.isNotEmpty()) put("at", at); if (udhaar) put("udhaar", true) }

        /** When the reminder is due, or 0. */
        fun dueMs(): Long = if (at.isEmpty()) 0L else runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(at)!!.time }.getOrDefault(0L)

        /** What this record does to the person's balance: + the person owes the user more, - the user owes them more. */
        fun effect(): Double = when (kind) {
            "gave", "paid_back", "owes_me" -> amount
            "took", "got_back", "i_owe" -> -amount
            "sale" -> if (udhaar) amount else 0.0
            "expense", "purchase" -> if (udhaar) -amount else 0.0
            else -> 0.0
        }

        /** A few words for the screen: "Petrol · 500", "Ali ko diye · 500", "Ali ka birthday 5 Dec". */
        fun short(): String {
            val p = PERSON_WORDS[kind]
            val what = when {
                p != null && person.isNotBlank() -> p.first.replace("X", person)
                else -> item.ifBlank { text }.ifBlank { kind.replace('_', ' ') }.replaceFirstChar { it.uppercase() } +
                    (if (udhaar) " udhaar" else "") + (if (person.isNotBlank() && !item.contains(person, true)) " · $person" else "")
            }.let { if (it.length > 40) it.take(39) + "…" else it }
            return what + (if (amount != 0.0) " · " + money(amount) else "")
        }

        /** One short line for the AI to read (numbers without commas, so they are copied right). */
        fun line(): String {
            val parts = mutableListOf("#$id", date, kind + (if (udhaar) "(udhaar)" else ""))
            if (amount != 0.0) parts += plain(amount)
            if (item.isNotBlank()) parts += item
            if (person.isNotBlank()) parts += "person:$person"
            if (text.isNotBlank() && (kind == "note" || kind == "task" || item.isBlank())) parts += "\"" + text.take(120) + "\""
            if (at.isNotEmpty()) parts += "remind:$at"
            return parts.joinToString(" ")
        }
    }

    class Person(val name: String, val bal: Double, val byKind: Map<String, Double>, val last: String)

    val recs = mutableListOf<Rec>()
    private var nextId = 1

    /** What the last [apply] did, for the screen, the chat history and Undo. */
    var added = listOf<Rec>(); private set
    var removed = listOf<Rec>(); private set
    var changed = listOf<Rec>(); private set
    /** What the last [apply] could not write and needs a question for. */
    var needAmount = 0; private set
    var needPerson = 0; private set
    var unclear = 0; private set
    /** People whose hisaab was already even when the AI asked to settle it. */
    var alreadyEven = listOf<String>(); private set
    /** Repayments with no loan (or more than the loan) in the book: person and the amount not written. */
    var noDebt = listOf<Pair<String, Double>>(); private set
    /** Money that is only promised ("kal wapis karega"): not written. */
    var promises = 0; private set
    /** For the old tests and the screen: everything that was not written. */
    val skipped get() = needAmount + needPerson + unclear + noDebt.size + promises
    private var before: Pair<List<Rec>, Int>? = null
    /** False when the book file could not be read; nothing is saved over it then. */
    var readable = true; private set

    init { load() }

    // ------------------------------------------------------------------ the file ---

    private val bak get() = File(file.path + ".bak")
    private val tmp get() = File(file.path + ".tmp")

    private fun read(f: File): JSONObject? = runCatching { if (f.exists() && f.length() > 0) JSONObject(f.readText()) else null }.getOrNull()

    private fun load() {
        val o = read(file) ?: read(bak)
        if (o == null) {
            if (file.exists() && file.length() > 0) {
                // a book that cannot be read is kept aside, never written over
                runCatching { file.copyTo(File(file.path + ".broken-" + System.currentTimeMillis()), overwrite = true) }
            }
            return
        }
        val arr = o.optJSONArray("recs") ?: JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { recs += of(it) }
        nextId = maxOf(o.optInt("next", 1), (recs.maxOfOrNull { it.id } ?: 0) + 1)
    }

    /** Writes a new file and then swaps it in, keeping the one before as .bak: a save cut off halfway never empties the book. */
    fun save() {
        val o = JSONObject().put("v", 2).put("next", nextId).put("recs", JSONArray().apply { recs.forEach { put(it.toJson()) } })
        tmp.writeText(o.toString())
        if (file.exists()) { bak.delete(); file.renameTo(bak) }
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    fun json(): String = JSONObject().put("v", 2).put("next", nextId).put("recs", JSONArray().apply { recs.forEach { put(it.toJson()) } }).toString()

    /** Replaces everything with a backup's records (Undo brings the old book back); false if it is not a memory book or would empty a full one. */
    fun restore(text: String): Boolean {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val arr = o.optJSONArray("recs") ?: return false
        val got = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { j -> of(j) } }
        if (got.isEmpty() && recs.isNotEmpty()) return false
        before = recs.toList() to nextId
        recs.clear(); recs.addAll(got)
        nextId = maxOf(o.optInt("next", 1), (recs.maxOfOrNull { it.id } ?: 0) + 1)
        added = emptyList(); removed = emptyList(); changed = emptyList()
        save()
        return true
    }

    /** Forgets everything; Undo brings it back until the next change. */
    fun clear() { before = recs.toList() to nextId; recs.clear(); added = emptyList(); removed = emptyList(); changed = emptyList(); save() }

    private fun of(o: JSONObject) = Rec(o.optInt("id"), kindOf(Words.str(o, "kind")) ?: "note", Words.amountOf(o.opt("amount")),
        Words.str(o, "person"), Words.str(o, "item"), Words.str(o, "date").ifEmpty { Dates.day(o.optLong("ts", System.currentTimeMillis())) },
        Words.str(o, "text"), o.optLong("ts"), Words.str(o, "at"), o.optBoolean("udhaar", false))

    /** Puts the book back as it was before the last change. */
    fun undo(): Boolean {
        val b = before ?: return false
        recs.clear(); recs.addAll(b.first); nextId = b.second
        before = null; added = emptyList(); removed = emptyList(); changed = emptyList()
        save()
        return true
    }

    /** Deletes one record (from the memory book screen); Undo brings it back. */
    fun delete(id: Int): Boolean {
        val snap = recs.toList() to nextId
        val r = recs.firstOrNull { it.id == id } ?: return false
        recs.remove(r)
        before = snap; added = emptyList(); removed = listOf(r); changed = emptyList()
        save()
        return true
    }

    // ------------------------------------------------------------------ writing ---

    private fun ids(v: Any?): List<Int> = when (v) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until v.length()).flatMap { ids(v.opt(it)) }
        is JSONObject -> ids(v.opt("id"))
        is Number -> listOf(v.toInt())
        else -> listOfNotNull(v.toString().trim().removePrefix("#").toIntOrNull())
    }

    private fun objects(v: Any?): List<JSONObject> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }
        is JSONObject -> listOf(v)
        else -> emptyList()
    }

    /** The people already in the book, by their stored names. */
    fun knownPeople(): List<String> = recs.map { it.person }.filter { it.isNotBlank() }.distinct()

    /**
     * What the AI asked for: {"add":[...], "remove":[ids], "change":[{"id":..}]}. Returns how many
     * records changed. Nothing is guessed: money without an amount, lena-dena without a person
     * and kinds that are not known are left out and counted, so the app can ask.
     */
    fun apply(ops: JSONObject, now: Long): Int {
        var n = 0
        val today = Dates.day(now)
        val snap = recs.toList() to nextId
        val add = mutableListOf<Rec>(); val gone = mutableListOf<Rec>(); val edit = mutableListOf<Rec>()
        needAmount = 0; needPerson = 0; unclear = 0; promises = 0
        val even = mutableListOf<String>()
        owed.clear()
        val touched = mutableSetOf<Int>()
        for (id in ids(ops.opt("remove"))) recs.firstOrNull { it.id == id }?.let { recs.remove(it); gone += it; n++ }
        for (c in objects(ops.opt("change"))) {
            val id = ids(c.opt("id")).firstOrNull() ?: continue
            val at = recs.indexOfFirst { it.id == id }
            if (at < 0) continue
            val old = recs[at]
            val kind = Words.str(c, "kind").let { if (it.isEmpty()) old.kind else kindOf(it)?.takeIf { k -> k != "settle" } ?: old.kind }
            val amount = Words.amountOf(c.opt("amount")).let { if (it > 0) it else old.amount }
            val person = Words.str(c, "person").let { if (it.isEmpty()) old.person else People.canonical(it, knownPeople()) }
            var date = Words.str(c, "date").let { if (it.isEmpty()) old.date else Dates.loose(it, now, kind == "task") ?: old.date }
            if (kind in MONEY && date > today) date = old.date
            val remind = Words.str(c, "remind_at").let { if (it.isEmpty()) old.at else Dates.at(it, now).ifEmpty { old.at } }
            val udhaar = if (Words.str(c, "udhaar").isNotEmpty()) truthy(c.opt("udhaar")) else old.udhaar
            val item = itemOf(Words.str(c, "item")).ifEmpty { old.item }
            val text = Words.str(c, "text").ifEmpty { old.text }
            val swap = setOf("gave", "took", "got_back", "paid_back")
            val side = if (old.kind == "gave" || old.kind == "paid_back") setOf("gave", "paid_back") else setOf("took", "got_back")
            // a loan the app split (paid back 1000 + lent 500 from one "1500 diye") is fixed as a whole; anything else is edited as it is
            val group = if (old.kind in swap) recs.filter { it.ts == old.ts && it.kind in side && it.id !in touched && People.key(it.person) == People.key(old.person) } else emptyList()
            val split = group.size == 2 && group.map { it.kind }.toSet().size == 2
            if (split && (amount != old.amount || kind != old.kind || person != old.person)) {
                val total = if (amount != old.amount) amount else group.sumOf { it.amount }
                val pos = recs.indexOfFirst { it in group }
                recs.removeAll(group)
                val fresh = when (kind) {
                    "gave", "paid_back" -> relabel("gave", total, person, item, date, text, old.ts, "", false)
                    "took", "got_back" -> relabel("took", total, person, item, date, text, old.ts, "", false)
                    else -> listOf(Rec(nextId++, kind, total, person, item, date, text, old.ts, remind, udhaar))
                }
                recs.addAll(maxOf(0, minOf(pos, recs.size)), fresh)
                gone += group.filter { g -> fresh.none { it.id == g.id } }
                edit += fresh; touched += fresh.map { it.id }; n++
                continue
            }
            val r = Rec(old.id, kind, amount, person, item, date, text, old.ts, remind, udhaar)
            recs[at] = r; edit += r; touched += r.id; n++
        }
        // "hisaab clear" is worked out after everything else said in the same sentence
        // new loans and debts first, then repayments, then "hisaab clear", whatever order the AI wrote them in
        val adds = objects(ops.opt("add")).sortedBy { when (kindOf(Words.str(it, "kind").ifEmpty { Words.str(it, "type") })) { "settle" -> 2; "got_back", "paid_back" -> 1; else -> 0 } }
        for (a in adds) {
            if (Words.str(a, "skip") == "promise") { promises++; continue }
            val rawKind = Words.str(a, "kind").ifEmpty { Words.str(a, "type") }
            val amount = Words.amountOf(a.opt("amount"))
            val text = Words.str(a, "text").ifEmpty { Words.str(a, "note") }
            val item = itemOf(Words.str(a, "item"))
            var kind = kindOf(rawKind)
            if (kind == "gave" && GIFT.containsMatchIn(Words.roman(item + " " + text) + " " + Words.norm(item + " " + text))) kind = "expense"   // a gift is not udhaar
            if (kind == null) {
                if (amount > 0) { unclear++; continue }                       // money of an unknown kind: ask, never file it as a note
                if (text.isEmpty() && item.isEmpty()) continue
                kind = "note"
            }
            val person = People.canonical(Words.str(a, "person").ifEmpty { Words.str(a, "name") }, knownPeople())
            val udhaar = truthy(a.opt("udhaar")) || truthy(a.opt("credit"))
            if (kind == "settle") {
                if (person.isEmpty()) { needPerson++; continue }
                val b = balanceOf(person)
                if (kotlin.math.abs(b) < 0.005) { even += person; continue }
                val date = Dates.loose(Words.str(a, "date"), now)?.takeIf { it <= today } ?: today
                val r = Rec(nextId++, if (b > 0) "got_back" else "paid_back", kotlin.math.abs(b), person, item, date, text.ifEmpty { "hisaab barabar" }, now)
                recs.add(0, r); add += r; n++
                continue
            }
            if (kind in MONEY && amount <= 0.0) { needAmount++; continue }
            if ((kind in LOANS || udhaar && kind in setOf("expense", "purchase", "sale")) && person.isEmpty()) { needPerson++; continue }
            if ((kind == "note" || kind == "task") && text.isEmpty() && item.isEmpty()) continue
            var date = Dates.loose(Words.str(a, "date"), now, kind == "task") ?: today
            if ((kind in MONEY || kind == "task") && date > today) date = today          // money already happened; a task is written today
            val remind = if (kind == "task") Dates.at(Words.str(a, "remind_at"), now) else ""
            // paying someone the user owes, written as plain spending: it pays the debt off instead of counting twice
            if ((kind == "expense" || kind == "purchase") && !udhaar && person.isNotEmpty()) {
                val b = balanceOf(person)
                if (b < -0.005) {
                    val theirs = recs.filter { it.person.isNotBlank() && People.key(it.person) == People.key(person) }
                    val onCredit = theirs.any { (it.udhaar && (it.kind == "expense" || it.kind == "purchase")) || it.kind == "took" }
                    // a payment, not new goods: no item, a payment word, or (for a debt with no goods behind it) the thing the person is named after
                    val payment = item.isEmpty() || item.lowercase(Locale.ROOT) in PAYMENT_WORDS ||
                        !onCredit && (theirs.any { it.kind == "i_owe" && (it.item.isEmpty() || it.item.equals(item, true)) } || People.key(person).contains(People.key(item)))
                    if (payment) {
                        val pay = minOf(amount, -b)
                        val out = mutableListOf<Rec>()
                        if (onCredit) {
                            // the spending was already counted when it was bought on udhaar: this only pays it back
                            out += Rec(nextId++, "paid_back", pay, person, item, date, text, now)
                            if (amount > pay + 0.005) out += Rec(nextId++, kind, amount - pay, person, item, date, text, now)
                        } else {
                            // a debt for work or goods ("mistri ke 3000 dene hain"): the payment is spending, and it closes the debt
                            out += Rec(nextId++, kind, amount, person, item, date, text, now)
                            out += Rec(nextId++, "paid_back", pay, person, item, date, "hisaab mein se", now)
                        }
                        for (r in out) { recs.add(0, r); add += r; n++ }
                        continue
                    }
                }
            }
            for (r in relabel(kind, amount, person, item, date, text, now, remind, udhaar && kind in setOf("expense", "purchase", "sale"))) {
                recs.add(0, r); add += r; n++
            }
        }
        alreadyEven = even
        noDebt = owed.toList()
        if (n > 0) { before = snap; added = add; removed = gone; changed = edit; save() }
        else { added = emptyList(); removed = emptyList(); changed = emptyList() }
        return n
    }

    /**
     * The right word for a loan from the person's balance: giving to someone the user owes is
     * paying back, taking from someone who owes the user is getting back (split at the balance).
     * The balance comes out the same either way; only the label is made true.
     */
    private val owed = mutableListOf<Pair<String, Double>>()

    private fun relabel(kind: String, amount: Double, person: String, item: String, date: String, text: String, now: Long, at: String, udhaar: Boolean): List<Rec> {
        fun rec(k: String, a: Double) = Rec(nextId++, k, a, person, item, date, text, now, at, udhaar)
        if (person.isEmpty() || kind !in setOf("gave", "took", "got_back", "paid_back")) return listOf(rec(kind, amount))
        val b = balanceOf(person)
        return when (kind) {
            "gave" -> if (b < -0.005) split("paid_back", -b, "gave", amount, ::rec) else listOf(rec("gave", amount))
            "took" -> if (b > 0.005) split("got_back", b, "took", amount, ::rec) else listOf(rec("took", amount))
            // a repayment only repays: with no loan in the book (or beyond it) nothing new is made up, and the app asks
            "paid_back" -> if (b < -0.005) { if (amount > -b + 0.005) owed += person to (amount + b); listOf(rec("paid_back", minOf(amount, -b))) } else { owed += person to amount; emptyList() }
            // owed: + is money the user paid back beyond the book's debt, - is money received back beyond it
            else -> if (b > 0.005) { if (amount > b + 0.005) owed += person to -(amount - b); listOf(rec("got_back", minOf(amount, b))) } else { owed += person to -amount; emptyList() }
        }
    }

    /** An item as one spelling: Urdu words the app knows in Roman letters ("پیٹرول" = petrol). */
    private fun itemOf(raw: String): String = (Words.dictRoman(raw) ?: raw).trim()

    private fun split(first: String, room: Double, rest: String, amount: Double, rec: (String, Double) -> Rec): List<Rec> =
        if (amount <= room + 0.005) listOf(rec(first, amount)) else listOf(rec(first, room), rec(rest, amount - room))

    private fun truthy(v: Any?) = v == true || v?.toString()?.trim()?.lowercase(Locale.ROOT) in setOf("true", "yes", "1", "haan")

    // ------------------------------------------------------------------ reading ---

    /** Tasks with a reminder still to come, soonest first. */
    fun reminders(now: Long): List<Rec> = recs.filter { it.kind == "task" && it.dueMs() > now }.sortedBy { it.dueMs() }

    /** Everyone with lena-dena, the largest balance first. */
    fun people(): List<Person> {
        val groups = linkedMapOf<String, MutableList<Rec>>()
        for (r in recs.reversed()) if (r.person.isNotBlank() && r.effect() != 0.0) groups.getOrPut(People.key(r.person)) { mutableListOf() } += r
        return groups.values.map { rs ->
            val label = { r: Rec -> r.kind + (if (r.udhaar) " udhaar" else "") }
            Person(rs.last().person, rs.sumOf { it.effect() }, rs.groupBy(label).mapValues { e -> e.value.sumOf { it.amount } }, rs.maxOf { it.date })
        }.sortedByDescending { kotlin.math.abs(it.bal) }
    }

    fun balanceOf(person: String): Double = People.key(person).let { k -> recs.filter { it.person.isNotBlank() && People.key(it.person) == k }.sumOf { it.effect() } }

    /** Each person's balance: + they owe the user, - the user owes them. */
    fun balances(): List<Pair<String, Double>> = people().filter { kotlin.math.abs(it.bal) > 0.005 }.map { it.name to it.bal }

    class Period(val name: String, val from: String, val to: String) {
        fun has(d: String) = d >= from && d <= to
    }

    /** The periods the totals are given for. */
    fun periods(now: Long): List<Period> {
        val today = Dates.day(now)
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7                     // Monday = 0
        val weekStart = Dates.plusDays(now, -dow)
        val month = today.take(7)
        val lastMonth = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -1) }.let { SimpleDateFormat("yyyy-MM", Locale.US).format(it.time) }
        return listOf(
            Period("Aaj $today", today, today),
            Period("Kal " + Dates.plusDays(now, -1), Dates.plusDays(now, -1), Dates.plusDays(now, -1)),
            Period("Parson " + Dates.plusDays(now, -2), Dates.plusDays(now, -2), Dates.plusDays(now, -2)),
            Period("This week (Monday $weekStart to today)", weekStart, today),
            Period("Last week (" + Dates.plusDays(now, -dow - 7) + " to " + Dates.plusDays(now, -dow - 1) + ")", Dates.plusDays(now, -dow - 7), Dates.plusDays(now, -dow - 1)),
            Period("Last 7 days", Dates.plusDays(now, -6), today),
            Period("This month $month", "$month-01", "$month-31"),
            Period("Last month $lastMonth", "$lastMonth-01", "$lastMonth-31"),
            Period("This year " + today.take(4), today.take(4) + "-01-01", today.take(4) + "-12-31"),
            Period("All time", "0000", "9999")
        )
    }

    fun spent(p: Period) = recs.filter { p.has(it.date) && (it.kind == "expense" || it.kind == "purchase") }.sumOf { it.amount }
    fun earned(p: Period) = recs.filter { p.has(it.date) && (it.kind == "income" || it.kind == "sale") }.sumOf { it.amount }

    /** Spending per item in a period: (item, total, how many times), the largest first. */
    fun itemList(p: Period): List<Triple<String, Double, Int>> =
        recs.filter { p.has(it.date) && (it.kind == "expense" || it.kind == "purchase") }.groupBy { it.item.ifBlank { "other" }.lowercase(Locale.ROOT) }
            .map { (k, v) -> Triple(k, v.sumOf { it.amount }, v.size) }.sortedByDescending { it.second }

    /** Spending per item in a period, with how many times: "petrol 3500 (5x)". */
    fun items(p: Period, max: Int = 30): String {
        val g = itemList(p)
        if (g.isEmpty()) return ""
        return g.take(max).joinToString(", ") { it.first + " " + plain(it.second) + " (" + it.third + "x)" } + (if (g.size > max) ", +" + (g.size - max) + " more" else "")
    }

    /** The exact totals and every person's balance, as the AI reads them. */
    fun summary(now: Long): String {
        val ps = periods(now)
        val lines = mutableListOf("TOTALS (exact; spent = expense+purchase, earned = income+sale; lena-dena is not spending)")
        for (p in ps) lines += p.name + ": spent " + plain(spent(p)) + ", earned " + plain(earned(p))
        val month = Dates.day(now).take(7)
        val lastMonth = ps[7].from.take(7)
        val months = recs.map { it.date.take(7) }.filter { it.length == 7 && it < month && it != lastMonth }.distinct().sortedDescending().take(10)
        if (months.isNotEmpty()) lines += "Earlier months: " + months.joinToString("; ") { m ->
            val p = Period(m, "$m-01", "$m-31"); "$m spent " + plain(spent(p)) + ", earned " + plain(earned(p))
        }
        val week = (0..6).map { Dates.plusDays(now, -it) }.mapNotNull { d -> val p = Period(d, d, d); spent(p).takeIf { it > 0 }?.let { "$d spent " + plain(it) } }
        if (week.isNotEmpty()) lines += "Each day of the last 7: " + week.joinToString("; ")
        lines += ""
        lines += "ITEM TOTALS (spending per item)"
        for (p in ps) items(p, if (p === ps[9]) 20 else 15).takeIf { it.isNotEmpty() }?.let { lines += p.name.substringBefore(" (") + ": " + it }
        lines += ""
        lines += lenaDena()
        val due = reminders(now)
        if (due.isNotEmpty()) { lines += ""; lines += "REMINDERS TO COME: " + due.take(15).joinToString("; ") { "#${it.id} ${it.at} ${it.text.ifBlank { it.item }}" } }
        return lines.joinToString("\n")
    }

    /** Every balance in words that are already the right way round. */
    fun lenaDena(): String {
        val all = people()
        val lena = all.filter { it.bal > 0.005 }
        val dena = all.filter { it.bal < -0.005 }
        val even = all.filter { kotlin.math.abs(it.bal) <= 0.005 }
        if (lena.isEmpty() && dena.isEmpty()) return "LENA-DENA: koi udhaar nahi (nobody owes the user and the user owes nobody)." +
            (if (even.isNotEmpty()) " Hisaab barabar: " + even.joinToString(", ") { it.name } + "." else "")
        val out = mutableListOf("LENA-DENA (exact, already the right way round: say it as it is)")
        out += "LENA, they owe the user: " + (if (lena.isEmpty()) "nobody." else lena.joinToString(", ") { it.name + " " + plain(it.bal) } + ". Total lena " + plain(lena.sumOf { it.bal }) + ".")
        out += "DENA, the user owes them: " + (if (dena.isEmpty()) "nobody." else dena.joinToString(", ") { it.name + " " + plain(-it.bal) } + ". Total dena " + plain(-dena.sumOf { it.bal }) + ".")
        for (p in (lena + dena).take(25)) out += "- " + p.name + ": \"" + sentence(p.name, p.bal) + "\" (" +
            p.byKind.entries.joinToString(", ") { it.key + " " + plain(it.value) } + "; last " + p.last + ")"
        if (lena.size + dena.size > 25) out += "+" + (lena.size + dena.size - 25) + " more"
        if (even.isNotEmpty()) out += "Hisaab barabar: " + even.joinToString(", ") { it.name } + "."
        return out.joinToString("\n")
    }

    /** Everything the AI is handed besides its instructions: calendar, totals, balances, names, and the records that matter now. */
    fun forAi(now: Long, said: String, lastChange: String, recent: Int = 20): String {
        val today = Dates.day(now)
        val kal = Dates.plusDays(now, -1)
        val sb = StringBuilder()
        sb.append(Dates.block(now)).append('\n')
        sb.append(summary(now)).append("\n\n")
        val people = knownPeople().take(60)
        if (people.isNotEmpty()) sb.append("PEOPLE (use these spellings): ").append(people.joinToString(", ")).append('\n')
        val items = recs.map { it.item.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(80)
        if (items.isNotEmpty()) sb.append("ITEMS (use these spellings): ").append(items.joinToString(", ") { it.key }).append('\n')
        sb.append("LAST CHANGE: ").append(lastChange.ifEmpty { "none" }).append("\n\n")
        val shown = mutableSetOf<Int>()
        fun block(title: String, rs: List<Rec>) {
            if (rs.isEmpty()) return
            sb.append(title).append('\n'); rs.forEach { sb.append(it.line()).append('\n'); shown += it.id }; sb.append('\n')
        }
        block("AAJ RECORDS ($today)", recs.filter { it.date == today }.take(40))
        block("KAL RECORDS ($kal)", recs.filter { it.date == kal }.take(40))
        block("LAST WRITTEN (newest first)", recs.sortedByDescending { it.ts }.filter { it.id !in shown }.take(recent))
        block("OLDER RECORDS THAT MAY BE ABOUT THIS", relevantRecs(said).filter { it.id !in shown }.take(30))
        if (recs.isEmpty()) sb.append("MEMORY BOOK: nothing written yet.\n")
        return sb.toString().trim()
    }

    /** Records whose words match what was said (a name, an item, a word of a note), in Roman or Urdu script. */
    private class Hay(val text: String, val tokens: List<String>, val skels: Set<String>, val personKey: String)
    private val hays = java.util.WeakHashMap<Rec, Hay>()

    private fun hayOf(r: Rec): Hay = hays.getOrPut(r) {
        val text = Words.roman(r.item + " " + r.person + " " + r.text) + " " + Words.norm(r.item + " " + r.text)
        val tokens = text.split(' ').filter { it.length >= 3 }
        Hay(text, tokens, tokens.map { skel(it) }.filter { it.length >= 3 }.toSet(), if (r.person.isBlank()) "" else People.key(r.person))
    }

    fun relevantRecs(said: String): List<Rec> {
        // question and filler words go first, in both scripts, so "کہاں" (kahan) does not match every "khana"
        val raw = Words.norm(said).split(NONWORD).filter { it.isNotBlank() && it !in COMMON }
        val words = raw.flatMap { listOf(it, Words.roman(it)) }.filter { it.length >= 3 && it !in COMMON }.toSet()
        if (words.isEmpty()) return emptyList()
        val probes = words.map { w -> Triple(w, People.key(w), skel(w)) }
        val hits = probes.associate { it.first to mutableListOf<Rec>() }
        for (r in recs) {                                     // newest first: each word keeps its newest matches
            val h = hayOf(r)
            for ((w, k, sk) in probes) {
                val list = hits.getValue(w)
                if (list.size >= 30) continue
                if (h.text.contains(w) || k.length >= 3 && h.personKey == k || sk.length >= 3 && sk in h.skels) list += r
            }
        }
        val out = linkedSetOf<Rec>()
        // a word found in very many records says little: it keeps only its newest few
        for ((w, list) in hits) out += if (list.size >= 30 && probes.first { it.first == w }.second.let { k -> list.none { it.person.isNotBlank() && People.key(it.person) == k } }) list.take(8) else list
        return out.sortedByDescending { it.ts }
    }

    /** For the old callers: older matching records as lines. */
    fun relevant(said: String, skip: Int = 150, max: Int = 40): String {
        if (recs.size <= skip) return ""
        val old = recs.drop(skip).map { it.id }.toSet()
        return relevantRecs(said).filter { it.id in old }.take(max).joinToString("\n") { it.line() }
    }

    /** The newest records. */
    fun recent(max: Int = 150): String = if (recs.isEmpty()) "(nothing written yet)" else recs.take(max).joinToString("\n") { it.line() }

    companion object {
        /** Money that moved. */
        val CASH = setOf("expense", "income", "gave", "took", "got_back", "paid_back", "sale", "purchase")
        /** Debts with no money moving now. */
        val DEBT = setOf("owes_me", "i_owe")
        val MONEY = CASH + DEBT
        val LOANS = setOf("gave", "took", "got_back", "paid_back", "owes_me", "i_owe")
        val KINDS = MONEY + setOf("note", "task", "settle")

        /** "X ko diye" etc. for the receipt, and the name of each kind in the book. */
        val PERSON_WORDS = mapOf(
            "gave" to ("X ko udhaar diye" to "Gave (udhaar)"), "took" to ("X se udhaar liye" to "Borrowed"),
            "got_back" to ("X ne wapis diye" to "Got back"), "paid_back" to ("X ko wapis diye" to "Paid back"),
            "owes_me" to ("X se lene hain" to "To receive"), "i_owe" to ("X ko dene hain" to "To pay"))

        private val ALIASES = mapOf(
            "lent" to "gave", "lend" to "gave", "loan" to "gave", "loan_given" to "gave", "give" to "gave", "given" to "gave", "udhaar_diya" to "gave", "qarz_diya" to "gave",
            "borrowed" to "took", "borrow" to "took", "loan_taken" to "took", "take" to "took", "udhaar_liya" to "took", "qarz_liya" to "took",
            "received_back" to "got_back", "returned_to_me" to "got_back", "got_returned" to "got_back", "repayment_received" to "got_back", "gotback" to "got_back",
            "repaid" to "paid_back", "returned" to "paid_back", "repay" to "paid_back", "paidback" to "paid_back", "repayment" to "paid_back",
            "receivable" to "owes_me", "owed_to_me" to "owes_me", "lena" to "owes_me", "lene" to "owes_me", "owesme" to "owes_me", "they_owe" to "owes_me",
            "payable" to "i_owe", "debt" to "i_owe", "owe" to "i_owe", "dena" to "i_owe", "dene" to "i_owe", "iowe" to "i_owe", "i_owe_them" to "i_owe",
            "kharcha" to "expense", "kharch" to "expense", "spent" to "expense", "spend" to "expense", "payment" to "expense", "bill" to "expense", "cost" to "expense",
            "earning" to "income", "earned" to "income", "salary" to "income", "kamai" to "income", "aamdani" to "income",
            "sold" to "sale", "sell" to "sale", "bought" to "purchase", "buy" to "purchase",
            "reminder" to "task", "todo" to "task", "to_do" to "task", "kaam" to "task",
            "memo" to "note", "fact" to "note", "memory" to "note",
            "clear" to "settle", "settled" to "settle", "hisaab_clear" to "settle", "close" to "settle")

        /** The kind the AI meant, or null when it is not one the book knows. */
        fun kindOf(k: String): String? {
            val t = k.trim().lowercase(Locale.ROOT).replace(' ', '_').replace('-', '_')
            if (t.isEmpty() || t == "null") return null
            return if (t in KINDS) t else ALIASES[t]
        }

        fun day(ms: Long): String = Dates.day(ms)

        /** For old callers: a real yyyy-MM-dd, or today. */
        fun dateOf(d: String, today: String) = Dates.loose(d, SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(today)!!.time + 43_200_000L) ?: today

        /** For old callers: a reminder time as yyyy-MM-dd HH:mm, or "". */
        fun atOf(a: String): String = Dates.at(a, System.currentTimeMillis())

        private val COMMON = setOf("aur", "kya", "kitna", "kitne", "kitni", "hai", "hain", "tha", "the", "thi", "mein", "main", "mera", "meri",
            "mere", "kal", "aaj", "liye", "liya", "diya", "diye", "kiya", "karo", "kar", "batao", "bata", "wala", "wali", "wale", "rupay", "rupee", "rupees",
            "and", "what", "how", "much", "yaad", "abhi", "pehle", "baad", "hua", "huwa", "gaya", "gaye", "total", "kharcha", "hisaab", "lene", "dene",
            "udhaar", "wapis", "kitnay", "mujhe", "kis", "kon", "kaun", "sab", "saare", "kul", "ko", "se", "ne", "ka", "ke", "ki", "hay", "hy",
            "کیا", "کتنا", "کتنے", "کتنی", "ہے", "ہیں", "تھا", "تھے", "تھی", "میں", "میرا", "میری", "میرے", "اور", "لیے", "دیے", "دیا", "لیا", "کیا",
            "بتاؤ", "بتائیں", "والا", "والے", "روپے", "حساب", "ادھار", "واپس", "لینے", "دینے", "مجھے", "کس", "کون", "سب", "کو", "سے", "نے", "کا", "کے", "کی",
            "کہاں", "کدھر", "کب", "کیسے", "رکھے", "رکھا", "رکھی", "پر", "ہوا", "پیسے", "تھا", "kahan", "kidhar", "kab", "kaise", "rakhe", "rakha", "rakhi",
            "par", "pe", "paise", "rupaye", "hain", "kuch", "koi")

        private val NONWORD = Regex("[^\\p{L}\\p{N}]+")
        private val DOUBLE = Regex("(.)\\1+")
        private fun skel(w: String) = DOUBLE.replace(Words.roman(w), "$1").filter { it !in "aeiouy" }
        private val GIFT = Regex("(?i)tohfa|tohfe|gift|eidi|salami|shagun|تحفہ|تحفے|عیدی|سلامی|شگن")

        /** Words that only say a payment was made, with no new thing bought. */
        private val PAYMENT_WORDS = setOf("udhaar", "udhar", "qarz", "hisaab", "hisab", "paise", "payment", "adaigi", "baqi", "baqaya")

        /** 1,500 for people. */
        fun money(v: Double): String {
            val whole = kotlin.math.abs(v - Math.rint(v)) < 0.005
            return if (whole) String.format(Locale.US, "%,d", Math.rint(v).toLong()) else String.format(Locale.US, "%,.2f", v)
        }

        /** 1500 for the AI: no commas, so it copies the number as a number. */
        fun plain(v: Double): String {
            val whole = kotlin.math.abs(v - Math.rint(v)) < 0.005
            return if (whole) Math.rint(v).toLong().toString() else String.format(Locale.US, "%.2f", v)
        }

        /** How a balance is said: "Ali se 800 lene hain." / "Bilal ko 1,000 dene hain." / "Usman ka hisaab barabar hai." */
        fun sentence(name: String, bal: Double): String = when {
            bal > 0.005 -> People.oblique(name) + " se " + money(bal) + " lene hain."
            bal < -0.005 -> People.oblique(name) + " ko " + money(-bal) + " dene hain."
            else -> "$name ka hisaab barabar hai."
        }

        fun sentenceUrdu(name: String, bal: Double): String = when {
            bal > 0.005 -> "$name سے " + money(bal) + " لینے ہیں۔"
            bal < -0.005 -> "$name کو " + money(-bal) + " دینے ہیں۔"
            else -> "$name کا حساب برابر ہے۔"
        }

        private val books = mutableMapOf<String, Memory>()

        /** One book per file for the whole app, so two screens never write over each other. */
        @Synchronized fun open(file: File): Memory = books.getOrPut(file.absolutePath) { Memory(file) }
    }
}
