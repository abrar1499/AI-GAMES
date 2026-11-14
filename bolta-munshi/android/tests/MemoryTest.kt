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

class MemoryTest {
    private val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse("2026-11-14 12:00")!!.time
    private fun book() = Memory(File.createTempFile("memory", ".json").apply { delete() })
    private fun add(vararg recs: JSONObject) = JSONObject().put("add", JSONArray().apply { recs.forEach { put(it) } })
    private fun rec(kind: String, amount: Double, item: String = "", person: String = "", date: String = "") =
        JSONObject().put("kind", kind).put("amount", amount).put("item", item).put("person", person).put("date", date)

    @Test fun keepsTotalsExactly() {
        val m = book()
        assertEquals(3, m.apply(add(rec("expense", 500.0, "petrol"), rec("expense", 250.0, "nashta"), rec("income", 2000.0, "salary")), now))
        m.apply(add(rec("expense", 100.0, "chai", date = "2026-11-13")), now)
        val s = m.summary(now)
        assertTrue(s, s.contains("Today: spent 750, earned 2,000"))
        assertTrue(s, s.contains("Yesterday: spent 100, earned 0"))
        assertTrue(s, s.contains("petrol 500"))
    }

    @Test fun moneyWithoutAnAmountIsNotWritten() {
        val m = book()
        assertEquals(0, m.apply(add(rec("expense", 0.0, "petrol")), now))
        assertEquals(1, m.apply(add(JSONObject().put("kind", "note").put("text", "Ali ka birthday 5 December")), now))
        assertEquals("note", m.recs[0].kind)
    }

    @Test fun loansBalancePerPerson() {
        val m = book()
        m.apply(add(rec("gave", 500.0, person = "Hamza"), rec("gave", 200.0, person = "hamza"), rec("got_back", 300.0, person = "Hamza"), rec("took", 1000.0, person = "Bilal")), now)
        val b = m.balances().toMap()
        assertEquals(400.0, b["Hamza"]!!, 0.001)
        assertEquals(-1000.0, b["Bilal"]!!, 0.001)
    }

    @Test fun removesAndChangesById() {
        val m = book()
        m.apply(add(rec("expense", 500.0, "petrol"), rec("expense", 50.0, "chai")), now)
        val petrol = m.recs.first { it.item == "petrol" }.id
        val chai = m.recs.first { it.item == "chai" }.id
        m.apply(JSONObject().put("remove", JSONArray().put(chai)).put("change", JSONArray().put(JSONObject().put("id", petrol).put("amount", 600))), now)
        assertEquals(1, m.recs.size)
        assertEquals(600.0, m.recs[0].amount, 0.001)
    }

    @Test fun survivesARestartAndABackup() {
        val f = File.createTempFile("memory", ".json").apply { delete() }
        Memory(f).apply(add(rec("expense", 75.0, "doodh")), now)
        val again = Memory(f)
        assertEquals(1, again.recs.size)
        val other = book()
        assertTrue(other.restore(again.json()))
        assertEquals("doodh", other.recs[0].item)
        assertTrue(!other.restore("not a backup"))
    }

    @Test fun readsTheAisJsonEvenWithWordsAround() {
        assertEquals("Likh liya.", Brain.jsonIn("Sure:\n```json\n{\"reply\": \"Likh liya.\", \"add\": []}\n```")!!.getString("reply"))
        assertNull(Brain.jsonIn("no json here"))
        assertEquals("limit", Brain.kindOf(429, ""))
        assertEquals("bad", Brain.kindOf(401, ""))
    }

    @Test fun undoPutsTheBookBack() {
        val m = book()
        m.apply(add(rec("expense", 500.0, "petrol")), now)
        m.apply(add(rec("expense", 50.0, "chai")), now)
        assertEquals(listOf("chai"), m.added.map { it.item })
        assertTrue(m.undo())
        assertEquals(listOf("petrol"), m.recs.map { it.item })
        assertTrue(!m.undo())
    }

    @Test fun aRepeatedAddIsWrittenOnce() {
        val m = book()
        m.apply(add(rec("expense", 500.0, "petrol")), now)
        assertEquals(0, m.apply(add(rec("expense", 500.0, "petrol")), now + 5000))
        assertEquals(1, m.recs.size)
    }

    @Test fun moneyWithoutAmountIsCountedAsSkipped() {
        val m = book()
        m.apply(add(rec("expense", 0.0, "petrol")), now)
        assertEquals(1, m.skipped)
    }

    @Test fun remindersKeepTheirTime() {
        val m = book()
        m.apply(add(JSONObject().put("kind", "task").put("text", "Bijli ka bill").put("remind_at", "2026-11-15T09:00:00")), now)
        assertEquals("2026-11-15 09:00", m.recs[0].at)
        assertEquals(1, m.reminders(now).size)
        assertTrue(m.summary(now), m.summary(now).contains("Reminders to come: #1 2026-11-15 09:00 Bijli ka bill"))
        assertTrue(m.reminders(m.recs[0].dueMs() + 1).isEmpty())
        val again = Memory(File.createTempFile("memory", ".json").apply { delete() })
        assertTrue(again.restore(m.json()))
        assertEquals("2026-11-15 09:00", again.recs[0].at)
        assertEquals("", Memory.atOf("kal subah"))
    }

    @Test fun findsOldRecordsByTheirWords() {
        val m = book()
        m.apply(add(rec("gave", 3000.0, "shaadi ka tohfa", "Usman", "2025-01-10")), now)
        repeat(200) { m.apply(add(rec("expense", (it + 1).toDouble(), "chai$it")), now + it * 200_000L) }
        assertTrue(m.recent().indexOf("Usman") < 0)
        assertTrue(m.relevant("Usman ko kitne diye the").contains("Usman"))
        assertEquals("", m.relevant("kya hai"))
    }

    @Test fun earlierMonthsHaveTotals() {
        val m = book()
        m.apply(add(rec("expense", 900.0, "rent", date = "2026-06-03"), rec("income", 5000.0, "salary", date = "2026-06-01")), now)
        assertTrue(m.summary(now), m.summary(now).contains("2026-06 spent 900, earned 5,000"))
    }

    @Test fun idsCanComeWithAHash() {
        val m = book()
        m.apply(add(rec("expense", 500.0, "petrol")), now)
        m.apply(JSONObject().put("remove", JSONArray().put("#1")), now)
        assertTrue(m.recs.isEmpty())
        assertEquals("Petrol · Hamza · 500", Memory.Rec(1, "expense", 500.0, "Hamza", "petrol", "2026-11-14", "", 0).short())
    }

    @Test fun limitsSayWhenTheyEnd() {
        assertEquals(432_500L, Brain.retryAfter(null, "Rate limit reached for model `openai/gpt-oss-120b` on tokens per day (TPD). Please try again in 7m12.5s."))
        assertEquals(2_300L, Brain.retryAfter(null, "Please try again in 2.3s"))
        assertEquals(3_723_000L, Brain.retryAfter(null, "Please try again in 1h2m3s"))
        assertEquals(20_000L, Brain.retryAfter("20", ""))
        assertEquals(60_000L, Brain.retryAfter(null, "nothing"))
        assertEquals(13_000L, Brain.retryAfter(null, "Quota exceeded. Please retry in 13s."))
        assertEquals("limit", Brain.kindOf(413, "Request too large"))
    }

    @Test fun picksChatModelsFromAList() {
        val groq = Brain.chatModels("key_groq", listOf("whisper-large-v3", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "meta-llama/llama-guard-4-12b", "playai-tts", "llama-3.3-70b-versatile"))
        assertEquals(listOf("openai/gpt-oss-120b", "llama-3.3-70b-versatile", "llama-3.1-8b-instant"), groq)
        val gem = Brain.chatModels("key_gemini", listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-preview-tts", "text-embedding-004", "gemini-2.5-flash-lite"))
        assertEquals(listOf("gemini-2.5-flash-lite", "gemini-2.5-flash"), gem)
    }

    @Test fun readsTheServicesOwnWords() {
        assertEquals("Rate limit reached for model x", Brain.detailOf("{\"error\":{\"message\":\"Rate limit reached\\n for model x\",\"type\":\"tokens\"}}"))
        assertEquals("API key not valid.", Brain.detailOf("[{\"error\":{\"code\":400,\"message\":\"API key not valid.\"}}]"))
        assertEquals("", Brain.detailOf("<html>"))
        val failed = "{\"error\":{\"code\":\"json_validate_failed\",\"failed_generation\":\"{\\\"reply\\\": \\\"Ji.\\\"}\"}}"
        assertEquals("Ji.", Brain.jsonIn(Brain.failedGeneration(failed)!!)!!.getString("reply"))
        assertNull(Brain.failedGeneration("{\"error\":{\"message\":\"x\"}}"))
    }
}
