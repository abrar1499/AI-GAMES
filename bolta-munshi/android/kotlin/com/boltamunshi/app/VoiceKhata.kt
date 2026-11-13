package com.boltamunshi.app

import com.bolkhata.app.BankTransactionDeduplicator
import com.bolkhata.app.Cat
import com.bolkhata.app.ChatMessage
import com.bolkhata.app.Commands
import com.bolkhata.app.EditPlan
import com.bolkhata.app.Edits
import com.bolkhata.app.Entry
import com.bolkhata.app.Goods
import com.bolkhata.app.NativeChatEngine
import com.bolkhata.app.Parser
import com.bolkhata.app.Query
import com.bolkhata.app.Questions
import com.bolkhata.app.Recat
import com.bolkhata.app.Split
import com.bolkhata.app.Translit
import com.bolkhata.app.Type
import com.bolkhata.app.Understand
import com.bolkhata.app.balances
import com.bolkhata.app.cashIn
import com.bolkhata.app.cashOut
import com.bolkhata.app.cleanPersonName
import com.bolkhata.app.fmt
import com.bolkhata.app.isTrade
import com.bolkhata.app.madeEntry
import com.bolkhata.app.madeRate
import com.bolkhata.app.matchEntries
import com.bolkhata.app.matchEntriesByPeriod
import com.bolkhata.app.personAccounts
import com.bolkhata.app.profitBreakdown
import com.bolkhata.app.stock
import java.util.Calendar
import java.util.Locale

/**
 * BolKhata's khata, run by voice.
 *
 * Every sentence goes through BolKhata's own logic in the same order the BolKhata
 * chat uses it: its engine reads the sentence, then new entries, questions,
 * changes, deletions and payments are carried out on a BolKhata ledger. Where
 * BolKhata shows a card or a button, this asks out loud ("haan ya nahi?") and
 * waits for the answer. Every reply is spoken in Urdu.
 *
 * [reply] returns null for a sentence that is not about the khata at all, so the
 * rest of Bolta Munshi (chat, notes, sums) can answer it.
 */
class VoiceKhata(private val book: Book) {

    /** Where the ledger is kept: BolKhata's own Store on the phone, a plain list in tests. */
    interface Book {
        var entries: List<Entry>
        var trash: List<Entry>
        val currency: String
        val wordFixes: Map<String, String>
    }

    /** What to say, in Roman Urdu and Urdu script, and whether the ledger changed. */
    class Reply(val ro: String, val ur: String, val changed: Boolean = false)

    private sealed class Waiting {
        class Delete(val ids: Set<String>) : Waiting()
        class Amount(val text: String) : Waiting()
        class Names(val plan: Split.Plan) : Waiting()
        class Who(val result: Understand.Result) : Waiting()
    }

    private var waiting: Waiting? = null
    private var waitingSince = 0L
    private var lastAdded: List<String> = emptyList()
    private var lastTrashed: List<String> = emptyList()
    private val history = mutableListOf<ChatMessage>()

    // ------------------------------------------------------------- entry point ---

    fun reply(said: String, now: Long = System.currentTimeMillis()): Reply? {
        val text = said.trim()
        if (text.isEmpty()) return null
        val r = try { answer(text, now) } catch (e: Exception) { null }
        history += ChatMessage(now.toString(), true, text, now)
        if (r != null) history += ChatMessage("$now-r", false, r.ro, now)
        while (history.size > 12) history.removeAt(0)
        return r
    }

    private fun answer(text: String, now: Long): Reply? {
        val roman = runCatching { Translit.toRoman(text) }.getOrDefault(text)
        val lower = roman.lowercase(Locale.ROOT)
        val words = lower.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }

        // An answer to a question asked a moment ago.
        waiting?.takeIf { now - waitingSince < 120_000 }?.let { w ->
            waiting = null
            answerWaiting(w, roman, words, now)?.let { return it }
        }
        waiting = null

        // "wapas lao" / "undo": take back what was just added or deleted
        if (words.size <= 4 && (words.any { it in UNDO } || (words.contains("wapas") && words.any { it in setOf("lao", "laao", "kar", "karo", "do") }))) return undo()

        val asking = roman.trim().endsWith("?") || words.any { it in QUESTION_WORDS }
        // "eent ke pese abhi nahi diye": a sale or purchase paid or on credit (only when one fits)
        if (!asking) payStatus(roman)?.let { return it }

        if (asking) overview(words, now)?.let { return it }

        val fixes = DEFAULT_FIXES + book.wordFixes
        val fixedWords = words.map { fixes[it] ?: it }
        val engineText = if (fixedWords == words) roman else fixedWords.joinToString(" ")
        val fixed = runCatching {
            NativeChatEngine.interpret(engineText, book.entries, names(), fixes, history, book.currency, now)
        }.getOrNull() ?: return null
        // Without an AI the engine sometimes takes a plain entry ("aaj 500 ka petrol dala") for a question;
        // a statement with an amount that BolKhata's parser reads as an entry is an entry.
        if (!asking && fixed.kind == "ask" && roman.any { it.isDigit() } && Parser.parse(roman, now).entries.isNotEmpty()) {
            return reader(roman, roman, now)
        }
        if (fixed.unsure) {
            // the reader path still knows commands and shop words the chat engine does not
            if (words.any { it in DELETE_WORDS } || fixedWords.contains("delete")) Commands.read(engineText)?.let { c -> command(c)?.let { return it } }
            return null
        }
        return when (fixed.kind) {
            "entry", "entry_after" -> {
                if (fixed.text.none { it.isDigit() }) return askAmount(fixed.text.ifBlank { roman }, now)
                reader(fixed.text, roman, now)
            }
            "ask" -> ask(fixed, now)
            "delete" -> delete(fixed.filter, now)
            "clear_all" -> confirmDelete(book.entries, now)
            "change" -> change(fixed)
            "undo" -> undo()
            "open" -> open(fixed, now)
            "plan" -> fixed.plan?.let { applyPlan(it) } ?: say("Kaun si entry, aur kya badalna hai?", "کون سی انٹری، اور کیا بدلنا ہے؟")
            "paid_by" -> null
            "chat" -> null
            else -> say(
                "Yeh kaam abhi BolKhata app ki screen se kijiye.",
                "یہ کام ابھی بول کھاتہ ایپ کی اسکرین سے کیجیے۔"
            )
        }
    }

    // ----------------------------------------------------------- waiting for ---

    private fun answerWaiting(w: Waiting, roman: String, words: List<String>, now: Long): Reply? = when (w) {
        is Waiting.Delete -> when {
            words.any { it in YES } -> {
                val gone = book.entries.filter { it.id in w.ids }
                book.entries = book.entries.filter { it.id !in w.ids }
                book.trash = gone + book.trash.filter { it.id !in w.ids }
                lastTrashed = gone.map { it.id }
                say("Mita di. Ghalti se mit gayi ho to kahiye: wapas lao.", "مٹا دی۔ غلطی سے مٹ گئی ہو تو کہیے: واپس لاؤ۔", true)
            }
            words.any { it in NO } -> say("Theek hai, kuch nahi mitaya.", "ٹھیک ہے، کچھ نہیں مٹایا۔")
            else -> say("Pehle bata dein: mita doon, haan ya nahi?", "پہلے بتا دیں: مٹا دوں، ہاں یا نہیں؟").also { waitFor(w, now) }
        }
        is Waiting.Amount -> {
            val n = Regex("""\d[\d,]*(?:\.\d+)?""").find(roman)?.value
            when {
                // only the amount ("500", "500 rupay the"): anything more is a new sentence
                n != null && words.all { it.any(Char::isDigit) || it in AMOUNT_ONLY } -> newEntries(w.text + " " + n.replace(",", ""), w.text, now)
                words.size <= 2 && words.any { it in NO } -> say("Theek hai, chhor diya.", "ٹھیک ہے، چھوڑ دیا۔")
                else -> null                                     // something new was said: answer that instead
            }
        }
        is Waiting.Names -> {
            val found = Split.namesIn(roman, names())
            if (found.isEmpty()) null
            else {
                val plan = w.plan.with(found)
                if (plan.missing > 0) { waitFor(Waiting.Names(plan), now); say("Aur kaun tha?", "اور کون تھا؟") }
                else split(plan)
            }
        }
        is Waiting.Who -> {
            val pick = w.result.choices.firstOrNull { c -> words.any { it.length >= 3 && c.lowercase().contains(it) } }
            if (pick == null) null
            else {
                val fixedText = w.result.nameWord?.let { w.result.text.replace(it, pick, ignoreCase = true) } ?: w.result.text
                newEntries(fixedText, fixedText, now)
            }
        }
    }

    private fun waitFor(w: Waiting, now: Long) { waiting = w; waitingSince = now }

    private fun askAmount(text: String, now: Long): Reply {
        waitFor(Waiting.Amount(text), now)
        return say("Kitne rupay?", "کتنے روپے؟")
    }

    // ------------------------------------------------------------ new entries ---

    /** BolKhata's reader path: smart understanding, then commands, shared bills, questions, changes, and new entries. */
    private fun reader(text: String, original: String, now: Long): Reply = newEntries(text, original, now)

    private fun newEntries(text: String, original: String, now: Long): Reply {
        val u = Understand.read(text, names(), book.wordFixes)
        if (u.choices.size > 1) {
            waitFor(Waiting.Who(u), now)
            val list = u.choices.take(3).joinToString(" ya ")
            return say("Kaun sa: $list?", "کون سا: ${u.choices.take(3).joinToString(" یا ")}؟")
        }
        when (u.kind) {
            Understand.Kind.UNDO -> return undo()
            Understand.Kind.DELETE_LAST -> {
                val last = book.entries.maxByOrNull { it.createdAt } ?: return say("Abhi koi entry nahi.", "ابھی کوئی انٹری نہیں۔")
                return confirmDelete(listOf(last), now)
            }
            Understand.Kind.OPEN_KHATA -> u.contact?.let { return personLine(it, true) }
            Understand.Kind.SUMMARY -> return monthSummary(now)
            Understand.Kind.ENTRY -> Unit
        }
        val lower = u.text.lowercase(Locale.ROOT)
        val words = lower.split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
        // "120000 awal eint tayyar hui": goods made go into stock.
        Goods.readMade(u.text)?.let { (qty, name) -> return made(qty, name, now) }
        // Paid or not paid for a sale or purchase; never on a question.
        val asking = u.text.trim().endsWith("?") || words.any { it in QUESTION_WORDS }
        if (!asking) payStatus(u.text)?.let { return it }
        // BolKhata's own commands: delete, search, clear all.
        Commands.read(u.text)?.let { c -> command(c)?.let { return it } }
        Split.detect(u.text, names())?.let { sp ->
            val p = if (u.clock == null) sp else Split.Plan(Understand.at(sp.bill, u.clock), sp.payer, sp.names, sp.shares, sp.people)
            if (p.missing != 0) {
                waitFor(Waiting.Names(p), now)
                return say("Aap ke sath aur kaun kaun tha?", "آپ کے ساتھ اور کون کون تھا؟")
            }
            return split(p)
        }
        Edits.plan(u.text, book.entries, now)?.let { return applyPlan(it) }
        if (Edits.looksLikeEdit(u.text) && book.entries.isNotEmpty()) {
            return say("Kaun si entry? Jaise: aakhri entry 400 kar do.", "کون سی انٹری؟ جیسے: آخری انٹری 400 کر دو۔")
        }
        val parsed = Parser.parse(u.text, now).let { p ->
            if (u.clock == null) p else Parser.Result(p.entries.map { Understand.at(it, u.clock) }, p.unclear.map { Understand.at(it, u.clock) })
        }
        val found = parsed.entries.map { Recat.fromHistory(it, book.entries) }
        if (found.isEmpty()) {
            if (parsed.unclear.isNotEmpty() && parsed.unclear.all { it.qty > 0 }) {
                return say("Is maal ka rate kya hai? Jaise: 500 eent 20 rupay wali.", "اس مال کا ریٹ کیا ہے؟ جیسے: 500 اینٹ 20 روپے والی۔")
            }
            return askAmount(original, now)
        }
        val fresh = BankTransactionDeduplicator.filterNew(found, book.entries).fresh
        if (fresh.isEmpty()) return say("Yeh pehle se likha hua hai.", "یہ پہلے سے لکھا ہوا ہے۔")
        book.entries = fresh + book.entries
        lastAdded = fresh.map { it.id }
        val lines = fresh.map { savedLine(it, now) }
        val people = fresh.mapNotNull { e -> e.person.takeIf { it.isNotBlank() } }.distinctBy { it.lowercase() }
        val tail = people.take(2).map { balanceLine(it) }
        return join(lines + tail, changed = true)
    }

    private fun split(p: Split.Plan): Reply {
        val list = Split.entries(p)
        if (list.isEmpty()) return say("Kitne rupay ka bill tha?", "کتنے روپے کا بل تھا؟")
        book.entries = list + book.entries
        lastAdded = list.map { it.id }
        val mine = list.filter { it.type == Type.KHARCHA }.sumOf { it.amount }
        val others = list.filter { it.type == Type.DIYA }
        val owe = list.firstOrNull { it.type == Type.LIYA }
        var ro = "Aap ka hissa ${fmt(mine)} rupay likh liya."
        var ur = "آپ کا حصہ ${n(mine)} روپے لکھ لیا۔"
        if (others.isNotEmpty()) {
            ro += " " + others.joinToString(", ") { it.person + " " + fmt(it.amount) } + " aap ko dein ge."
            ur += " " + others.joinToString("، ") { it.person + " " + n(it.amount) } + " آپ کو دیں گے۔"
        }
        if (owe != null) {
            ro += " Aap ne ${owe.person} ko ${fmt(owe.amount)} dene hain."
            ur += " آپ نے ${owe.person} کو ${n(owe.amount)} دینے ہیں۔"
        }
        return Reply(ro, ur, true)
    }

    private fun made(qty: Double, said: String, now: Long): Reply {
        val item = Goods.canonical(said, Goods.known(book.entries)).ifBlank { "Goods" }
        val e = madeEntry(item, qty, madeRate(book.entries, qty, now), now)
        book.entries = listOf(e) + book.entries
        lastAdded = listOf(e.id)
        return Reply("${fmt(qty)} $item tayyar, stock mein likh di.", "${n(qty)} $item تیار، اسٹاک میں لکھ دی۔", true)
    }

    // --------------------------------------------------------------- questions ---

    private fun ask(f: NativeChatEngine.Fixed, now: Long): Reply {
        var q = f.filter
        if (f.what == "balance" && q.persons.size == 1) return personLine(q.persons.first(), false)
        var picked = Query.select(book.entries, q)
        // "aaj achha kitna hua": a word nothing in the khata was ever called is most often a misheard
        // "kharcha" (or a stray "bata"), so the question was about the whole period
        if (picked.isEmpty() && q.items.isNotEmpty() && q.persons.isEmpty() && q.cats.isEmpty() && q.ids.isEmpty()) {
            val everSaid = q.items.any { w -> book.entries.any { e -> (e.item + " " + e.note + " " + e.person).contains(w, ignoreCase = true) } }
            if (!everSaid) {
                q = Query.Filter(from = q.from, to = q.to, types = q.types, min = q.min, max = q.max, last = q.last, noPeople = q.noPeople,
                    notItems = q.notItems, createdFrom = q.createdFrom, createdTo = q.createdTo, notCats = q.notCats)
                picked = Query.select(book.entries, q)
            }
        }
        val label = labelOf(q, now)
        if (picked.isEmpty()) return say("${label.first}: kuch nahi mila.", "${label.second}: کچھ نہیں ملا۔")
        return when (f.what) {
            "count" -> say("${label.first}: ${picked.size} entries.", "${label.second}: ${picked.size} انٹریاں۔")
            "list" -> listOf(picked, label)
            "highest" -> {
                val top = picked.filter { it.type == Type.KHARCHA }.sortedByDescending { it.amount }.take(3)
                if (top.isEmpty()) say("${label.first}: koi kharcha nahi.", "${label.second}: کوئی خرچہ نہیں۔")
                else Reply(
                    "${label.first} sab se bare kharche: " + top.joinToString(", ") { what(it).first + " " + fmt(it.amount) } + ".",
                    "${label.second} سب سے بڑے خرچے: " + top.joinToString("، ") { what(it).second + " " + n(it.amount) } + "۔"
                )
            }
            else -> totals(picked, label)
        }
    }

    /** Profit, stock, savings and money owed, worked out by BolKhata's own functions. */
    private fun overview(words: List<String>, now: Long): Reply? {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val period: Pair<String, String>
        val from: Long
        when {
            words.any { it in setOf("aaj", "aj", "today") } -> { c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); from = c.timeInMillis; period = "Aaj" to "آج" }
            words.any { it in setOf("saal", "year") } -> { c.set(Calendar.DAY_OF_YEAR, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); from = c.timeInMillis; period = "Is saal" to "اس سال" }
            words.any { it in setOf("total", "kul", "abtak", "sab") } && !words.any { it in setOf("mahine", "mahina") } -> { from = 0L; period = "Ab tak" to "اب تک" }
            else -> { c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); from = c.timeInMillis; period = "Is mahine" to "اس مہینے" }
        }
        val inPeriod = book.entries.filter { it.date in from..now }
        if (words.any { it in setOf("munafa", "munafaa", "nafa", "profit", "faida", "fayda", "nuqsan", "nuksan", "loss") }) {
            val p = profitBreakdown(book.entries, inPeriod)
            val v = p.netProfit
            return if (v >= 0) Reply("${period.first} andaazan munafa ${fmt(v)} rupay.", "${period.second} اندازاً منافع ${n(v)} روپے۔")
            else Reply("${period.first} andaazan nuqsan ${fmt(-v)} rupay.", "${period.second} اندازاً نقصان ${n(-v)} روپے۔")
        }
        if (words.any { it in setOf("stock", "istak", "bacha", "bachi", "bache") } && book.entries.any { it.qty > 0 }) {
            val lines = stock(book.entries).filter { it.qty > 0.005 }
            if (lines.isEmpty()) return say("Stock khali hai.", "اسٹاک خالی ہے۔")
            return Reply("Stock mein: " + lines.take(5).joinToString(", ") { fmt(it.qty) + " " + it.name } + ".",
                "اسٹاک میں: " + lines.take(5).joinToString("، ") { n(it.qty) + " " + it.name } + "۔")
        }
        if (words.any { it in setOf("bachat", "bacha", "saving", "savings") }) {
            val inn = inPeriod.filter { cashIn(it) }.sumOf { it.amount }
            val out = inPeriod.filter { cashOut(it) }.sumOf { it.amount }
            return Reply("${period.first} aaye ${fmt(inn)}, gaye ${fmt(out)}, bachat ${fmt(inn - out)} rupay.", "${period.second} آئے ${n(inn)}، گئے ${n(out)}، بچت ${n(inn - out)} روپے۔")
        }
        val loanTalk = words.any { it in setOf("udhar", "udhaar", "qarz", "qarza", "karza", "baqaya", "bakaya") } && words.none { w -> book.entries.any { it.person.equals(w, true) } }
        if (loanTalk) {
            val bal = balances(book.entries)
            val get = bal.values.filter { it > 0 }.sum()
            val pay = -bal.values.filter { it < 0 }.sum()
            if (get < 0.005 && pay < 0.005) return say("Kisi ka koi udhar nahi.", "کسی کا کوئی ادھار نہیں۔")
            return Reply("Logon se lene hain ${fmt(get)}, aap ne dene hain ${fmt(pay)} rupay.", "لوگوں سے لینے ہیں ${n(get)}، آپ نے دینے ہیں ${n(pay)} روپے۔")
        }
        return null
    }

    private fun totals(list: List<Entry>, label: Pair<String, String>): Reply {
        val out = list.filter { cashOut(it) }.sumOf { it.amount }
        val inn = list.filter { cashIn(it) }.sumOf { it.amount }
        val onlyOne = list.map { it.type }.toSet().size == 1
        if (onlyOne || inn < 0.005 || out < 0.005) {
            val sum = list.sumOf { it.amount }
            val top = list.groupBy { what(it) }.mapValues { e -> e.value.sumOf { it.amount } }.entries.sortedByDescending { it.value }
            val parts = if (top.size > 1) top.take(3) else emptyList()
            return Reply(
                "${label.first} total ${fmt(sum)} rupay." + (if (parts.isEmpty()) "" else " " + parts.joinToString(", ") { it.key.first + " " + fmt(it.value) } + "."),
                "${label.second} ٹوٹل ${n(sum)} روپے۔" + (if (parts.isEmpty()) "" else " " + parts.joinToString("، ") { it.key.second + " " + n(it.value) } + "۔")
            )
        }
        return Reply(
            "${label.first}: aaye ${fmt(inn)}, gaye ${fmt(out)} rupay.",
            "${label.second}: آئے ${n(inn)}، گئے ${n(out)} روپے۔"
        )
    }

    private fun listOf(list: List<Entry>, label: Pair<String, String>): Reply {
        val shown = list.take(5)
        val more = if (list.size > 5) " Aur ${list.size - 5} entries." else ""
        val moreUr = if (list.size > 5) " اور ${list.size - 5} انٹریاں۔" else ""
        return Reply(
            "${label.first}: " + shown.joinToString(", ") { what(it).first + " " + fmt(it.amount) } + "." + more + " Total ${fmt(list.sumOf { it.amount })}.",
            "${label.second}: " + shown.joinToString("، ") { what(it).second + " " + n(it.amount) } + "۔" + moreUr + " ٹوٹل ${n(list.sumOf { it.amount })}۔"
        )
    }

    private fun open(f: NativeChatEngine.Fixed, now: Long): Reply {
        f.filter.persons.firstOrNull()?.let { return personLine(it, true) }
        return ask(NativeChatEngine.Fixed(f.text, "ask", "", "list", f.filter), now)
    }

    /** A person's khata: what stands between you, and the last thing that happened. */
    private fun personLine(name: String, detail: Boolean): Reply {
        val acct = personAccounts(book.entries).firstOrNull { it.name.equals(name, true) }
            ?: return say("$name ka koi hisaab nahi likha.", "$name کا کوئی حساب نہیں لکھا۔")
        val b = balanceLine(acct.name)
        if (!detail || acct.history.isEmpty()) return b
        val last = acct.history.last().first
        return Reply(b.ro + " Aakhri: " + what(last).first + " " + fmt(last.amount) + ".", b.ur + " آخری: " + what(last).second + " " + n(last.amount) + "۔")
    }

    private fun balanceLine(name: String): Reply {
        val bal = balances(book.entries).entries.firstOrNull { it.key.equals(name, true) }?.value ?: 0.0
        return when {
            bal > 0.005 -> Reply("$name ne aap ko ${fmt(bal)} rupay dene hain.", "$name نے آپ کو ${n(bal)} روپے دینے ہیں۔")
            bal < -0.005 -> Reply("Aap ne $name ko ${fmt(-bal)} rupay dene hain.", "آپ نے $name کو ${n(-bal)} روپے دینے ہیں۔")
            else -> Reply("$name ke sath hisaab barabar hai.", "$name کے ساتھ حساب برابر ہے۔")
        }
    }

    private fun monthSummary(now: Long): Reply {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val list = book.entries.filter { it.date >= c.timeInMillis && it.date <= now }
        if (list.isEmpty()) return say("Is mahine abhi kuch nahi likha.", "اس مہینے ابھی کچھ نہیں لکھا۔")
        return totals(list, "Is mahine" to "اس مہینے")
    }

    // ------------------------------------------------- changes and deletions ---

    private fun delete(q: Query.Filter, now: Long): Reply {
        if (q.isEmpty) return say("Kaun si entries? Jaise: aaj ki entries mita do.", "کون سی انٹریاں؟ جیسے: آج کی انٹریاں مٹا دو۔")
        val hits = Query.select(book.entries, q)
        if (hits.isEmpty()) return say("Aisi koi entry nahi mili.", "ایسی کوئی انٹری نہیں ملی۔")
        return confirmDelete(hits, now)
    }

    private fun confirmDelete(hits: List<Entry>, now: Long): Reply {
        if (hits.isEmpty()) return say("Mitane ke liye kuch nahi.", "مٹانے کے لیے کچھ نہیں۔")
        waitFor(Waiting.Delete(hits.map { it.id }.toSet()), now)
        if (hits.size == 1) {
            val e = hits[0]
            return Reply("${what(e).first} ${fmt(e.amount)} rupay wali entry mita doon? Haan ya nahi?", "${what(e).second} ${n(e.amount)} روپے والی انٹری مٹا دوں؟ ہاں یا نہیں؟")
        }
        return Reply("${hits.size} entries mita doon? Haan ya nahi?", "${hits.size} انٹریاں مٹا دوں؟ ہاں یا نہیں؟")
    }

    private fun change(f: NativeChatEngine.Fixed): Reply {
        val q = f.filter
        if (q.isEmpty || f.set.isEmpty()) return say("Kaun si entry, aur kya badalna hai? Jaise: petrol wali entry 400 kar do.", "کون سی انٹری، اور کیا بدلنا ہے؟ جیسے: پیٹرول والی انٹری 400 کر دو۔")
        val hits = Query.select(book.entries, q)
        if (hits.isEmpty()) return say("Aisi koi entry nahi mili.", "ایسی کوئی انٹری نہیں ملی۔")
        if (hits.size > 1 && q.ids.isEmpty()) return say("${hits.size} entries milti hain. Tareekh ya raqam bata dein.", "${hits.size} انٹریاں ملتی ہیں۔ تاریخ یا رقم بتا دیں۔")
        val changed = hits.map { it to Query.change(it, f.set) }.filter { (a, b) -> a != b }
        if (changed.isEmpty()) return say("Yeh pehle se aisa hi hai.", "یہ پہلے سے ایسا ہی ہے۔")
        return applyPlan(EditPlan(changed, emptyList(), ""))
    }

    private fun applyPlan(plan: EditPlan): Reply {
        val map = plan.changed.associate { it.first.id to it.second }
        val gone = plan.deleted.map { it.id }.toSet()
        book.entries = book.entries.filter { it.id !in gone }.map { map[it.id] ?: it }
        if (gone.isNotEmpty()) book.trash = plan.deleted + book.trash.filter { it.id !in gone }
        val first = plan.changed.firstOrNull()
        if (first == null) return say("Mita di.", "مٹا دی۔", true)
        val (a, b) = first
        val more = plan.changed.size - 1
        return Reply(
            "Theek kar diya: ${what(a).first} ${fmt(a.amount)} ki jagah ${what(b).first} ${fmt(b.amount)}." + (if (more > 0) " Aur $more." else ""),
            "ٹھیک کر دیا: ${what(a).second} ${n(a.amount)} کی جگہ ${what(b).second} ${n(b.amount)}۔" + (if (more > 0) " اور $more۔" else ""),
            true
        )
    }

    private fun undo(): Reply {
        val added = lastAdded.filter { id -> book.entries.any { it.id == id } }
        if (added.isNotEmpty()) {
            val gone = book.entries.filter { it.id in added }
            book.entries = book.entries.filter { it.id !in added }
            book.trash = gone + book.trash
            lastAdded = emptyList()
            return say("Abhi wali entry hata di.", "ابھی والی انٹری ہٹا دی۔", true)
        }
        val back = book.trash.filter { it.id in lastTrashed }
        if (back.isNotEmpty()) {
            book.entries = back + book.entries
            book.trash = book.trash.filter { it.id !in lastTrashed }
            lastTrashed = emptyList()
            return say("Mitayi hui entries wapas aa gayin.", "مٹائی ہوئی انٹریاں واپس آ گئیں۔", true)
        }
        return say("Wapas karne ke liye kuch nahi.", "واپس کرنے کے لیے کچھ نہیں۔")
    }

    private fun command(c: Commands.Command): Reply? {
        val now = System.currentTimeMillis()
        return when (c.action) {
            Commands.Action.DELETE -> confirmDelete(matchEntries(book.entries, c.query), now)
            Commands.Action.DELETE_PERIOD -> c.period?.let { confirmDelete(matchEntriesByPeriod(book.entries, it), now) }
            Commands.Action.CLEAR_ALL -> confirmDelete(book.entries, now)
            Commands.Action.SEARCH -> {
                val hits = matchEntries(book.entries, c.query)
                if (hits.isEmpty()) say("\"${c.query}\" ki koi entry nahi mili.", "\"${c.query}\" کی کوئی انٹری نہیں ملی۔")
                else listOf(hits, c.query to c.query)
            }
            Commands.Action.EDIT -> say("Kya badalna hai? Jaise: ${c.query} wali entry 400 kar do.", "کیا بدلنا ہے؟ جیسے: ${c.query} والی انٹری 400 کر دو۔")
        }
    }

    /** "eint ke pese abhi nahi diye" / "Afzal ko eint ke pese de diye": a sale or purchase paid or on credit. */
    private fun payStatus(text: String): Reply? {
        val st = Parser.payStatus(text) ?: return null
        val trades = book.entries.filter { isTrade(it.type) }.sortedByDescending { it.createdAt }
        if (trades.isEmpty()) return null
        fun hit(e: Entry, k: String) = e.person.equals(k, true) ||
            e.item.lowercase().split(Regex("""\s+""")).any { w -> w.length >= 3 && (w.startsWith(k) || k.startsWith(w)) }
        val target = (if (st.keywords.isNotEmpty()) trades.firstOrNull { e -> st.keywords.all { hit(e, it) } } ?: trades.firstOrNull { e -> st.keywords.any { hit(e, it) } }
            else if (st.mentionsMoney) trades.firstOrNull() else null) ?: return null
        val who = target.person.ifBlank { st.person }
        if (!st.paid && who.isBlank() && target.type == Type.PURCHASE) {
            return say("Supplier ka naam bhi bata dein.", "سپلائر کا نام بھی بتا دیں۔")
        }
        val updated = target.copy(paid = st.paid, person = who)
        book.entries = book.entries.map { if (it.id == target.id) updated else it }
        val label = target.item.ifBlank { if (target.type == Type.SALE) "Sale" else "Purchase" }
        val head = if (st.paid) Reply("$label ki adaigi ho gayi.", "$label کی ادائیگی ہو گئی۔") else Reply("$label udhar par likh diya.", "$label ادھار پر لکھ دیا۔")
        if (who.isBlank()) return Reply(head.ro, head.ur, true)
        val b = balanceLine(who)
        return Reply(head.ro + " " + b.ro, head.ur + " " + b.ur, true)
    }

    // ------------------------------------------------------------- the words ---

    private fun names(): List<String> = personAccounts(book.entries).mapNotNull { cleanPersonName(it.name) }.distinctBy { it.lowercase() }

    /**
     * What an entry was for, in Roman Urdu and Urdu. BolKhata names items in English ("Tea & Coffee");
     * the word the person actually said ("chai") is used instead where its note has it, else the item's
     * usual word, and the category's Urdu name when there is nothing better for the Urdu voice.
     */
    private fun what(e: Entry): Pair<String, String> {
        val generic = e.item.isBlank() || e.item.equals(e.type.label, true) || e.item.equals(e.category.label, true) ||
            e.item.equals("Loans", true) || e.item.equals("Sale", true) || e.item.equals("Purchase", true)
        if (!generic) {
            val said = runCatching { Translit.toRoman(e.note) }.getOrDefault(e.note).lowercase(Locale.ROOT)
            val heard = Regex("""[^\p{L}]+""").split(e.note.lowercase(Locale.ROOT) + " " + said).filter { it.length >= 2 }.toSet()
            val known = ItemWords.words[e.item]
            if (known != null) {
                val ro = known.firstOrNull { it in heard && it.none { c -> c in '\u0600'..'\u06FF' } }
                    ?: known.firstOrNull { it.none { c -> c in '\u0600'..'\u06FF' } && !it.contains(' ') } ?: e.item
                val ur = known.firstOrNull { it.any { c -> c in '\u0600'..'\u06FF' } && it in heard }
                    ?: known.firstOrNull { it.any { c -> c in '\u0600'..'\u06FF' } }
                    ?: (CAT[e.category]?.second ?: ro)
                return ro to ur
            }
            val ur = if (e.item.any { it in '\u0600'..'\u06FF' }) e.item else (CAT[e.category]?.second ?: e.item)
            return e.item to ur
        }
        if (e.person.isNotBlank()) return e.person to e.person
        return when (e.type) {
            Type.SALE -> "maal" to "مال"
            Type.PURCHASE -> "maal" to "مال"
            else -> CAT[e.category] ?: (e.category.label to e.category.label)
        }
    }

    private fun savedLine(e: Entry, now: Long): Reply {
        val (ro, ur) = what(e)
        val a = fmt(e.amount); val u = n(e.amount)
        val p = e.person
        val base = when (e.type) {
            Type.KHARCHA -> Reply("$ro ke $a rupay likh liye.", "$ur کے $u روپے لکھ لیے۔")
            Type.AAMDANI -> Reply("$a rupay aamdani likh li.", "$u روپے آمدنی لکھ لی۔")
            Type.DIYA -> Reply("$p ko $a rupay diye, likh liya.", "$p کو $u روپے دیے، لکھ لیا۔")
            Type.LIYA -> Reply("$p se $a rupay liye, likh liya.", "$p سے $u روپے لیے، لکھ لیا۔")
            Type.WAPIS_MILA -> Reply("$p ne $a rupay wapas kiye, likh liya.", "$p نے $u روپے واپس کیے، لکھ لیا۔")
            Type.WAPIS_DIYA -> Reply("$p ko $a rupay wapas kiye, likh liya.", "$p کو $u روپے واپس کیے، لکھ لیا۔")
            Type.SALE -> Reply(qtyRo(e) + "$ro ki bikri $a rupay" + (if (p.isNotBlank()) ", $p ko" + (if (e.paid) "" else " udhar par") else "") + ", likh li.",
                qtyUr(e) + "$ur کی بکری $u روپے" + (if (p.isNotBlank()) "، $p کو" + (if (e.paid) "" else " ادھار پر") else "") + "، لکھ لی۔")
            Type.PURCHASE -> Reply(qtyRo(e) + "$ro ki khareed $a rupay" + (if (p.isNotBlank()) ", $p se" + (if (e.paid) "" else " udhar par") else "") + ", likh li.",
                qtyUr(e) + "$ur کی خرید $u روپے" + (if (p.isNotBlank()) "، $p سے" + (if (e.paid) "" else " ادھار پر") else "") + "، لکھ لی۔")
            Type.TRANSFER_OUT -> Reply("$p ko $a rupay bheje, likh liya.", "$p کو $u روپے بھیجے، لکھ لیا۔")
            Type.TRANSFER_IN -> Reply("$p se $a rupay aaye, likh liya.", "$p سے $u روپے آئے، لکھ لیا۔")
        }
        val day = dayName(e.date, now)
        return if (day == null) base else Reply("${day.first}: ${base.ro}", "${day.second}: ${base.ur}")
    }

    private fun qtyRo(e: Entry) = if (e.qty > 0) "${fmt(e.qty)} " + (if (e.unit.isBlank()) "" else e.unit + " ") else ""
    private fun qtyUr(e: Entry) = if (e.qty > 0) "${n(e.qty)} " + (if (e.unit.isBlank()) "" else e.unit + " ") else ""

    /** "Kal", "5 November", or null for today. */
    private fun dayName(date: Long, now: Long): Pair<String, String>? {
        val d = Calendar.getInstance().apply { timeInMillis = date }
        val t = Calendar.getInstance().apply { timeInMillis = now }
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (same(d, t)) return null
        val y = (t.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (same(d, y)) return "Kal" to "کل"
        val m = d.get(Calendar.MONTH)
        return "${d.get(Calendar.DAY_OF_MONTH)} ${MONTHS_RO[m]}" to "${d.get(Calendar.DAY_OF_MONTH)} ${MONTHS_UR[m]}"
    }

    /** How a query is spoken: "Aaj", "Is mahine petrol", "5 se 9 November Ahmed"… */
    private fun labelOf(q: Query.Filter, now: Long): Pair<String, String> {
        val ro = mutableListOf<String>(); val ur = mutableListOf<String>()
        if (q.from != null || q.to != null) {
            val from = q.from ?: 0L
            val to = q.to ?: now
            val a = Calendar.getInstance().apply { timeInMillis = from }
            val b = Calendar.getInstance().apply { timeInMillis = to }
            val t = Calendar.getInstance().apply { timeInMillis = now }
            val sameDay = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
            val wholeMonth = a.get(Calendar.DAY_OF_MONTH) == 1 && a.get(Calendar.MONTH) == b.get(Calendar.MONTH) && a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                (b.get(Calendar.DAY_OF_MONTH) == b.getActualMaximum(Calendar.DAY_OF_MONTH) || (b.get(Calendar.MONTH) == t.get(Calendar.MONTH) && b.get(Calendar.YEAR) == t.get(Calendar.YEAR)))
            when {
                q.from == null -> { ro += "Ab tak"; ur += "اب تک" }
                sameDay -> dayName(from, now)?.let { ro += it.first; ur += it.second } ?: run { ro += "Aaj"; ur += "آج" }
                wholeMonth && a.get(Calendar.MONTH) == t.get(Calendar.MONTH) && a.get(Calendar.YEAR) == t.get(Calendar.YEAR) -> { ro += "Is mahine"; ur += "اس مہینے" }
                wholeMonth -> { ro += MONTHS_RO[a.get(Calendar.MONTH)]; ur += MONTHS_UR[a.get(Calendar.MONTH)] }
                else -> {
                    ro += "${a.get(Calendar.DAY_OF_MONTH)} ${MONTHS_RO[a.get(Calendar.MONTH)]} se ${b.get(Calendar.DAY_OF_MONTH)} ${MONTHS_RO[b.get(Calendar.MONTH)]} tak"
                    ur += "${a.get(Calendar.DAY_OF_MONTH)} ${MONTHS_UR[a.get(Calendar.MONTH)]} سے ${b.get(Calendar.DAY_OF_MONTH)} ${MONTHS_UR[b.get(Calendar.MONTH)]} تک"
                }
            }
        } else { ro += "Ab tak"; ur += "اب تک" }
        q.cats.forEach { c -> (CAT[c] ?: (c.label to c.label)).let { ro += it.first; ur += it.second } }
        q.items.forEach { ro += it; ur += it }
        q.persons.forEach { ro += it; ur += it }
        if (q.types == setOf(Type.AAMDANI)) { ro += "aamdani"; ur += "آمدنی" }
        return ro.joinToString(" ") to ur.joinToString(" ")
    }

    private fun say(ro: String, ur: String, changed: Boolean = false) = Reply(ro, ur, changed)
    private fun join(parts: List<Reply>, changed: Boolean) = Reply(parts.joinToString(" ") { it.ro }, parts.joinToString(" ") { it.ur }, changed)
    /** A number for Urdu speech: no thousands commas, which the voice reads out. */
    private fun n(v: Double) = fmt(v).replace(",", "")

    private companion object {
        val UNDO = setOf("undo", "wapis", "waapas")
        val AMOUNT_ONLY = setOf("rupay", "rupaye", "rupee", "rupees", "rs", "pkr", "sar", "riyal", "ke", "ka", "ki", "the", "thay", "tha", "thi", "hain", "hai", "ji", "jee", "bas", "sirf", "hazar", "hazaar", "sau", "lakh")
        val DELETE_WORDS = setOf("mita", "mitao", "mitado", "hata", "hatao", "hatado", "delete", "remove")
        /** Urdu words for BolKhata's command words, given to its engine the way it takes learned word fixes. */
        val DEFAULT_FIXES = mapOf("mita" to "delete", "mitao" to "delete", "mitado" to "delete", "hata" to "delete", "hatao" to "delete", "hatado" to "delete")
        val YES = setOf("haan", "han", "ha", "haa", "ji", "jee", "yes", "theek", "thik", "bilkul", "zaroor", "kar", "karo", "kardo", "mitao", "mita", "okay", "ok")
        val NO = setOf("nahi", "nahin", "nai", "na", "no", "mat", "cancel", "ruko", "rehne", "chhoro", "chodo")
        val QUESTION_WORDS = setOf("kya", "kia", "kitna", "kitne", "kitni", "how", "what", "who", "when", "where", "which", "whether", "kab", "kahan", "kaun")
        val MONTHS_RO = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val MONTHS_UR = listOf("جنوری", "فروری", "مارچ", "اپریل", "مئی", "جون", "جولائی", "اگست", "ستمبر", "اکتوبر", "نومبر", "دسمبر")
        val CAT = mapOf(
            Cat.KHANA to ("khanay" to "کھانے"), Cat.TRANSPORT to ("safar" to "سفر"), Cat.RENT to ("kiraye" to "کرائے"),
            Cat.BIJLI to ("bill" to "بل"), Cat.MOBILE to ("mobile" to "موبائل"), Cat.GHAR to ("ghar bheje" to "گھر بھیجے"),
            Cat.SHOPPING to ("shopping" to "شاپنگ"), Cat.SEHAT to ("ilaj" to "علاج"), Cat.PERSONAL to ("zaati kharche" to "ذاتی خرچے"),
            Cat.TANKHWAH to ("aamdani" to "آمدنی"), Cat.UDHAAR to ("udhar" to "ادھار"), Cat.FEES to ("fees" to "فیس"),
            Cat.EDU to ("parhai" to "پڑھائی"), Cat.CHARITY to ("khairat" to "خیرات"), Cat.ASSETS to ("saman" to "سامان"),
            Cat.SALES to ("bikri" to "بکری"), Cat.STOCK to ("khareed" to "خرید"), Cat.PRODUCTION to ("banwai" to "بنوائی"),
            Cat.OTHER to ("doosre kharche" to "دوسرے خرچے")
        )
    }
}
