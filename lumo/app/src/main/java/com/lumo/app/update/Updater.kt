package com.lumo.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.lumo.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * In-app updates from GitHub Releases. The build workflow publishes each version as
 * release "lumo-<versionCode>" with one APK per phone type. Updates are signed with the
 * same key, so they install over the app and keep the login and chats.
 */
object Updater {
    data class Release(val code: Long, val name: String, val url: String)

    val available = MutableStateFlow<Release?>(null)
    /** null when idle, 0..1 while downloading. */
    val progress = MutableStateFlow<Float?>(null)
    val error = MutableStateFlow<String?>(null)

    suspend fun check() = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = json.getString("tag_name").removePrefix("lumo-").toLong()
            if (code <= BuildConfig.VERSION_CODE) { available.value = null; return@runCatching }
            val assets = json.getJSONArray("assets")
            val urls = (0 until assets.length()).associate {
                val a = assets.getJSONObject(it)
                a.getString("name") to a.getString("browser_download_url")
            }
            val url = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { urls["lumo-$it.apk"] }
                ?: urls["lumo-universal.apk"] ?: return@runCatching
            available.value = Release(code, json.optString("name").removePrefix("Lumo ").ifBlank { "$code" }, url)
        }
        Unit
    }

    suspend fun install(context: Context) {
        val release = available.value ?: return
        if (progress.value != null) return
        error.value = null
        // Android asks once: "Allow Lumo to install updates?"
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            error.value = "Allow Lumo to install updates, then tap Update again."
            return
        }
        progress.value = 0f
        try {
            withContext(Dispatchers.IO) { downloadAndCommit(context, release.url) }
        } catch (e: Exception) {
            error.value = "Update failed: ${e.localizedMessage ?: "check your connection"}"
        } finally {
            progress.value = null
        }
    }

    private fun downloadAndCommit(context: Context, url: String) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            conn.inputStream.use { input ->
                session.openWrite("lumo.apk", 0, total).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress.value = done.toFloat() / total
                    }
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, sessionId, Intent(context, InstallReceiver::class.java), flags)
            session.commit(pi.intentSender)
        }
    }
}

/** Shows Android's "Do you want to update this app?" screen, or reports a failure. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
            else -> Updater.error.value =
                "Update failed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error")
        }
    }
}
