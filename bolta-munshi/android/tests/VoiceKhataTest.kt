package com.boltamunshi.app

import com.bolkhata.app.Entry
import com.bolkhata.app.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BolKhata's khata logic, driven by voice, answering in Urdu. */
class VoiceKhataTest {
    private class ListBook : VoiceKhata.Book {
        override var entries: List<Entry> = emptyList()
        override var trash: List<Entry> = emptyList()
        override val currency = "PKR"
        override val wordFixes = emptyMap<String, String>()
    }

    private val book = ListBook()
    private val k = VoiceKhata(book)
    private var clock = System.currentTimeMillis()
    private fun say(s: String): VoiceKhata.Reply? { clock += 5_000; return k.reply(s, clock) }
    private fun ro(s: String) = say(s)?.ro.orEmpty()

    @Test fun expensesAreSavedAndSpokenWithTheWordsSaid() {
        assertEquals("petrol ke 500 rupay likh liye.", ro("petrol 500"))
        assertEquals("chai ke 50 rupay likh liye.", ro("chai 50"))
        val r = say("آج 500 کا پیٹرول ڈالا")!!
        assertEquals("پٹرول کے 500 روپے لکھ لیے۔", r.ur)
        assertTrue(r.changed)
        assertEquals(3, book.entries.count { it.type == Type.KHARCHA })
        assertTrue(ro("aaj kitna kharcha hua").startsWith("Aaj total 1,050 rupay."))
    }

    @Test fun anEarlierDayIsNamed() {
        assertEquals("Kal: bijli ke 1,200 rupay likh liye.", ro("kal 1200 bijli ka bill diya"))
    }

    @Test fun loansKeepARunningBalance() {
        assertTrue(ro("Ahmed ko 1000 udhar diye").endsWith("Ahmed ne aap ko 1,000 rupay dene hain."))
        assertTrue(ro("Ahmed ne 400 wapas kiye").endsWith("Ahmed ne aap ko 600 rupay dene hain."))
        assertTrue(ro("Ahmed ka hisaab").startsWith("Ahmed ne aap ko 600 rupay dene hain."))
        assertEquals("Logon se lene hain 600, aap ne dene hain 0 rupay.", ro("udhar kitna hai"))
    }

    @Test fun aSharedBillSplitsIntoShares() {
        val r = ro("khana 300 ali bilal ke sath")
        assertTrue(r, r.startsWith("Aap ka hissa 100 rupay likh liya."))
        assertEquals(3, book.entries.size)
    }

    @Test fun deletingAsksFirstAndCanBeUndone() {
        ro("petrol 500")
        assertTrue(ro("aakhri entry delete karo").endsWith("Haan ya nahi?"))
        assertEquals(1, book.entries.size)                     // nothing gone before the answer
        assertTrue(ro("haan").startsWith("Mita di."))
        assertEquals(0, book.entries.size)
        assertEquals("Mitayi hui entries wapas aa gayin.", ro("wapas lao"))
        assertEquals(1, book.entries.size)
    }

    @Test fun sayingNoKeepsEverything() {
        ro("petrol 500"); ro("chai 50")
        assertTrue(ro("aaj ki entries mita do").endsWith("Haan ya nahi?"))
        assertEquals("Theek hai, kuch nahi mitaya.", ro("nahi"))
        assertEquals(2, book.entries.size)
    }

    @Test fun anEntryCanBeChanged() {
        ro("petrol 500")
        assertEquals("Theek kar diya: petrol 500 ki jagah petrol 600.", ro("petrol wali entry 600 kar do"))
        assertEquals(600.0, book.entries.single().amount, 0.001)
    }

    @Test fun salesSavingsAndProfit() {
        ro("15000 ki sale hui"); ro("petrol 600")
        assertEquals("Is mahine aaye 15,000, gaye 600, bachat 14,400 rupay.", ro("is mahine kitni bachat hui"))
        assertTrue(ro("munafa kitna hua").contains("Is mahine andaazan"))
    }

    @Test fun aMissingAmountIsAskedForButDoesNotTrapTheNextSentence() {
        ro("Ashraf se 500 eent li 20 rupay wali")                // BolKhata itself is unsure here: it asks
        assertEquals("petrol ke 300 rupay likh liye.", ro("petrol 300"))
    }

    @Test fun talkThatIsNotAboutTheKhataIsLeftForTheRestOfTheApp() {
        assertNull(say("salam"))
        assertNull(say("mera naam Abrar hai"))
        assertNull(say("lateefa sunao"))
        assertEquals(0, book.entries.size)
    }

    @Test fun boltaMunshisOldRecordsMoveIntoTheLedger() {
        val old = """{"entries":[
            {"id":1,"type":"expense","amount":500,"cat":"petrol","item":"petrol","date":"2026-11-10","ts":1},
            {"id":2,"type":"income","amount":9000,"item":"dukaan","date":"2026-11-10","ts":2},
            {"id":3,"type":"loan","amount":1000,"delta":1000,"person":"Ali","date":"2026-11-10","ts":3},
            {"id":4,"type":"loan","amount":400,"delta":-400,"person":"Ali","date":"2026-11-11","ts":4},
            {"id":5,"type":"loan","amount":2000,"delta":-2000,"person":"Bilal","date":"2026-11-11","ts":5},
            {"id":6,"type":"stock","amount":25000,"qty":25000,"item":"kachi eent","person":"Ashraf","date":"2026-11-12","ts":6}]}"""
        val moved = Migrate.fromMunshi(old)
        assertEquals(listOf(Type.KHARCHA, Type.AAMDANI, Type.DIYA, Type.WAPIS_MILA, Type.LIYA, Type.PURCHASE), moved.map { it.type })
        book.entries = moved
        assertTrue(ro("Ali ka hisaab").startsWith("Ali ne aap ko 600 rupay dene hain."))
        assertTrue(ro("Bilal ka hisaab").startsWith("Aap ne Bilal ko 2,000 rupay dene hain."))
    }
}
