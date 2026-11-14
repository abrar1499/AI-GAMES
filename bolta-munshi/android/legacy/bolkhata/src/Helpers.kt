// Helpers copied from BolKhata's screen files (see tools/extract.py). Do not edit by hand.
package com.bolkhata.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// from Ui.kt
val PAGE_COLORS = listOf(0xFFE9E6E1, 0xFFE6E8EB)

// from Ui.kt
class DarkLook(val page: Long, val card: Long, val well: Long, val edge: Long, val day: Long, val field: Long)

// from Ui.kt
val DARK_LOOKS = listOf(
    DarkLook(0xFF3A3D42, 0xFF2A2C30, 0xFF34373C, 0xFF4A4E54, 0xFF33373E, 0xFF45484D),
    DarkLook(0xFF1B2230, 0xFF121826, 0xFF1D2535, 0xFF2C3648, 0xFF222B3C, 0xFF252D3D)
)

// from Ui.kt
val DARK_PAGES = DARK_LOOKS.map { it.page }

// from Ui.kt
fun fmt(n: Double): String =
    java.text.DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols(Locale.US)).format(if (kotlin.math.abs(n) < 0.005) 0.0 else n)

// from Ui.kt
fun money(cur: String, n: Double) = "${fmt(n)} $cur"

// from Ui.kt
fun cal(ms: Long): Calendar = Calendar.getInstance().apply { timeInMillis = ms }

// from Ui.kt
fun dayNumber(ms: Long): Long {
    val zone = java.util.TimeZone.getDefault()
    return Math.floorDiv(ms + zone.getOffset(ms), 86_400_000L)
}

// from Ui.kt
fun sameDay(a: Long, b: Long): Boolean = dayNumber(a) == dayNumber(b)

// from Ui.kt
fun sameMonth(a: Long, b: Long): Boolean = cal(a).let { x -> cal(b).let { y -> x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.MONTH) == y.get(Calendar.MONTH) } }

// from Ui.kt
fun dayLabel(d: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(d, now) -> "Today"
        sameDay(d, now - 86_400_000L) -> "Yesterday"
        else -> SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(d))
    }
}

// from Ui.kt
fun isLoan(t: Type) = t == Type.DIYA || t == Type.LIYA || t == Type.WAPIS_MILA || t == Type.WAPIS_DIYA

// from Ui.kt
fun isTrade(t: Type) = t == Type.SALE || t == Type.PURCHASE

// from Ui.kt
fun isTransfer(t: Type) = t == Type.TRANSFER_IN || t == Type.TRANSFER_OUT

// from Ui.kt
fun withPerson(e: Entry) = e.person.isNotBlank() && (isLoan(e.type) || isTrade(e.type) || isTransfer(e.type))

// from Ui.kt
fun balances(entries: List<Entry>): Map<String, Double> {
    val map = linkedMapOf<String, Double>()
    for (e in entries) {
        if (!withPerson(e) || e.paid) continue
        val p = e.person.trim()
        // Same rule as the khata: loans, credit trade and bank transfers to / from the person.
        map[p] = (map[p] ?: 0.0) + personEffect(e)
    }
    return map
}

// from Ui.kt
data class StockLine(val name: String, val unit: String, val qty: Double)

// from Ui.kt
fun stock(entries: List<Entry>): List<StockLine> {
    val map = linkedMapOf<String, StockLine>()
    for (e in entries) {
        if (e.qty <= 0 || e.item.isBlank() || e.item == "Loans") continue
        val sign = when (e.type) {
            Type.PURCHASE, Type.LIYA -> 1.0
            Type.SALE, Type.DIYA -> -1.0
            else -> continue
        }
        val key = e.item.trim().lowercase()
        val old = map[key]
        map[key] = StockLine(old?.name ?: e.item.trim(), old?.unit?.ifBlank { e.unit } ?: e.unit, (old?.qty ?: 0.0) + sign * e.qty)
    }
    return map.values.toList()
}

// from Ui.kt
data class ProfitItemBreakdown(
    val item: String,
    val salesAmount: Double,
    val costedSalesAmount: Double,
    val costOfGoods: Double,
    val estimatedProfit: Double,
    val salesWithoutCost: Double,
    val entries: List<Entry>
)

// from Ui.kt
data class ProfitBreakdown(
    val salesAmount: Double,
    val costedSalesAmount: Double,
    val costOfGoods: Double,
    val salesWithoutCost: Double,
    val otherIncome: Double,
    val expenses: Double,
    val netProfit: Double,
    val items: List<ProfitItemBreakdown>
)

// from Ui.kt
fun costOf(e: Entry) = if (e.amount > 0) e.amount else e.rate * e.qty

// from Ui.kt
fun isMade(e: Entry) = e.sourceFingerprint.startsWith("made:")

// from Ui.kt
fun madeRate(all: List<Entry>, qty: Double, now: Long = System.currentTimeMillis()): Double {
    val month = all.filter { sameMonth(it.date, now) }
    val cost = month.filter { it.type == Type.KHARCHA && it.category == Cat.PRODUCTION }.sumOf { it.amount } +
        month.filter { it.type == Type.PURCHASE && it.category == Cat.PRODUCTION && !isMade(it) }.sumOf { it.amount }
    val made = month.filter(::isMade).sumOf { it.qty }
    return if (cost > 0 && made + qty > 0) cost / (made + qty) else 0.0
}

// from Ui.kt
fun madeEntry(item: String, qty: Double, rate: Double, date: Long, unit: String = ""): Entry {
    val id = "e" + System.nanoTime()
    return Entry(id, Type.PURCHASE, 0.0, Cat.STOCK, item, "", "Made", date, System.currentTimeMillis(),
        qty = qty, unit = unit, rate = rate, paid = true, sourceFingerprint = "made:$id")
}

// from Ui.kt
fun profitBreakdown(allEntries: List<Entry>, periodEntries: List<Entry>): ProfitBreakdown {
    val sales = periodEntries.filter { it.type == Type.SALE }
    val costBySaleId = linkedMapOf<String, Double>()
    // Purchases grouped by item once, so a year of sales doesn't scan every entry for each sale.
    val purchasesByItem = if (sales.isEmpty()) emptyMap() else allEntries
        .filter { it.type == Type.PURCHASE && it.qty > 0.0 && costOf(it) > 0.0 }
        .groupBy { it.item.trim().lowercase() }
    sales.forEach { sale ->
        if (sale.qty <= 0.0 || sale.item.isBlank() || sale.item.equals("Sale", ignoreCase = true)) return@forEach
        val purchases = purchasesByItem[sale.item.trim().lowercase()].orEmpty().filter { it.date <= sale.date }
        val purchasedQty = purchases.sumOf { it.qty }
        if (purchasedQty > 0.0) {
            costBySaleId[sale.id] = sale.qty * (purchases.sumOf { costOf(it) } / purchasedQty)
        }
    }
    val itemLines = sales.groupBy { it.item.trim().ifBlank { "Sale" } }
        .map { (name, list) ->
            val costed = list.filter { costBySaleId.containsKey(it.id) }
            val costedSales = costed.sumOf { it.amount }
            val cost = costed.sumOf { costBySaleId[it.id] ?: 0.0 }
            val withoutCost = list.filterNot { costBySaleId.containsKey(it.id) }.sumOf { it.amount }
            ProfitItemBreakdown(
                item = name,
                salesAmount = list.sumOf { it.amount },
                costedSalesAmount = costedSales,
                costOfGoods = cost,
                estimatedProfit = costedSales - cost,
                salesWithoutCost = withoutCost,
                entries = list.sortedWith(compareByDescending<Entry> { it.date }.thenByDescending { it.createdAt })
            )
        }
        .sortedByDescending { it.estimatedProfit }
    val costedSalesAmount = sales.filter { costBySaleId.containsKey(it.id) }.sumOf { it.amount }
    val costOfGoods = costBySaleId.values.sum()
    val totalSales = sales.sumOf { it.amount }
    val salesWithoutCost = (totalSales - costedSalesAmount).coerceAtLeast(0.0)
    val otherIncome = periodEntries.filter { it.type == Type.AAMDANI }.sumOf { it.amount }
    val expenses = periodEntries.filter { it.type == Type.KHARCHA }.sumOf { it.amount }
    return ProfitBreakdown(
        salesAmount = totalSales,
        costedSalesAmount = costedSalesAmount,
        costOfGoods = costOfGoods,
        salesWithoutCost = salesWithoutCost,
        otherIncome = otherIncome,
        expenses = expenses,
        netProfit = costedSalesAmount - costOfGoods - expenses,
        items = itemLines
    )
}

// from Ui.kt
fun typeLabel(t: Type, person: String): String {
    val p = person.trim()
    return when (t) {
        Type.KHARCHA -> "Cash Out"
        Type.AAMDANI -> "Cash In"
        Type.DIYA -> if (p.isEmpty()) "Gave (to get back)" else "Gave to $p"
        Type.LIYA -> if (p.isEmpty()) "Took (to give back)" else "Took from $p"
        Type.WAPIS_MILA -> if (p.isEmpty()) "Got money back" else "$p returned"
        Type.WAPIS_DIYA -> if (p.isEmpty()) "Paid back" else "Returned to $p"
        Type.SALE -> if (p.isEmpty()) "Sale" else "Sold to $p"
        Type.PURCHASE -> if (p.isEmpty()) "Purchase" else "Bought from $p"
        Type.TRANSFER_IN -> if (p.isEmpty()) "Transfer in" else "Transfer from $p"
        Type.TRANSFER_OUT -> if (p.isEmpty()) "Transfer out" else "Transfer to $p"
    }
}

// from Ui.kt
fun entryTitle(e: Entry): String = when {
    isTransfer(e.type) -> typeLabel(e.type, e.person) + (if (e.item.isNotBlank() && !e.item.equals("Transfer", true)) ": " + e.item else "")
    isLoan(e.type) -> typeLabel(e.type, e.person)
    isTrade(e.type) && e.person.isNotBlank() -> typeLabel(e.type, e.person)
    isTrade(e.type) -> typeLabel(e.type, "") + (if (e.item.isNotBlank() && e.item != "Loans") ": " + e.item else "")
    e.item.isNotBlank() -> e.item
    else -> e.category.label
}

// from Ledger.kt
class PersonAcct(
    val name: String,
    val history: List<Pair<Entry, Double>>,   // oldest first, with the balance after each entry
    val balance: Double,
    val toReceive: Boolean,
    val gave: Double,
    val got: Double
)

// from Ledger.kt
fun personEffect(e: Entry): Double = when (e.type) {
    Type.DIYA, Type.WAPIS_DIYA, Type.SALE, Type.TRANSFER_OUT -> e.amount
    Type.LIYA, Type.WAPIS_MILA, Type.PURCHASE, Type.TRANSFER_IN -> -e.amount
    else -> 0.0
}

// from Ledger.kt
fun personHistoryIsOutflow(e: Entry): Boolean = when (e.type) {
    Type.TRANSFER_OUT -> true
    Type.TRANSFER_IN -> false
    else -> personEffect(e) > 0
}

// from Ledger.kt
fun applyKhataContext(entries: List<Entry>, person: String?): List<Entry> {
    val name = person?.trim()?.takeIf { it.isNotEmpty() } ?: return entries
    return entries.map { e ->
        if (e.person.isBlank() && (isLoan(e.type) || isTrade(e.type) || isTransfer(e.type))) e.copy(person = name)
        else e
    }
}

// from Ledger.kt
fun convertPersonTransfersToLedgerEntries(entries: List<Entry>, person: String): List<Pair<Entry, Entry>> {
    val history = entries
        .filter { it.person.equals(person, true) && withPerson(it) && !it.paid }
        .sortedWith(compareBy<Entry> { it.date }.thenBy { it.createdAt })
    var balance = 0.0
    val changed = mutableListOf<Pair<Entry, Entry>>()
    for (e in history) {
        val converted = when (e.type) {
            Type.TRANSFER_OUT -> e.copy(
                type = if (balance < -0.005) Type.WAPIS_DIYA else Type.DIYA,
                category = Cat.UDHAAR,
                item = if (e.item.isBlank() || e.item.equals("Transfer", true)) "Loans" else e.item
            )
            Type.TRANSFER_IN -> e.copy(
                type = if (balance > 0.005) Type.WAPIS_MILA else Type.LIYA,
                category = Cat.UDHAAR,
                item = if (e.item.isBlank() || e.item.equals("Transfer", true)) "Loans" else e.item
            )
            else -> null
        }
        if (converted != null) {
            changed += e to converted
            balance += personEffect(converted)
        } else {
            balance += personEffect(e)
        }
    }
    return changed
}

// from Ledger.kt
fun personAccounts(entries: List<Entry>): List<PersonAcct> =
    entries.filter { withPerson(it) && !it.paid }
        .groupBy { it.person.trim().lowercase() }
        .map { (_, list) ->
            val name = list.groupingBy { it.person.trim() }.eachCount().maxByOrNull { it.value }?.key ?: ""
            val sorted = list.sortedWith(compareBy<Entry> { it.date }.thenBy { it.createdAt })
            var run = 0.0
            var receive = personEffect(sorted.first()) >= 0
            val hist = sorted.map { e ->
                run += personEffect(e)
                if (run > 0.005) receive = true else if (run < -0.005) receive = false
                e to run
            }
            PersonAcct(
                name = name, history = hist, balance = run, toReceive = receive,
                gave = sorted.filter { personEffect(it) > 0 }.sumOf { it.amount },
                got = sorted.filter { personEffect(it) < 0 }.sumOf { it.amount }
            )
        }

// from Ledger.kt
internal fun isOutflow(e: Entry) = when (e.type) {
    Type.KHARCHA, Type.DIYA, Type.WAPIS_DIYA, Type.PURCHASE, Type.TRANSFER_OUT -> true
    else -> false
}

// from Ledger.kt
fun movedTo(e: Entry, all: List<Entry>, cat: Cat?, income: Boolean, person: String?): Entry {
    if (person != null) {
        val p = person.trim()
        val bal = personAccounts(all.filter { it.id != e.id })
            .firstOrNull { it.name.equals(p, true) }?.balance ?: 0.0
        val type = if (isOutflow(e)) (if (bal < -0.005) Type.WAPIS_DIYA else Type.DIYA)
        else (if (bal > 0.005) Type.WAPIS_MILA else Type.LIYA)
        return e.copy(type = type, person = p, category = Cat.UDHAAR, item = "Loans", paid = false)
    }
    val c = cat ?: Cat.OTHER
    val item = if (e.item.isBlank() || e.item == "Loans") c.label else e.item
    return e.copy(type = if (income) Type.AAMDANI else Type.KHARCHA, category = c, person = "", item = item, paid = false)
}

// from Ledger.kt
val defaultSubs: Map<Cat, List<String>> = mapOf(
    Cat.TRANSPORT to listOf("Petrol", "Car repair", "Oil change", "Tyres", "Car wash", "Parking", "Taxi", "Bus"),
    Cat.KHANA to listOf("Groceries", "Restaurant", "Bread", "Sweets", "Tea", "Fruit & veg", "Water"),
    Cat.BIJLI to listOf("Electricity", "Water bill", "Gas"),
    Cat.MOBILE to listOf("Recharge", "Internet"),
    Cat.SEHAT to listOf("Medicine", "Doctor"),
    Cat.PERSONAL to listOf("Haircut", "Soap & shampoo"),
    Cat.FEES to listOf("ID card", "Bank fee", "Visa"),
    Cat.SHOPPING to listOf("Clothes", "Electronics"),
    Cat.EDU to listOf("School fees", "Books"),
    Cat.RENT to listOf("House rent"),
    Cat.CHARITY to listOf("Gifts", "Charity"),
    Cat.PRODUCTION to listOf("Labour", "Raw material", "Fuel", "Loading", "Unloading", "Office", "Salary", "Transport")
)

// from Ledger.kt
fun subsFor(cat: Cat, all: List<Entry>): List<String> {
    val used = all.filter { it.type == Type.KHARCHA && it.category == cat }.groupingBy { it.item.trim() }.eachCount()
        .filter { (n, c) -> c >= 2 && n.length in 2..24 && !n.equals(cat.label, true) && !n.equals("Other", true) }
        .entries.sortedByDescending { it.value }.map { it.key }
    return (defaultSubs[cat].orEmpty() + used).distinctBy { it.lowercase() }
}

// from Ledger.kt
fun withSub(e: Entry, sub: String): Entry {
    val old = e.item.trim()
    val keep = old.isNotBlank() && !old.equals(sub, true) && !old.equals(e.category.label, true) && !e.note.contains(old, true)
    return e.copy(item = sub, note = if (keep) listOf(old, e.note.trim()).filter { it.isNotBlank() }.joinToString(" · ") else e.note)
}

// from Ledger.kt
fun splitSub(to: String): Pair<String, String> = to.substringBefore(" › ").trim() to to.substringAfter(" › ", "").trim()

// from KhataTabs.kt
fun cashOut(e: Entry) = when (e.type) {
    Type.KHARCHA, Type.DIYA, Type.WAPIS_DIYA, Type.TRANSFER_OUT -> true
    // Goods made here moved no cash: they are stock, not a payment.
    Type.PURCHASE -> (e.paid || e.person.isBlank()) && !isMade(e)
    else -> false
}

// from KhataTabs.kt
fun cashIn(e: Entry) = when (e.type) {
    Type.AAMDANI, Type.LIYA, Type.WAPIS_MILA, Type.TRANSFER_IN -> true
    Type.SALE -> e.paid || e.person.isBlank()
    else -> false
}

// from KhataTabs.kt
internal fun plusMinus(v: Double) = (if (v > 0.005) "+" else if (v < -0.005) "−" else "") + fmt(kotlin.math.abs(v))

// from KhataTabs.kt
internal fun source(e: Entry): String {
    val generic = e.item.isBlank() || e.item.equals("Transfer", true) || e.item.equals("Income", true)
    if (!generic) return e.item.trim()
    Regex("""(?i)\bfrom\s+([^.,\d]+)""").find(e.note)?.let { return it.groupValues[1].trim() }
    return e.person.ifBlank { e.item.ifBlank { "Income" } }
}

// from EntryForm.kt
internal fun isOut(t: Type) = t == Type.KHARCHA || t == Type.DIYA || t == Type.WAPIS_DIYA || t == Type.PURCHASE || t == Type.TRANSFER_OUT

// from BusinessCard.kt
data class BizInfo(val owner: String = "", val phone: String = "", val address: String = "", val card: String = "Classic")

// from Eleven.kt
fun shortSpoken(text: String): String {
    val first = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val sentence = Regex("""^.+?[.!?۔](?=\s|$)""").find(first)?.value ?: first
    return if (sentence.length <= 160) sentence else sentence.take(160).substringBeforeLast(' ') + "…"
}

// from Flash.kt
fun cleanPersonName(raw: String): String? {
    var s = raw.trim().split('.', ';', ',', ':', '/', '(').first().trim()
    s = s.replace(Regex("\\d+"), " ").replace(Regex("\\s+"), " ").trim()
    if (s.isBlank()) return null
    val bad = setOf("transfer", "internal", "inward", "outward", "credit", "debit", "notes", "time", "ips", "acct", "pos", "purchase", "fee")
    if (s.split(' ').any { it.lowercase() in bad }) return null
    // "FRIMTIAZ" / "TONISAR": the bank glues "FR" / "TO" to the name.
    if (!s.contains(' ') && s.length >= 5 && s == s.uppercase()) {
        val m = Regex("^(TO|FR)(\\p{Lu}{3,})$").find(s)
        if (m != null) s = m.groupValues[2]
    }
    s = s.split(' ').take(3).joinToString(" ")
    if (s == s.uppercase() && s.any { it in 'A'..'Z' }) s = s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    return s.takeIf { it.length >= 2 }
}
