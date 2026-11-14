package com.boltamunshi.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The memory book as a PDF in Downloads: totals, loans, notes and the latest entries. */
object Report {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    /** Where the PDF went ("Downloads/…"), or null if it could not be written. */
    fun make(context: Context, memory: Memory): String? {
        val now = System.currentTimeMillis()
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
            page.canvas.drawText(left.take(80), M, y + 12f, text)
            if (amount != null) page.canvas.drawText(amount, W - M, y + 12f, right)
            y += 18f
        }
        fun section(name: String) {
            need(40f); y += 14f
            page.canvas.drawText(name, M, y + 13f, head); y += 20f
            page.canvas.drawLine(M, y, W - M, y, rule); y += 6f
        }

        page.canvas.drawText("Smart Book · Report", M, y + 22f, title); y += 32f
        page.canvas.drawText(SimpleDateFormat("d MMMM yyyy, h:mm a", Locale.US).format(Date(now)), M, y + 10f, grey); y += 18f

        section("Totals")
        memory.summary(now).lines().drop(1).filter { !it.startsWith("Loans") }.forEach { l ->
            val name = l.substringBefore(":"); val rest = l.substringAfter(": ")
            line(name, rest.substringBefore(" (").take(60))
        }
        val bal = memory.balances()
        if (bal.isNotEmpty()) {
            section("Loans")
            bal.forEach { (p, v) -> line(if (v > 0) "$p owes you" else "You owe $p", Memory.money(kotlin.math.abs(v))) }
        }
        val notes = memory.recs.filter { it.kind == "note" || it.kind == "task" }.take(40)
        if (notes.isNotEmpty()) {
            section("Notes and tasks")
            notes.forEach { line(it.date + "   " + (if (it.kind == "task") "To do · " else "") + it.text.ifBlank { it.item }, null) }
        }
        section("Latest money entries")
        val money = memory.recs.filter { it.kind in Memory.MONEY }.take(60)
        if (money.isEmpty()) line("Nothing yet.", null)
        money.forEach { r ->
            val what = listOf(r.item, r.person).filter { it.isNotBlank() }.joinToString(" · ")
            line(r.date + "   " + r.kind.replace('_', ' ') + (if (what.isEmpty()) "" else " · $what"), Memory.money(r.amount))
        }
        doc.finishPage(page)

        val name = "smart-book-report-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(now)) + ".pdf"
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
