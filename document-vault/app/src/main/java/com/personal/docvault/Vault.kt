package com.personal.docvault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted document store.
 *
 * Every document is encrypted with AES-256-GCM using a random data key.
 * The data key is wrapped twice: first with a key derived from the PIN
 * (PBKDF2), then with a hardware-backed Android Keystore key. Without the
 * PIN the data key cannot be recovered, so the files cannot be read.
 */
object Vault {

    data class Doc(
        val id: String,
        var name: String,
        var category: String,
        val mime: String,
        val size: Long,
        val added: Long,
    )

    val categories = listOf(
        "ID Card / CNIC",
        "Iqama",
        "Passport",
        "Birth Certificate",
        "Driving License",
        "Education",
        "Bank / Finance",
        "Medical",
        "Other",
    )

    const val MAX_SIZE = 30L * 1024 * 1024
    const val MIN_PIN = 4

    private const val PREFS = "vault"
    private const val KS_ALIAS = "docvault_master"
    private const val PBKDF2_ROUNDS = 150_000
    private const val GCM = "AES/GCM/NoPadding"

    /** Data key while unlocked, null while locked. */
    @Volatile
    private var key: ByteArray? = null

    /** While a system picker / share sheet is open the app must not auto-lock. */
    @Volatile
    var suppressLockUntil = 0L

    val unlocked: Boolean get() = key != null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSetup(ctx: Context) = prefs(ctx).contains("blob")

    // ---------------------------------------------------------------- crypto

    private fun encrypt(k: SecretKey, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.ENCRYPT_MODE, k)
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(k: SecretKey, data: ByteArray): ByteArray {
        val c = Cipher.getInstance(GCM)
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KS_ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun pinKey(pin: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ROUNDS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun unb64(s: String) = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    private fun storeWrapped(ctx: Context, pin: String, dataKey: ByteArray) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val inner = encrypt(pinKey(pin, salt), dataKey)
        val outer = encrypt(keystoreKey(), inner)
        prefs(ctx).edit()
            .putString("salt", b64(salt))
            .putString("blob", b64(outer))
            .putInt("fails", 0)
            .putLong("lockUntil", 0)
            .commit()
    }

    // ------------------------------------------------------------ PIN / lock

    /** Slow (key derivation) - call off the main thread. */
    fun setup(ctx: Context, pin: String) {
        val dataKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        storeWrapped(ctx, pin, dataKey)
        key = dataKey
        saveIndex(ctx, emptyList())
    }

    /** Milliseconds the user still has to wait after too many wrong PINs. */
    fun lockoutRemaining(ctx: Context): Long =
        (prefs(ctx).getLong("lockUntil", 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /** Slow (key derivation) - call off the main thread. Returns false on a wrong PIN. */
    fun unlock(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        if (lockoutRemaining(ctx) > 0) return false
        val salt = unb64(p.getString("salt", "")!!)
        val outer = unb64(p.getString("blob", "")!!)
        return try {
            val inner = decrypt(keystoreKey(), outer)
            key = decrypt(pinKey(pin, salt), inner)
            p.edit().putInt("fails", 0).putLong("lockUntil", 0).apply()
            true
        } catch (e: GeneralSecurityException) {
            val fails = p.getInt("fails", 0) + 1
            val e2 = p.edit().putInt("fails", fails)
            if (fails >= 5) {
                val wait = (30_000L * (fails - 4)).coerceAtMost(300_000L)
                e2.putLong("lockUntil", System.currentTimeMillis() + wait)
            }
            e2.commit()
            false
        }
    }

    /** Slow - call off the main thread. The vault must be unlocked. */
    fun changePin(ctx: Context, newPin: String) {
        storeWrapped(ctx, newPin, requireKey())
    }

    fun lock(ctx: Context) {
        key = null
        clearTemp(ctx)
    }

    fun clearTemp(ctx: Context) {
        tempDir(ctx, "share").listFiles()?.forEach { it.delete() }
        tempDir(ctx, "view").listFiles()?.forEach { it.delete() }
    }

    fun tempDir(ctx: Context, name: String) = File(ctx.cacheDir, name).apply { mkdirs() }

    private fun requireKey(): ByteArray = key ?: throw IllegalStateException("Vault is locked")
    private fun dataKey(): SecretKey = SecretKeySpec(requireKey(), "AES")

    // ------------------------------------------------------------- documents

    private fun docsDir(ctx: Context) = File(ctx.filesDir, "docs").apply { mkdirs() }
    private fun indexFile(ctx: Context) = File(ctx.filesDir, "index.bin")

    private fun writeAtomic(f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw java.io.IOException("Could not save " + f.name)
        }
    }

    @Synchronized
    fun loadIndex(ctx: Context): MutableList<Doc> {
        val f = indexFile(ctx)
        if (!f.exists()) return mutableListOf()
        val arr = JSONArray(String(decrypt(dataKey(), f.readBytes()), Charsets.UTF_8))
        val out = mutableListOf<Doc>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Doc(
                o.getString("id"), o.getString("name"), o.getString("category"),
                o.getString("mime"), o.getLong("size"), o.getLong("added"),
            )
        }
        return out
    }

    @Synchronized
    fun saveIndex(ctx: Context, docs: List<Doc>) {
        val arr = JSONArray()
        for (d in docs) {
            arr.put(
                JSONObject().put("id", d.id).put("name", d.name).put("category", d.category)
                    .put("mime", d.mime).put("size", d.size).put("added", d.added)
            )
        }
        writeAtomic(indexFile(ctx), encrypt(dataKey(), arr.toString().toByteArray(Charsets.UTF_8)))
    }

    @Synchronized
    fun add(ctx: Context, bytes: ByteArray, name: String, category: String, mime: String): Doc {
        val doc = Doc(UUID.randomUUID().toString(), name, category, mime, bytes.size.toLong(), System.currentTimeMillis())
        writeAtomic(File(docsDir(ctx), doc.id), encrypt(dataKey(), bytes))
        val list = loadIndex(ctx)
        list.add(0, doc)
        saveIndex(ctx, list)
        return doc
    }

    fun read(ctx: Context, doc: Doc): ByteArray = decrypt(dataKey(), File(docsDir(ctx), doc.id).readBytes())

    @Synchronized
    fun update(ctx: Context, doc: Doc) {
        val list = loadIndex(ctx)
        val i = list.indexOfFirst { it.id == doc.id }
        if (i >= 0) {
            list[i] = doc
            saveIndex(ctx, list)
        }
    }

    @Synchronized
    fun delete(ctx: Context, doc: Doc) {
        val list = loadIndex(ctx)
        list.removeAll { it.id == doc.id }
        saveIndex(ctx, list)
        File(docsDir(ctx), doc.id).delete()
    }

    fun extensionFor(mime: String): String = when (mime) {
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }

    fun isAllowed(mime: String?): Boolean =
        mime != null && (mime == "application/pdf" || mime.startsWith("image/"))
}
