package com.personal.docvault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.File

/** Shows one document: a zoomable photo or a scrolling list of PDF pages. */
class ViewerActivity : SecureActivity() {

    private lateinit var body: FrameLayout
    private lateinit var status: TextView
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var tempPdf: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (bounceIfLocked()) return

        val id = intent.getStringExtra("id")
        val doc = try {
            Vault.loadIndex(this).firstOrNull { it.id == id }
        } catch (e: Exception) {
            null
        }
        if (doc == null) {
            finish()
            return
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            setBackgroundColor(Ui.PRIMARY)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 16), 0)
        }
        val back = Ui.text(this, "←", 24f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 56)))
        bar.addView(Ui.text(this, doc.name, 18f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))

        body = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#22272E")) }
        status = Ui.text(this, "Opening…", 15f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 24), 0, Ui.dp(context, 24), 0)
        }
        body.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Thread {
            try {
                val bytes = Vault.read(applicationContext, doc)
                if (doc.mime == "application/pdf") {
                    val f = File(Vault.tempDir(applicationContext, "view"), doc.id + ".pdf")
                    f.writeBytes(bytes)
                    runOnUiThread { showPdf(f) }
                } else {
                    val bmp = decodeImage(bytes)
                    runOnUiThread { showImage(bmp) }
                }
            } catch (e: Throwable) {
                runOnUiThread { status.text = "This document could not be opened." }
            }
        }.start()
    }

    private fun decodeImage(bytes: ByteArray): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 3200) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("not an image")
        val angle = try {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (angle == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(angle) }, true)
    }

    private fun showImage(bmp: Bitmap) {
        if (isFinishing || isDestroyed) return
        status.visibility = View.GONE
        val v = ZoomImageView(this)
        body.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.setImageBitmap(bmp)
    }

    private fun showPdf(file: File) {
        tempPdf = file
        if (isFinishing || isDestroyed) {
            file.delete()
            return
        }
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd!!)
        } catch (e: Throwable) {
            status.text = "This PDF could not be opened. It may be password protected or damaged."
            return
        }
        val r = renderer!!
        status.visibility = View.GONE
        val pageWidth = resources.displayMetrics.widthPixels
        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setBackgroundColor(Color.parseColor("#22272E"))
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
            clipToPadding = false
        }
        list.adapter = object : BaseAdapter() {
            override fun getCount() = r.pageCount
            override fun getItem(position: Int) = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val iv = (convertView as? ImageView) ?: ImageView(this@ViewerActivity).apply {
                    adjustViewBounds = true
                    setPadding(0, 0, 0, Ui.dp(context, 8))
                }
                val page = r.openPage(position)
                try {
                    // Render a little sharper than the screen, capped to keep memory low.
                    val w = minOf((pageWidth * 1.5f).toInt(), 2000)
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceIn(1, 4000)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    iv.setImageBitmap(bmp)
                } finally {
                    page.close()
                }
                return iv
            }
        }
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onDestroy() {
        try {
            renderer?.close()
        } catch (e: Exception) {
        }
        try {
            pfd?.close()
        } catch (e: Exception) {
        }
        tempPdf?.delete()
        super.onDestroy()
    }
}
