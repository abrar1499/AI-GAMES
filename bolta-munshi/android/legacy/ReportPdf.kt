package com.boltamunshi.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.provider.MediaStore
import com.bolkhata.app.Entry
import com.bolkhata.app.Type
import com.bolkhata.app.fmt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The hisaab as a one- or two-page PDF in Downloads: this month's money in and out, spending
 * by category, who owes whom, and the latest entries. Made on the phone, no internet.
 */
object ReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    private val IN = setOf(Type.AAMDANI, Type.SALE, Type.TRANSFER_IN)
    private val OUT = setOf(Type.KHARCHA, Type.PURCHASE, Type.TRANSFER_OUT)

    /** Where the PDF went ("Downloads/…"), or null if it could not be written. */
    fun make(context: Context, entries: List<Entry>, currency: String): String? {
        val now = Calendar.getInstance()
        val monthStart = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val month = entries.filter { it.date >= monthStart }
        val cashIn = month.filter { it.type in IN }.sumOf { it.amount }
        val cashOut = month.filter { it.type in OUT }.sumOf { it.amount }
        val byCat = month.filter { it.type == Type.KHARCHA }.groupBy { it.category.label }
            .mapValues { e -> e.value.sumOf { it.amount } }.toList().sortedByDescending { it.second }
        val owed = mutableMapOf<String, Double>()          // + they owe me, - I owe them
        for (e in entries) {
            val p = e.person.trim(); if (p.isEmpty()) continue
            val d = when (e.type) { Type.DIYA, Type.WAPIS_DIYA -> e.amount; Type.LIYA, Type.WAPIS_MILA -> -e.amount; else -> 0.0 }
            if (d != 0.0) owed[p] = (owed[p] ?: 0.0) + d
        }
        val people = owed.filter { kotlin.math.abs(it.value) > 0.005 }.toList().sortedByDescending { kotlin.math.abs(it.second) }
        val latest = entries.sortedByDescending { it.date }.take(40)
        val day = SimpleDateFormat("d MMM", Locale.US)
        fun money(v: Double) = "$currency ${fmt(v)}"

        val doc = PdfDocument()
        val title = Paint().apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val head = Paint().apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val text = Paint().apply { textSize = 11f; isAntiAlias = true }
        val grey = Paint().apply { textSize = 10f; color = 0xFF6B7280.toInt(); isAntiAlias = true }
        val right = Paint(text).apply { textAlign = Paint.Align.RIGHT }
        val rule = Paint().apply { color = 0xFFE5E7EB.toInt(); strokeWidth = 1f }
        var pageNo = 0
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
        var y = M
        fun need(h: Float) {
            if (y + h <= H - M) return
            doc.finishPage(page)
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
            y = M
        }
        fun line(left: String, amount: String?) {
            need(18f)
            page.canvas.drawText(left, M, y + 12f, text)
            if (amount != null) page.canvas.drawText(amount, W - M, y + 12f, right)
            y += 18f
        }
        fun section(name: String) {
            need(40f); y += 14f
            page.canvas.drawText(name, M, y + 13f, head); y += 20f
            page.canvas.drawLine(M, y, W - M, y, rule); y += 6f
        }

        page.canvas.drawText("Smart Book · Hisaab report", M, y + 22f, title); y += 32f
        page.canvas.drawText(SimpleDateFormat("d MMMM yyyy, h:mm a", Locale.US).format(now.time), M, y + 10f, grey); y += 18f

        section(SimpleDateFormat("MMMM yyyy", Locale.US).format(now.time))
        line("Money in", money(cashIn))
        line("Money out", money(cashOut))
        line("Saved", money(cashIn - cashOut))

        if (byCat.isNotEmpty()) {
            section("Spending by category (this month)")
            byCat.forEach { (cat, v) -> line(cat, money(v)) }
        }
        if (people.isNotEmpty()) {
            section("Udhar (all time)")
            people.forEach { (p, v) -> line(if (v > 0) "$p owes you" else "You owe $p", money(kotlin.math.abs(v))) }
        }
        section("Latest entries")
        if (latest.isEmpty()) line("No entries yet.", null)
        latest.forEach { e ->
            val what = listOf(e.item, e.person).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { e.category.label }
            line(day.format(Date(e.date)) + "   " + e.type.label + " · " + what.take(48), money(e.amount))
        }
        doc.finishPage(page)

        val name = "smart-book-report-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(now.time) + ".pdf"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) } ?: return null
                "Downloads/$name"
            } else {
                val f = File(context.getExternalFilesDir(null), name)
                f.outputStream().use { doc.writeTo(it) }
                f.absolutePath
            }
        } catch (e: Exception) {
            null
        } finally {
            doc.close()
        }
    }
}
