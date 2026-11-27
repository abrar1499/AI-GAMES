package com.personal.docvault

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The document list: filter by category, add, open, rename, share, export, delete. */
class MainActivity : SecureActivity() {

    private companion object {
        const val REQ_PICK = 1
        const val REQ_EXPORT = 2
        const val ALL = "All"
        const val PICKER_GRACE_MS = 5 * 60 * 1000L
    }

    private var docs = mutableListOf<Vault.Doc>()
    private var shown = listOf<Vault.Doc>()
    private var filter = ALL
    private var pendingCategory = Vault.categories.last()
    private var pendingExportId: String? = null

    private lateinit var chips: LinearLayout
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var subtitle: TextView
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (bounceIfLocked()) return
        savedInstanceState?.let {
            filter = it.getString("filter", ALL)
            pendingCategory = it.getString("pendingCategory", pendingCategory)
            pendingExportId = it.getString("pendingExportId")
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
        }

        // Header
        val bar = LinearLayout(this).apply {
            setBackgroundColor(Ui.PRIMARY)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 20), Ui.dp(context, 14), Ui.dp(context, 6), Ui.dp(context, 14))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Ui.text(this, "My Documents", 21f, Color.WHITE, true))
        subtitle = Ui.text(this, "", 13f, Color.parseColor("#B8C7DA"))
        titles.addView(subtitle)
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val menu = Ui.text(this, "⋮", 26f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Menu"
        }
        menu.setOnClickListener { showMenu(menu) }
        bar.addView(menu, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        root.addView(bar)

        // Category filter
        chips = LinearLayout(this).apply {
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })

        // List
        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            clipToPadding = false
            setPadding(0, 0, 0, Ui.dp(context, 8))
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> open(shown[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            showActions(shown[pos])
            true
        }
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        empty = Ui.text(this, "", 15f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 32), 0, Ui.dp(context, 32), 0)
        }
        body.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Add button
        val add = Ui.button(this, "+  Add Document")
        add.setOnClickListener { chooseCategoryThenPick() }
        root.addView(add, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)).apply {
            setMargins(Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 16))
        })

        setContentView(root)
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("filter", filter)
        outState.putString("pendingCategory", pendingCategory)
        outState.putString("pendingExportId", pendingExportId)
    }

    // ------------------------------------------------------------------ list

    private fun reload() {
        if (!Vault.unlocked) return
        docs = try {
            Vault.loadIndex(this)
        } catch (e: Exception) {
            toast("Could not read the document list.")
            mutableListOf()
        }
        render()
    }

    private fun render() {
        shown = if (filter == ALL) docs else docs.filter { it.category == filter }
        subtitle.text = if (docs.size == 1) "1 document, encrypted" else "${docs.size} documents, encrypted"
        empty.text = when {
            docs.isEmpty() -> "No documents yet.\n\nTap “Add Document” to keep a photo or PDF of your CNIC, Iqama, passport or certificates safely here."
            shown.isEmpty() -> "No documents in “$filter”."
            else -> ""
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()

        chips.removeAllViews()
        for (c in listOf(ALL) + Vault.categories) {
            val count = if (c == ALL) docs.size else docs.count { it.category == c }
            val on = c == filter
            val chip = Ui.text(this, if (count > 0) "$c  $count" else c, 14f, if (on) Color.WHITE else Ui.TEXT, on).apply {
                background = if (on) Ui.ripple(context, Ui.ACCENT, 18) else Ui.ripple(context, Color.WHITE, 18, Ui.LINE)
                gravity = Gravity.CENTER
                setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0)
                setOnClickListener {
                    filter = c
                    render()
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36)).apply {
                rightMargin = Ui.dp(this@MainActivity, 8)
            })
        }
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ctx = this@MainActivity
            val d = shown[position]
            val isPdf = d.mime == "application/pdf"

            val card = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.round(Color.WHITE, 14, ctx, Ui.LINE)
                setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
            }
            val badge = Ui.text(ctx, if (isPdf) "PDF" else "IMG", 12f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = Ui.round(if (isPdf) Color.parseColor("#B45309") else Ui.ACCENT, 10, ctx)
            }
            card.addView(badge, LinearLayout.LayoutParams(Ui.dp(ctx, 46), Ui.dp(ctx, 46)))

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 14), 0, 0, 0)
            }
            col.addView(Ui.text(ctx, d.name, 16f, Ui.TEXT, true).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            col.addView(Ui.text(ctx, "${d.category}  ·  ${Ui.sizeLabel(d.size)}  ·  ${dateFmt.format(Date(d.added))}", 13f, Ui.MUTED).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // Outer frame gives each card its margins inside the ListView.
            return FrameLayout(ctx).apply {
                setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 5), Ui.dp(ctx, 16), Ui.dp(ctx, 5))
                addView(card)
            }
        }
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Lock now")
            menu.add(0, 2, 1, "Change PIN")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> {
                        Vault.lock(this@MainActivity)
                        bounceIfLocked()
                    }
                    2 -> changePin()
                }
                true
            }
        }.show()
    }

    private fun pinInput(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(12))
    }

    private fun changePin() {
        val a = pinInput("New PIN (at least ${Vault.MIN_PIN} digits)")
        val b = pinInput("Confirm new PIN")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 22), Ui.dp(context, 8), Ui.dp(context, 22), 0)
            addView(a)
            addView(b)
        }
        AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Change") { _, _ ->
                val p = a.text.toString()
                when {
                    p.length < Vault.MIN_PIN -> toast("PIN must be at least ${Vault.MIN_PIN} digits. PIN not changed.")
                    p != b.text.toString() -> toast("The two PINs do not match. PIN not changed.")
                    else -> background("Changing PIN…", { Vault.changePin(applicationContext, p) }) {
                        toast("PIN changed.")
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------------- add

    private fun chooseCategoryThenPick() {
        val items = Vault.categories.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("What kind of document?")
            .setItems(items) { _, which ->
                pendingCategory = items[which]
                Vault.suppressLockUntil = System.currentTimeMillis() + PICKER_GRACE_MS
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                try {
                    startActivityForResult(i, REQ_PICK)
                } catch (e: Exception) {
                    Vault.suppressLockUntil = 0
                    toast("No file picker found on this phone.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || !Vault.unlocked) return
        when (requestCode) {
            REQ_PICK -> importPicked(uri)
            REQ_EXPORT -> exportTo(uri)
        }
    }

    private fun importPicked(uri: Uri) {
        val mime = contentResolver.getType(uri)
        if (!Vault.isAllowed(mime)) {
            toast("Only photos and PDF documents can be kept in the vault.")
            return
        }
        var fileName = "Document"
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) fileName = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (e: Exception) {
        }
        if (size > Vault.MAX_SIZE) {
            toast("This file is too large. The limit is ${Ui.sizeLabel(Vault.MAX_SIZE)}.")
            return
        }
        val category = pendingCategory
        askName("Name this document", fileName.substringBeforeLast('.')) { name ->
            var tooBig = false
            background("Encrypting…", {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw java.io.IOException("could not read file")
                if (bytes.size > Vault.MAX_SIZE) tooBig = true
                else Vault.add(applicationContext, bytes, name, category, mime!!)
            }) {
                if (tooBig) {
                    toast("This file is too large. The limit is ${Ui.sizeLabel(Vault.MAX_SIZE)}.")
                } else {
                    filter = ALL
                    reload()
                    AlertDialog.Builder(this)
                        .setTitle("Saved in the vault")
                        .setMessage("“$name” is now encrypted inside the vault.\n\nThe original file is still in your phone storage or gallery. Delete it there if you want it to exist only in the vault.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    // --------------------------------------------------------------- actions

    private fun open(d: Vault.Doc) {
        startActivity(Intent(this, ViewerActivity::class.java).putExtra("id", d.id))
    }

    private fun showActions(d: Vault.Doc) {
        val items = arrayOf("Open", "Rename", "Change category", "Share", "Save a copy to phone", "Delete")
        AlertDialog.Builder(this)
            .setTitle(d.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> open(d)
                    1 -> askName("Rename document", d.name) { name ->
                        d.name = name
                        saveChange(d)
                    }
                    2 -> {
                        val cats = Vault.categories.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("Move to category")
                            .setItems(cats) { _, w ->
                                d.category = cats[w]
                                saveChange(d)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    3 -> share(d)
                    4 -> startExport(d)
                    5 -> confirmDelete(d)
                }
            }
            .show()
    }

    private fun saveChange(d: Vault.Doc) {
        try {
            Vault.update(this, d)
        } catch (e: Exception) {
            toast("Could not save the change.")
        }
        reload()
    }

    private fun confirmDelete(d: Vault.Doc) {
        AlertDialog.Builder(this)
            .setTitle("Delete “${d.name}”?")
            .setMessage("It will be removed from the vault permanently. This cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    Vault.delete(this, d)
                } catch (e: Exception) {
                    toast("Could not delete the document.")
                }
                reload()
            }
            .show()
    }

    private fun safeFileName(d: Vault.Doc): String {
        val base = d.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "Document" }
        return base + "." + Vault.extensionFor(d.mime)
    }

    private fun share(d: Vault.Doc) {
        AlertDialog.Builder(this)
            .setTitle("Share “${d.name}”?")
            .setMessage("A normal, unencrypted copy will be sent to the app you choose.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Share") { _, _ ->
                var file: File? = null
                background("Preparing…", {
                    val f = File(Vault.tempDir(applicationContext, "share"), safeFileName(d))
                    f.writeBytes(Vault.read(applicationContext, d))
                    file = f
                }) {
                    val uri = ShareProvider.uriFor(file!!)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType(d.mime)
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    send.clipData = ClipData.newRawUri(d.name, uri)
                    Vault.suppressLockUntil = System.currentTimeMillis() + PICKER_GRACE_MS
                    startActivity(Intent.createChooser(send, "Share document"))
                }
            }
            .show()
    }

    private fun startExport(d: Vault.Doc) {
        pendingExportId = d.id
        Vault.suppressLockUntil = System.currentTimeMillis() + PICKER_GRACE_MS
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(d.mime)
            .putExtra(Intent.EXTRA_TITLE, safeFileName(d))
        try {
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: Exception) {
            Vault.suppressLockUntil = 0
            toast("No file picker found on this phone.")
        }
    }

    private fun exportTo(uri: Uri) {
        val d = docs.firstOrNull { it.id == pendingExportId } ?: return
        background("Saving copy…", {
            val bytes = Vault.read(applicationContext, d)
            (contentResolver.openOutputStream(uri) ?: throw java.io.IOException("could not write file")).use { it.write(bytes) }
        }) {
            toast("Unencrypted copy saved to your phone.")
        }
    }

    // --------------------------------------------------------------- helpers

    private fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(InputFilter.LengthFilter(60))
        }
        val box = FrameLayout(this).apply {
            setPadding(Ui.dp(context, 22), Ui.dp(context, 8), Ui.dp(context, 22), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                onOk(input.text.toString().trim().ifEmpty { initial.ifEmpty { "Document" } })
            }
            .show()
    }

    /** Runs [work] off the main thread behind a small blocking dialog, then [done] on success. */
    private fun background(message: String, work: () -> Unit, done: () -> Unit) {
        val dlg = AlertDialog.Builder(this).setMessage(message).setCancelable(false).show()
        Thread {
            var error: Throwable? = null
            try {
                work()
            } catch (e: Throwable) {
                error = e
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    dlg.dismiss()
                } catch (e: Exception) {
                }
                if (error != null) {
                    toast(if (error is OutOfMemoryError) "This file is too large for this phone." else "Something went wrong. Nothing was changed.")
                } else {
                    done()
                }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
