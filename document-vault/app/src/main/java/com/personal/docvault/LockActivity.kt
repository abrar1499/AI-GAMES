package com.personal.docvault

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** First screen: create a PIN on first run, otherwise ask for it. */
class LockActivity : SecureActivity() {

    override val needsUnlock = false

    private lateinit var pin: EditText
    private lateinit var confirm: EditText
    private lateinit var error: TextView
    private lateinit var action: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Vault.unlocked) {
            openVault()
            return
        }
        val setup = !Vault.isSetup(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(context, 28), Ui.dp(context, 56), Ui.dp(context, 28), Ui.dp(context, 28))
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            background = Ui.round(Ui.PRIMARY, 24, context)
        }
        col.addView(logo, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)))

        col.addView(Ui.text(this, "Document Vault", 26f, Ui.TEXT, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 18), 0, Ui.dp(context, 6))
        })
        col.addView(Ui.text(
            this,
            if (setup) "Create a PIN to protect your personal documents" else "Enter your PIN to open your documents",
            15f, Ui.MUTED,
        ).apply { gravity = Gravity.CENTER })

        pin = pinField(if (setup) "New PIN (at least ${Vault.MIN_PIN} digits)" else "PIN")
        col.addView(pin, fieldParams(28))

        confirm = pinField("Confirm PIN")
        confirm.visibility = if (setup) View.VISIBLE else View.GONE
        col.addView(confirm, fieldParams(12))

        error = Ui.text(this, "", 14f, Ui.DANGER).apply {
            gravity = Gravity.CENTER
            setPadding(0, Ui.dp(context, 12), 0, 0)
        }
        col.addView(error)

        action = Ui.button(this, if (setup) "Create Vault" else "Unlock")
        action.setOnClickListener { submit(setup) }
        col.addView(action, fieldParams(16))

        if (setup) {
            col.addView(Ui.text(
                this,
                "Remember this PIN. Your documents are encrypted with it and cannot be recovered if you forget it.",
                13f, Ui.MUTED,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(context, 20), 0, 0)
            })
        }

        (if (setup) confirm else pin).setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                submit(setup)
                true
            } else false
        }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(col)
        })
        pin.requestFocus()
        showLockout()
    }

    private fun pinField(hintText: String) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(12))
        gravity = Gravity.CENTER
        textSize = 20f
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.MUTED)
        imeOptions = EditorInfo.IME_ACTION_DONE
        background = Ui.round(android.graphics.Color.WHITE, 12, context, Ui.LINE)
        setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0)
        minHeight = Ui.dp(context, 56)
    }

    private fun fieldParams(topDp: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = Ui.dp(this@LockActivity, topDp) }

    /** Shows the wait time after too many wrong PINs; returns true while locked out. */
    private fun showLockout(): Boolean {
        val left = Vault.lockoutRemaining(this)
        if (left <= 0) return false
        error.text = "Too many wrong attempts. Try again in ${(left + 999) / 1000} seconds."
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!showLockout() && !busy) error.text = ""
        }, 1000)
        return true
    }

    private fun submit(setup: Boolean) {
        if (busy) return
        val p = pin.text.toString()
        if (setup) {
            if (p.length < Vault.MIN_PIN) {
                error.text = "PIN must be at least ${Vault.MIN_PIN} digits."
                return
            }
            if (p != confirm.text.toString()) {
                error.text = "The two PINs do not match."
                return
            }
        } else {
            if (showLockout()) return
            if (p.isEmpty()) return
        }
        busy = true
        error.text = ""
        action.text = if (setup) "Creating…" else "Unlocking…"
        action.alpha = 0.6f
        Thread {
            var ok = false
            var failure: String? = null
            try {
                if (setup) {
                    Vault.setup(applicationContext, p)
                    ok = true
                } else {
                    ok = Vault.unlock(applicationContext, p)
                }
            } catch (e: Exception) {
                failure = "Could not open the vault: ${e.message}"
            }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) {
                    openVault()
                } else {
                    action.text = if (setup) "Create Vault" else "Unlock"
                    action.alpha = 1f
                    pin.setText("")
                    if (!showLockout()) error.text = failure ?: "Wrong PIN. Try again."
                }
            }
        }.start()
    }

    private fun openVault() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
