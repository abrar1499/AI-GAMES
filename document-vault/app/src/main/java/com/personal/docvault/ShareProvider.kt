package com.personal.docvault

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only access to the temporary decrypted copy made when the user taps
 * "Share". Not exported: another app can read a file only through the
 * one-time permission granted by the share intent.
 */
class ShareProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.personal.docvault.share"
        fun uriFor(file: File): Uri =
            Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.name).build()
    }

    private fun fileFor(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val dir = Vault.tempDir(context!!, "share")
        val f = File(dir, name)
        if (f.parentFile?.canonicalPath != dir.canonicalPath || !f.isFile) throw FileNotFoundException(name)
        return f
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? {
        val ext = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun query(uri: Uri, projection: Array<String>?, sel: String?, args: Array<String>?, sort: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        })
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<String>?) = 0
}
