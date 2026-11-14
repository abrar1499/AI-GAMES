package com.boltamunshi.app

import android.content.Context
import com.bolkhata.app.Cat
import com.bolkhata.app.Entry
import com.bolkhata.app.Store
import com.bolkhata.app.Type
import org.json.JSONObject

/**
 * The khata on the phone, kept by BolKhata's own [Store], so it is saved exactly as
 * BolKhata saves it. The list is read once and kept in memory; every change is
 * written straight back.
 */
class KhataStore(context: Context) : VoiceKhata.Book {
    private val store = Store(context)
    private var cached: List<Entry>? = null
    private var cachedTrash: List<Entry>? = null

    override var entries: List<Entry>
        get() = cached ?: store.load().also { cached = it }
        set(v) { cached = v; store.save(v) }

    override var trash: List<Entry>
        get() = cachedTrash ?: store.loadTrash().also { cachedTrash = it }
        set(v) { cachedTrash = v; store.saveTrash(v) }

    override val currency: String get() = store.currency
    override val wordFixes: Map<String, String> get() = runCatching { store.loadWordFixes() }.getOrDefault(emptyMap())

    /** The whole khata as BolKhata's backup file, and back. */
    fun exportJson(): String = store.exportJson()
    fun importJson(text: String) { store.importJson(text); cached = null; cachedTrash = null }
}

/** Moves the hisaab Bolta Munshi kept before into BolKhata's ledger, once. */
object Migrate {
    /**
     * Bolta Munshi's own entries (expense, income, loan, stock) as BolKhata entries.
     * A loan payment is told from a new loan by the running balance with that person.
     */
    fun fromMunshi(json: String): List<Entry> {
        val db = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val list = db.optJSONArray("entries") ?: return emptyList()
        val out = mutableListOf<Entry>()
        val balance = mutableMapOf<String, Double>()            // + they owe me, - I owe them
        val items = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.sortedBy { it.optLong("ts") }
        for (o in items) {
            val amount = o.optDouble("amount", 0.0)
            val ts = o.optLong("ts", System.currentTimeMillis())
            val date = dateOf(o.optString("date"), ts)
            val id = "munshi-" + o.optInt("id") + "-" + ts
            val item = o.optString("item").trim()
            when (o.optString("type")) {
                "expense" -> out += Entry(id, Type.KHARCHA, amount, catOf(o.optString("cat")), item, "", "Bolta Munshi: $item", date, ts)
                "income" -> out += Entry(id, Type.AAMDANI, amount, Cat.TANKHWAH, item.ifBlank { "Income" }, "", "Bolta Munshi: $item", date, ts)
                "loan" -> {
                    val person = o.optString("person").trim()
                    if (person.isEmpty()) continue
                    val delta = o.optDouble("delta", amount)
                    val before = balance[person.lowercase()] ?: 0.0
                    val type = when {
                        delta > 0 && before < -0.005 -> Type.WAPIS_DIYA      // I paid back what I owed
                        delta > 0 -> Type.DIYA                                // I lent
                        before > 0.005 -> Type.WAPIS_MILA                     // they paid back
                        else -> Type.LIYA                                     // I borrowed
                    }
                    balance[person.lowercase()] = before + delta
                    out += Entry(id, type, kotlin.math.abs(delta), Cat.UDHAAR, "Loans", person, "Bolta Munshi", date, ts)
                }
                "stock" -> {
                    val qty = o.optDouble("qty", amount)
                    val person = o.optString("person").trim().takeIf { it != "null" }.orEmpty()
                    out += Entry(id, if (qty < 0) Type.SALE else Type.PURCHASE, 0.0, if (qty < 0) Cat.SALES else Cat.STOCK,
                        o.optString("item"), person, "Bolta Munshi stock", date, ts, qty = kotlin.math.abs(qty), paid = true)
                }
            }
        }
        return out
    }

    private fun dateOf(iso: String, fallback: Long): Long = runCatching {
        val p = iso.split("-").map { it.toInt() }
        java.util.Calendar.getInstance().apply { set(p[0], p[1] - 1, p[2], 12, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
    }.getOrDefault(fallback)

    private fun catOf(c: String): Cat = when (c) {
        "petrol", "safar", "gaari" -> Cat.TRANSPORT
        "khana", "rashan" -> Cat.KHANA
        "kiraya" -> Cat.RENT
        "bill" -> Cat.BIJLI
        "mobile" -> Cat.MOBILE
        "ilaj" -> Cat.SEHAT
        "kapre" -> Cat.SHOPPING
        "parhai" -> Cat.EDU
        else -> Cat.OTHER
    }
}
