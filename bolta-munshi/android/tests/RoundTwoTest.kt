package com.boltamunshi.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** The problems the second review found, each pinned down. */
class RoundTwoTest {
    private fun at(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s)!!.time
    private val now = at("2026-11-14 12:00")          // a Saturday
    private fun book() = Memory(File.createTempFile("memory", ".json").apply { delete() })
    private fun op(kind: String, amount: Double = 0.0, date: String? = null, person: String = "", item: String = "") =
        JSONObject().put("kind", kind).put("amount", amount).put("person", person).put("item", item).apply { if (date != null) put("date", date) }
    private fun fixOne(said: String, a: JSONObject, n: Long = now): JSONObject {
        val o = JSONObject().put("add", JSONArray().put(a)); Dates.fix(o, said, n); return o.getJSONArray("add").getJSONObject(0)
    }
    private fun remind(said: String, ai: String, n: Long = now) = fixOne(said, JSONObject().put("kind", "task").put("text", "x").put("remind_at", ai), n).getString("remind_at")

    @Test fun aPromiseDoesNotMoveTodaysMoney() {
        for (said in listOf("Ali ko 500 diye kal wapis karega", "علی کو 500 دیے کل واپس کرے گا", "Ali ko 500 diye wo kal lauta dega",
                "Ali ko 500 diye parson wapis karega", "Ali ko 500 diye jumma ko wapis karega", "500 ka petrol dalwaya kal phir dalwana hai"))
            assertEquals(said, "2026-11-14", fixOne(said, op("gave", 500.0, person = "Ali")).getString("date"))
        assertEquals("2026-11-13", fixOne("kal Ali ko 500 diye the", op("gave", 500.0, person = "Ali")).getString("date"))
        assertEquals("2026-11-13", fixOne("kal Ali ko 500 diye wo jumma ko wapis karega", op("gave", 500.0, person = "Ali")).getString("date"))
        // money only promised is not written at all
        val m = book()
        val o = JSONObject().put("add", JSONArray().put(op("got_back", 1000.0, "2026-11-15", person = "Usman")))
        Dates.fix(o, "Usman kal 1000 wapis karega", now)
        assertEquals(0, m.apply(o, now)); assertEquals(1, m.promises)
    }

    @Test fun sameAmountTwoDays() {
        val o = JSONObject().put("add", JSONArray().put(op("expense", 500.0, "kal", item = "petrol")).put(op("expense", 500.0, "2026-11-14", item = "sabzi")))
        Dates.fix(o, "کل 500 کا پیٹرول ڈلوایا اور آج 500 کی سبزی لی", now)
        assertEquals("2026-11-13", o.getJSONArray("add").getJSONObject(0).getString("date"))
        assertEquals("2026-11-14", o.getJSONArray("add").getJSONObject(1).getString("date"))
        val p = JSONObject().put("add", JSONArray().put(op("gave", 1000.0, person = "Ali")).put(op("took", 1000.0, person = "Bilal")))
        Dates.fix(p, "کل علی کو 1000 دیے اور آج بلال سے 1000 لیے", now)
        assertEquals("2026-11-13", p.getJSONArray("add").getJSONObject(0).getString("date"))
        assertEquals("2026-11-14", p.getJSONArray("add").getJSONObject(1).getString("date"))
    }

    @Test fun reminderTimesAsSaid() {
        assertEquals("2026-11-14 17:00", remind("شام پانچ بجے علی کو فون کرنا یاد دلانا", "2026-11-14 17:00"))
        assertEquals("2026-11-14 17:00", remind("shaam paanch baje yaad dilana", "2026-11-14 18:00"))
        assertEquals("2026-11-14 22:00", remind("رات دس بجے یاد دلانا", "2026-11-14 21:00"))
        assertEquals("2026-11-15 08:00", remind("کل صبح آٹھ بجے یاد دلانا", "kal 09:00"))
        assertEquals("2026-11-14 17:30", remind("shaam saadhe 5 baje yaad dilana", ""))
        assertEquals("2026-11-15 08:45", remind("kal پونے 9 بجے یاد دلانا", ""))
        assertEquals("2026-11-15 00:00", remind("raat 12 baje yaad dilana", "", at("2026-11-14 20:00")))
        assertEquals("2026-11-15 05:00", remind("5 am yaad dilana flight hai", "", at("2026-11-14 20:00")))
        assertEquals("2026-11-14 23:00", remind("aaj raat yaad dilana", "2026-11-14 23:00", at("2026-11-14 22:30")))
        assertEquals("2026-11-15 09:00", remind("subah 9 baje bill jama karna yaad dilana", "2026-11-14 09:00", at("2026-11-14 15:00")))
        assertEquals("2026-11-14 17:00", remind("5 baje dawai yaad dilana", "", at("2026-11-14 14:00")))
        assertEquals("2026-11-15 09:00", remind("kal subah 9 baje dawai yaad dilana", "2026-11-16 09:00", at("2026-11-15 00:30")))
    }

    @Test fun aReminderInAMixedSentence() {
        val o = JSONObject().put("add", JSONArray().put(op("expense", 500.0, item = "petrol"))
            .put(JSONObject().put("kind", "task").put("text", "gaari ki service").put("remind_at", "kal 09:00")))
        Dates.fix(o, "parson 500 ka petrol dalwaya tha aur kal subah gaari ki service yaad dilana", now)
        assertEquals("2026-11-12", o.getJSONArray("add").getJSONObject(0).getString("date"))
        assertEquals("2026-11-15 09:00", o.getJSONArray("add").getJSONObject(1).getString("remind_at"))
    }

    @Test fun theAisRelativeTimes() {
        assertEquals("2026-12-14 09:00", Dates.at("+1 month", now))
        assertEquals("2026-11-28 09:00", Dates.at("+2 hafte", now))
        assertEquals("2026-11-21 09:00", Dates.at("+1 week", now))
        assertEquals("2026-11-14 12:30", Dates.at("+30 min", now))
        assertEquals("2026-11-14 14:00", Dates.at("+2 h", now))
    }

    @Test fun weekdaysAndAbhiAndNotes() {
        val tue = at("2026-11-17 12:00")
        assertEquals("2026-11-10", fixOne("pichle mangal ko Bilal ko 1000 diye", op("gave", 1000.0, "2026-11-10", person = "Bilal"), tue).getString("date"))
        assertEquals("2026-11-10", fixOne("mangal ko 1000 diye", op("expense", 1000.0, "2026-11-10"), tue).getString("date"))
        assertEquals("2026-11-14", fixOne("کل علی کو 500 دیے تھے، ابھی 200 واپس کیے", op("got_back", 200.0, person = "Ali")).getString("date"))
        assertEquals("2026-11-14", fixOne("Ali ki shaadi 20 December ko hai", JSONObject().put("kind", "note").put("text", "Ali ki shaadi 20 December")).getString("date"))
        assertEquals("2026-11-13", fixOne("کل ادھار لیا 500 علی سے", op("took", 500.0, person = "Ali")).getString("date"))
    }

    @Test fun amountsTheLocalWay() {
        assertEquals(3500.0, Words.amountOf("saadhe 3 hazaar"), 0.001)
        assertEquals(3500.0, Words.amountOf("ساڑھے 3 ہزار"), 0.001)
        assertEquals(150000.0, Words.amountOf("1,50,000"), 0.001)
        assertEquals(2500.0, Words.amountOf("Rs. 2,500/-"), 0.001)
        assertEquals(1500.0, Words.amountOf("پندرہ سو"), 0.001)
        assertEquals(1000.0, Words.amountOf("ہزار روپے"), 0.001)
        assertEquals(500.0, Words.amountOf("Rs500"), 0.001)
    }

    @Test fun namesStayApart() {
        assertEquals("Ammi", People.canonical("Ammi jaan", listOf("Ammi")))
        assertEquals("Ammi", People.canonical("امی جان", listOf("Ammi")))
        assertEquals("Baji", People.canonical("Baji jaan", listOf("Baji")))
        assertEquals("Ali Jan", People.canonical("Ali Jan", emptyList()))
        assertTrue(People.canonical("عالیہ", listOf("Ali")) != "Ali")
        assertTrue(People.canonical("حسین", listOf("Hasan")) != "Hasan")
        assertEquals("Imran", People.canonical("Imran", listOf(People.canonical("عمران", emptyList()))))
        assertEquals("Saqib", People.canonical("ثاقب", listOf("Saqib")))
    }

    @Test fun aRepaymentNeverMakesANewLoan() {
        val m = book()
        assertEquals(0, m.apply(JSONObject().put("add", op("got_back", 500.0, person = "Ali")), now))
        assertEquals(listOf("Ali" to -500.0), m.noDebt)
        m.apply(JSONObject().put("add", op("gave", 800.0, person = "Ali")), now)
        assertEquals(1, m.apply(JSONObject().put("add", op("got_back", 1000.0, person = "Ali")), now))
        assertEquals(0.0, m.balanceOf("Ali"), 0.001)                  // 800 back; the 200 beyond the loan is not made up
        assertEquals(listOf("Ali" to -200.0), m.noDebt)
        // settle said with a repayment in one sentence is worked out last
        val n = book()
        n.apply(JSONObject().put("add", op("gave", 1000.0, person = "Bilal")), now)
        n.apply(JSONObject().put("add", JSONArray().put(op("settle", person = "Bilal")).put(op("got_back", 300.0, person = "Bilal"))), now)
        assertEquals(0.0, n.balanceOf("Bilal"), 0.001)
        assertEquals(1000.0, n.recs.filter { it.kind == "got_back" }.sumOf { it.amount }, 0.001)
    }

    @Test fun fixingASplitRepayment() {
        val m = book()
        m.apply(JSONObject().put("add", op("took", 1000.0, person = "Ali")), now)
        m.apply(JSONObject().put("add", op("gave", 1500.0, person = "Ali")), now + 1000)   // 1000 paid back + 500 lent
        assertEquals(500.0, m.balanceOf("Ali"), 0.001)
        val lent = m.recs.first { it.kind == "gave" }
        m.apply(JSONObject().put("change", JSONArray().put(JSONObject().put("id", lent.id).put("amount", 1200))), now + 2000)
        assertEquals(200.0, m.balanceOf("Ali"), 0.001)                 // 1200 given against a 1000 debt
    }

    @Test fun payingOffAShopsUdhaar() {
        val m = book()
        m.apply(JSONObject().put("add", op("expense", 1500.0, person = "Kiryana wala", item = "rashan").put("udhaar", true)), now)
        m.apply(JSONObject().put("add", op("expense", 1500.0, person = "Kiryana wala")), now)
        assertEquals(1500.0, m.spent(m.periods(now)[0]), 0.001)        // spent once
        assertEquals(0.0, m.balanceOf("Kiryana wala"), 0.001)
        // a debt for work: paying it is spending, and it closes the debt
        val w = book()
        w.apply(JSONObject().put("add", op("i_owe", 3000.0, person = "Mistri")), now)
        w.apply(JSONObject().put("add", op("expense", 3000.0, person = "Mistri")), now)
        assertEquals(3000.0, w.spent(w.periods(now)[0]), 0.001)
        assertEquals(0.0, w.balanceOf("Mistri"), 0.001)
        // buying something new at a shop one owes is just spending
        m.apply(JSONObject().put("add", op("expense", 1500.0, person = "Kiryana wala", item = "rashan").put("udhaar", true)), now)
        m.apply(JSONObject().put("add", op("expense", 200.0, person = "Kiryana wala", item = "cheeni")), now)
        assertEquals(-1500.0, m.balanceOf("Kiryana wala"), 0.001)
    }

    @Test fun changesWithNullsKeepWhatWasThere() {
        val m = book()
        m.apply(JSONObject().put("add", op("expense", 1500.0, person = "Kiryana wala", item = "rashan").put("udhaar", true)), now)
        val id = m.recs[0].id
        m.apply(JSONObject().put("change", JSONArray().put(JSONObject().put("id", id).put("amount", 1800).put("udhaar", JSONObject.NULL).put("remind_at", JSONObject.NULL))), now)
        assertTrue(m.recs[0].udhaar)
        assertEquals(-1800.0, m.balanceOf("Kiryana wala"), 0.001)
    }

    @Test fun urduItemsAndQuestions() {
        val m = book()
        m.apply(JSONObject().put("add", JSONArray().put(op("expense", 500.0, item = "petrol")).put(op("expense", 300.0, item = "پیٹرول"))), now)
        assertTrue(m.items(m.periods(now)[0]), m.items(m.periods(now)[0]).startsWith("petrol 800 (2x)"))
        val b = book()
        b.apply(JSONObject().put("add", op("gave", 5000.0, person = "Saqib", item = "shaadi")), now)
        b.apply(JSONObject().put("add", JSONObject().put("kind", "note").put("text", "Gaari ke kaghaz almari mein hain")), now)
        repeat(40) { b.apply(JSONObject().put("add", op("expense", 100.0, item = "khana")), now + it * 1000L) }
        assertTrue(b.relevantRecs("ثاقب کو کب دیے تھے").any { it.person == "Saqib" })
        assertTrue(b.relevantRecs("کاغذ کہاں رکھے ہیں").none { it.item == "khana" })
    }
}
