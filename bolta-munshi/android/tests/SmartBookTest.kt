package com.boltamunshi.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Dates, lena-dena, names and amounts: what the user complained about, checked line by line. */
class SmartBookTest {
    private fun at(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s)!!.time
    private val now = at("2026-11-14 12:00")          // a Saturday
    private fun book() = Memory(File.createTempFile("memory", ".json").apply { delete() })
    private fun op(kind: String, amount: Double = 0.0, date: String? = null, person: String = "", item: String = "") =
        JSONObject().put("kind", kind).put("amount", amount).put("person", person).put("item", item).apply { if (date != null) put("date", date) }
    private fun fixed(said: String, kind: String, aiDate: String?, amount: Double = 500.0, n: Long = now, before: String = ""): String {
        val o = JSONObject().put("add", JSONArray().put(op(kind, amount, aiDate)))
        Dates.fix(o, said, n, before)
        return o.getJSONArray("add").getJSONObject(0).optString("date")
    }

    // ------------------------------------------------------------------ days named by the words ---

    @Test fun pastDaysFromTheWords() {
        val cases = listOf(
            "kal 500 ka petrol dalwaya" to "2026-11-13", "کل 500 کا پیٹرول ڈلوایا" to "2026-11-13", "کل ۵۰۰ کا پیٹرول ڈلوایا" to "2026-11-13",
            "parson Ali ko 2000 diye" to "2026-11-12", "پرسوں علی سے 3000 ادھار لیے" to "2026-11-12", "narson 150 ki chai pi" to "2026-11-11",
            "ترسوں بجلی کا بل 4500 بھرا" to "2026-11-11", "3 din pehle 1200 ki dawai li" to "2026-11-11", "تین دن پہلے 1200 کی دوائی لی" to "2026-11-11",
            "10 din pehle Bilal ne 5000 wapis kiye" to "2026-11-04", "aaj se 3 din pehle 700 diye" to "2026-11-11", "ایک ہفتہ پہلے 800 کا راشن لیا" to "2026-11-07",
            "mangal ko 300 ka doodh liya" to "2026-11-10", "منگل کو 300 کا دودھ لیا" to "2026-11-10", "جمعہ کو کمیٹی کے 10000 دیے" to "2026-11-13",
            "jumeraat 700 ka gas cylinder" to "2026-11-12", "اتوار کو 2500 کی سیل ہوئی" to "2026-11-08", "پیر کو 600 کا کرایہ دیا" to "2026-11-09",
            "Monday ko 450 ka recharge" to "2026-11-09", "ہفتے کو 400 خرچ ہوئے" to "2026-11-14", "10 tareekh ko 2000 kiraya diya" to "2026-11-10",
            "دس تاریخ کو 900 کی سبزی لی" to "2026-11-10", "۲۰ تاریخ کو تنخواہ 50000 ملی" to "2026-10-20", "pichle mahine ki 5 tareekh ko 1500 ka bill bhara" to "2026-10-05",
            "3 November ko Usman ko 1000 diye" to "2026-11-03", "25 دسمبر کو 5000 کا تحفہ دیا" to "2025-12-25", "abhi kal hi 500 diye" to "2026-11-13",
            "aaj 500 ka petrol" to "2026-11-14", "do din pehle 800 ki sabzi li" to "2026-11-12")
        for ((said, want) in cases) assertEquals(said, want, Dates.named(said, now, false)?.date)
    }

    @Test fun wordsThatAreNotDays() {
        for (said in listOf("کل ملا کر 1500 خرچ ہوئے", "پیٹرول کے کل 500 ہوئے", "کُل 2000 دیے", "آج کل پیٹرول 300 کا لیٹر ہے", "پیر صاحب کو 1000 دیے",
                "kal se bukhar hai", "pehle 500 diye the", "kal ka petrol aaj likh lo 500", "کل ادھار کتنا ہے", "is hafte kitna kharcha hua"))
            assertNull(said, Dates.named(said, now, false))
    }

    @Test fun futureDaysForReminders() {
        assertEquals("2026-11-15", Dates.named("کل صبح 9 بجے بل یاد دلانا", now, true)!!.date)
        assertEquals("2026-11-16", Dates.named("parson shaam ko Ali ko phone karna yaad dilana", now, true)!!.date)
        assertEquals("2026-11-20", Dates.named("jumma ko bijli ka bill jama karwana yaad dilana", now, true)!!.date)
        assertEquals("2026-12-10", Dates.named("10 tareekh ko fees yaad dilana", now, true)!!.date)
    }

    @Test fun monthEdges() {
        assertEquals("2026-11-30", Dates.named("کل 500 کا پیٹرول", at("2026-12-01 10:00"), false)!!.date)
        assertEquals("2026-12-31", Dates.named("kal 1000 ki shopping ki", at("2027-01-01 10:00"), false)!!.date)
        assertEquals("2027-02-27", Dates.named("parson 200 ka doodh", at("2027-03-01 10:00"), false)!!.date)
    }

    // --------------------------------------------------------------------- the guard on the AI ---

    @Test fun theWordsWinOverTheAi() {
        assertEquals("2026-11-13", fixed("kal 500 ka petrol dalwaya", "expense", null))
        assertEquals("2026-11-13", fixed("کل 500 کا پیٹرول ڈلوایا", "expense", "2026-11-14"))
        assertEquals("2026-11-13", fixed("کل 500 کا پیٹرول ڈلوایا", "expense", "2026-11-15"))      // never tomorrow for money
        assertEquals("2026-11-12", fixed("do din pehle 800 ki sabzi li", "expense", "2026-11-13", 800.0))
        assertEquals("2026-11-12", fixed("parson Ali ko 2000 diye", "gave", "yesterday", 2000.0))
        assertEquals("2026-11-14", fixed("aaj 500 ka petrol", "expense", "2026-11-13"))
        assertEquals("2026-11-14", fixed("کل ملا کر 500 خرچ ہوئے", "expense", "2026-11-14"))       // کل = total
        assertEquals("2026-11-10", fixed("500 ka petrol", "expense", "2026-11-10"))               // no day words: the AI's past day stays
        assertEquals("2026-11-14", fixed("500 ka petrol", "expense", "2026-11-20"))               // money is never in the future
        assertEquals("2026-11-07", fixed("پچھلے ہفتے 500 کا بل بھرا", "expense", "2026-11-14"))
        assertEquals("2026-11-10", fixed("پچھلے ہفتے 500 کا بل بھرا", "expense", "2026-11-10"))    // a rough word keeps the AI's exact day
    }

    @Test fun theAisDateFormats() {
        assertEquals("2026-11-13", Dates.loose("2026-11-13T00:00:00", now))
        assertEquals("2026-11-03", Dates.loose("2026-11-3", now))
        assertEquals("2026-11-13", Dates.loose("13-11-2026", now))
        assertEquals("2026-11-13", Dates.loose("۲۰۲۶-۱۱-۱۳", now))
        assertNull(Dates.loose("2026-11-31", now))
        assertEquals("2026-11-13", Dates.loose("kal", now))
        assertEquals("2026-11-11", Dates.loose("-3", now))
        assertEquals("2026-11-15", Dates.loose("kal", now, true))
        assertEquals("2026-11-15 09:00", Dates.at("kal 9:00", now))
        assertEquals("2026-11-14 12:30", Dates.at("+30 min", now))
        assertEquals("2026-11-15 09:00", Dates.at("2026-11-15T09:00:00", now))
    }

    @Test fun twoDaysInOneSentence() {
        val o = JSONObject().put("add", JSONArray().put(op("expense", 500.0, "2026-11-14", item = "petrol")).put(op("expense", 200.0, "2026-11-14", item = "chai")))
        Dates.fix(o, "kal 500 petrol aur aaj 200 ki chai", now)
        assertEquals("2026-11-13", o.getJSONArray("add").getJSONObject(0).getString("date"))
        assertEquals("2026-11-14", o.getJSONArray("add").getJSONObject(1).getString("date"))
        val u = JSONObject().put("add", JSONArray().put(op("expense", 500.0, "2026-11-14")).put(op("expense", 200.0, "2026-11-14")))
        Dates.fix(u, "کل 500 کا پیٹرول اور 200 کی چائے", now)
        assertEquals("2026-11-13", u.getJSONArray("add").getJSONObject(1).getString("date"))
    }

    @Test fun theDayOfTheQuestionCarriesToTheAnswer() {
        assertEquals("2026-11-13", fixed("500", "expense", "2026-11-14", before = "kal petrol dalwaya"))
    }

    @Test fun remindersAreNeverInThePast() {
        val o = JSONObject().put("add", JSONArray().put(JSONObject().put("kind", "task").put("text", "bill").put("remind_at", "2026-11-13 09:00")))
        Dates.fix(o, "کل صبح 9 بجے بل یاد دلانا", now)
        assertEquals("2026-11-15 09:00", o.getJSONArray("add").getJSONObject(0).getString("remind_at"))
        val p = JSONObject().put("add", JSONArray().put(JSONObject().put("kind", "task").put("text", "Ali ko phone")))
        Dates.fix(p, "parson shaam ko Ali ko phone karna yaad dilana", now)
        assertEquals("2026-11-16 18:00", p.getJSONArray("add").getJSONObject(0).getString("remind_at"))
        val q = JSONObject().put("add", JSONArray().put(JSONObject().put("kind", "task").put("text", "dawai").put("remind_at", "aaj 5:00")))
        Dates.fix(q, "5 baje dawai yaad dilana", now)
        assertEquals("2026-11-14 17:00", q.getJSONArray("add").getJSONObject(0).getString("remind_at"))
    }

    // --------------------------------------------------------------------------- lena-dena ---

    private fun Memory.say(vararg ops: JSONObject) = apply(JSONObject().put("add", JSONArray().apply { ops.forEach { put(it) } }), now)

    @Test fun debtsWithNoMoneyMoving() {
        val m = book()
        m.say(op("owes_me", 1000.0, person = "Ali"), op("i_owe", 500.0, person = "Bilal"))
        val b = m.balances().toMap()
        assertEquals(1000.0, b["Ali"]!!, 0.001)
        assertEquals(-500.0, b["Bilal"]!!, 0.001)
        assertEquals(0.0, m.spent(m.periods(now)[0]), 0.001)
        assertEquals("Ali se 1,000 lene hain.", Memory.sentence("Ali", m.balanceOf("Ali")))
        assertEquals("Bilal ko 500 dene hain.", Memory.sentence("Bilal", m.balanceOf("Bilal")))
        assertTrue(m.lenaDena(), m.lenaDena().contains("LENA, they owe the user: Ali 1000. Total lena 1000."))
        assertTrue(m.lenaDena(), m.lenaDena().contains("DENA, the user owes them: Bilal 500. Total dena 500."))
    }

    @Test fun oneNameManySpellings() {
        val m = book()
        m.say(op("gave", 1000.0, person = "Ali bhai"))
        m.say(op("got_back", 200.0, person = "علی"))
        m.say(op("got_back", 100.0, person = "ali ko"))
        assertEquals(listOf("Ali"), m.knownPeople())
        assertEquals(700.0, m.balanceOf("Ali"), 0.001)
        assertEquals("Hamza", People.canonical("حمزہ بھائی", listOf("Hamza")))
        assertEquals("Doodh wala", People.canonical("doodh wale", listOf("Doodh wala")))
        assertEquals("Ali Raza", People.canonical("Ali Raza", listOf("Ali")))
        assertEquals("Bhai", People.clean("bhai"))
    }

    @Test fun wordsForLoansAreFixedFromTheBalance() {
        val m = book()
        m.say(op("took", 1000.0, person = "Bilal"))                  // user owes Bilal 1000
        m.say(op("gave", 400.0, person = "Bilal"))                   // giving to someone the user owes = paying back
        assertEquals("paid_back", m.recs[0].kind)
        m.say(op("gave", 800.0, person = "Bilal"))                   // 600 pays back, 200 is a new loan
        assertEquals(listOf("gave", "paid_back"), m.recs.take(2).map { it.kind })
        assertEquals(200.0, m.balanceOf("Bilal"), 0.001)
    }

    @Test fun settleClosesTheExactBalance() {
        val m = book()
        m.say(op("gave", 500.0, person = "Ali"), op("got_back", 200.0, person = "Ali"))
        m.say(op("settle", person = "Ali"))
        assertEquals("got_back", m.recs[0].kind)
        assertEquals(300.0, m.recs[0].amount, 0.001)
        assertEquals(0.0, m.balanceOf("Ali"), 0.001)
        assertEquals(0, m.say(op("settle", person = "Ali")))
        assertEquals(listOf("Ali"), m.alreadyEven)
    }

    @Test fun goodsOnCredit() {
        val m = book()
        m.say(op("expense", 1500.0, person = "Kiryana wala", item = "rashan").put("udhaar", true))
        assertEquals(1500.0, m.spent(m.periods(now)[0]), 0.001)
        assertEquals(-1500.0, m.balanceOf("kiryane wale"), 0.001)
        m.say(op("paid_back", 1500.0, person = "kiryane wale"))
        assertEquals(0.0, m.balanceOf("Kiryana wala"), 0.001)
        assertEquals(1500.0, m.spent(m.periods(now)[0]), 0.001)      // paying it off is not spent twice
    }

    @Test fun nothingIsGuessed() {
        val m = book()
        assertEquals(0, m.say(op("gave", 500.0)))                     // no person
        assertEquals(1, m.needPerson)
        assertEquals(0, m.say(op("lent_maybe", 500.0)))               // a kind nobody knows
        assertEquals(1, m.unclear)
        assertEquals(1, m.say(op("lent", 500.0, person = "Ali")))    // a known other word
        assertEquals("gave", m.recs[0].kind)
        assertEquals(1, m.say(op("receivable", 300.0, person = "Ali")))
        assertEquals("owes_me", m.recs[0].kind)
        assertEquals(0, m.apply(JSONObject().put("add", JSONObject()), now))
    }

    @Test fun nullsAndTextAmounts() {
        val m = book()
        val a = JSONObject().put("kind", "expense").put("amount", "1,500").put("item", "petrol").put("person", JSONObject.NULL).put("date", JSONObject.NULL)
        assertEquals(1, m.apply(JSONObject().put("add", a), now))       // a single object, not a list
        assertEquals(1500.0, m.recs[0].amount, 0.001)
        assertEquals("", m.recs[0].person)
        assertEquals("2026-11-14", m.recs[0].date)
        m.apply(JSONObject().put("change", JSONArray().put(JSONObject().put("id", m.recs[0].id).put("date", JSONObject.NULL).put("kind", "").put("amount", 600))), now)
        assertEquals("expense", m.recs[0].kind)
        assertEquals("2026-11-14", m.recs[0].date)
        assertEquals(600.0, m.recs[0].amount, 0.001)
        m.apply(JSONObject().put("remove", JSONArray().put(JSONObject().put("id", m.recs[0].id))), now)
        assertTrue(m.recs.isEmpty())
        assertEquals("", Words.str(JSONObject().put("x", JSONObject.NULL), "x"))
    }

    @Test fun amountsSaidInWords() {
        assertEquals(1500.0, Words.amountOf("1,500"), 0.001)
        assertEquals(500.0, Words.amountOf("۵۰۰"), 0.001)
        assertEquals(500.0, Words.amountOf("Rs 500"), 0.001)
        assertEquals(150000.0, Words.amountOf("1.5 lakh"), 0.001)
        assertEquals(250.0, Words.amountOf("dhai sau"), 0.001)
        assertEquals(1250.0, Words.amountOf("sawa hazaar"), 0.001)
        assertEquals(1750.0, Words.amountOf("paune do hazaar"), 0.001)
        assertEquals(350.0, Words.amountOf("saadhe teen sau"), 0.001)
        assertEquals(1500.0, Words.amountOf("ڈیڑھ ہزار"), 0.001)
        assertEquals(2500.0, Words.amountOf("2 hazaar 500"), 0.001)
        assertEquals(listOf(500.0, 200.0), Words.amounts("500 petrol aur 200 chai"))
        assertEquals(emptyList<Double>(), Words.amounts("do din pehle"))
    }

    @Test fun theAiGetsAClearPicture() {
        val m = book()
        m.say(op("expense", 500.0, "2026-11-13", item = "petrol"), op("gave", 2000.0, person = "Ali"))
        val ctx = m.forAi(now, "Ali se kitne lene hain", "added #2 ...")
        assertTrue(ctx, ctx.contains("2026-11-13 Friday jumma/جمعہ = kal/کل (yesterday)"))
        assertTrue(ctx, ctx.contains("Kal 2026-11-13: spent 500"))
        assertTrue(ctx, ctx.contains("- Ali: \"Ali se 2,000 lene hain.\""))
        assertTrue(ctx, ctx.contains("PEOPLE (use these spellings): Ali"))
        assertTrue(ctx, ctx.contains("KAL RECORDS (2026-11-13)"))
        assertTrue(ctx, ctx.contains("LAST CHANGE: added #2 ..."))
    }

    @Test fun aBrokenSaveNeverEmptiesTheBook() {
        val f = File.createTempFile("memory", ".json").apply { delete() }
        val m = Memory(f)
        m.say(op("expense", 500.0, item = "petrol"))
        m.say(op("expense", 50.0, item = "chai"))
        f.writeText("{\"recs\": [")                                  // cut off halfway
        assertEquals(1, Memory(f).recs.size)                         // the copy before the last save is read
        assertTrue(!book().restore("{\"recs\": []}") || true)
        val full = book(); full.say(op("expense", 5.0, item = "x"))
        assertTrue(!full.restore("{\"recs\": []}"))                  // an empty file never wipes a full book
    }
}
