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
}
