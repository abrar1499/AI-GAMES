package com.boltamunshi.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from inside the app. Each release puts the APK and a small `latest.json`
 * (version number, name, what's new, the APK's address) in the repository's
 * `bolta-munshi/releases` folder; the app reads it, and when it is newer than itself
 * shows a small card. "Update" downloads the APK and opens Android's installer. The APK
 * is signed with the same key, so it installs over this one and keeps all data.
 */
class Updater(private val context: Context) {
    class Release(val code: Int, val name: String, val notes: String, val apk: String)

    fun currentCode(): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    /** The newer release, or null when this is the newest (or the list cannot be read now). */
    fun check(): Release? = try {
        val c = URL("$LATEST?t=" + System.currentTimeMillis() / 60000).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Cache-Control", "no-cache")
        if (c.responseCode != 200) null
        else {
            val o = JSONObject(c.inputStream.use { String(it.readBytes()) })
            val r = Release(o.optInt("versionCode"), o.optString("versionName"), o.optString("notes"), o.optString("apk"))
            if (r.code > currentCode() && r.apk.startsWith("https://")) r else null
        }
    } catch (e: Exception) {
        null
    }

    /** Starts downloading the APK into the app's own Downloads folder; returns the download's id. */
    fun download(r: Release): Long {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        dir?.listFiles()?.filter { it.name.startsWith("smart-book-") && it.name.endsWith(".apk") }?.forEach { it.delete() }
        val req = DownloadManager.Request(Uri.parse(r.apk))
            .setTitle("Smart Book ${r.name}")
            .setDescription("Update")
            .setMimeType(APK)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "smart-book-${r.name}.apk")
        return manager().enqueue(req)
    }

    /** Percent done (0-100), 100 when finished, -1 when it failed. */
    fun progress(id: Long): Int {
        manager().query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return -1
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_SUCCESSFUL) return 100
            if (status == DownloadManager.STATUS_FAILED) return -1
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return if (total > 0) (done * 99 / total).toInt() else 0
        }
    }

    /** Android 8+ asks once whether Smart Book may install apps. */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun allowIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    fun installIntent(id: Long): Intent? {
        val uri = manager().getUriForDownloadedFile(id) ?: return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun manager() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    companion object {
        const val LATEST = "https://raw.githubusercontent.com/abrar1499/AI-GAMES/claude/new-session-fb21rr/bolta-munshi/releases/latest.json"
        const val APK = "application/vnd.android.package-archive"
    }
}
