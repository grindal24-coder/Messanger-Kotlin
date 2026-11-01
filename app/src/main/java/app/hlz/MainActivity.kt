package app.hlz

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Rational
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    companion object {
        @Volatile var visible = false
    }

    lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var inCall = false

    private val filePick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askBattery()
        askFullScreen()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockMode(intent)
        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            userAgentString = userAgentString + " HLZApp/" + BuildConfig.VERSION_NAME
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.addJavascriptInterface(Bridge(this), "HLZ")

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onShowFileChooser(
                w: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    filePick.launch(params.createIntent()); true
                } catch (e: Exception) {
                    fileCb = null; false
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val own = Uri.parse(Config.APP_URL).host
                if (u.host == own || u.scheme == "blob" || u.scheme == "about") return false
                return try { startActivity(Intent(Intent.ACTION_VIEW, u)); true } catch (e: Exception) { true }
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            web.evaluateJavascript("window.hlzBack&&hlzBack()") { r ->
                if (r != "true") moveTaskToBack(true)
            }
        }

        askPerms()
        ListenService.startIfNeeded(this) // если ключ уведомлений уже есть, служба приёма запускается сразу

        val chat = intent.getStringExtra("chat")
        web.loadUrl(Config.APP_URL + if (chat != null) "#c=$chat" else "")
        Updater.check(this, manual = false)
    }

    // если открыли из уведомления о входящем звонке — показываем приложение поверх экрана блокировки и включаем экран
    private fun lockMode(i: Intent) {
        val call = i.getBooleanExtra("call", false)
        setShowWhenLocked(call)
        setTurnScreenOn(call)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lockMode(intent)
        intent.getStringExtra("chat")?.let { c ->
            val safe = c.filter { it.isLetterOrDigit() || it == '-' }
            web.evaluateJavascript("window.hlzOpen&&hlzOpen('$safe')", null)
        }
    }

    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() {
        super.onStop(); visible = false
        setShowWhenLocked(false); setTurnScreenOn(false) // обычный режим: на экране блокировки приложение не показывается
    }

    private fun askPerms() {
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val miss = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isNotEmpty()) permLauncher.launch(miss.toTypedArray()) else { askBattery(); askFullScreen() }
    }

    // один раз просим не ограничивать приложение в фоне (иначе оболочки вроде MIUI «убивают» уведомления)
    private fun askBattery() {
        val sp = getSharedPreferences("hlz", 0)
        if (sp.getBoolean("batAsked", false)) return
        sp.edit().putBoolean("batAsked", true).apply()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    // Android 14+ не даёт показывать звонок на весь экран без отдельного разрешения:
    // без него входящий вызов придёт обычным уведомлением. Подсказываем один раз.
    private fun askFullScreen() {
        if (Build.VERSION.SDK_INT < 34) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.canUseFullScreenIntent()) return
        val sp = getSharedPreferences("hlz", 0)
        if (sp.getBoolean("fsAsked", false)) return
        sp.edit().putBoolean("fsAsked", true).apply()
        AlertDialog.Builder(this)
            .setTitle("Звонки на весь экран")
            .setMessage("Разрешите «Полноэкранные уведомления», иначе входящий звонок придёт обычным уведомлением, а не поверх экрана блокировки.")
            .setPositiveButton("Разрешить") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    // ---- звонок: служба переднего плана держит микрофон/камеру при погашенном экране; окно-картинка при выходе ----
    fun setCall(on: Boolean) {
        inCall = on
        val i = Intent(this, CallService::class.java)
        try {
            if (on) ContextCompat.startForegroundService(this, i) else stopService(i)
        } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                setPictureInPictureParams(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).setAutoEnterEnabled(on).build()
                )
            } catch (_: Exception) {}
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (inCall && !isInPictureInPictureMode) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build())
            } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        web.evaluateJavascript("window.hlzPip&&hlzPip($isInPictureInPictureMode)", null)
    }

    fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---- мост для страницы: window.HLZ ----
    class Bridge(private val a: MainActivity) {
        @JavascriptInterface fun call(on: Boolean) { a.runOnUiThread { a.setCall(on) } }
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun checkUpdate() { a.runOnUiThread { Updater.check(a, manual = true) } }
        // страница передаёт личный ключ канала уведомлений (пустой — выключить приём)
        @JavascriptInterface fun notifKey(k: String) {
            a.getSharedPreferences("hlz", 0).edit().putString("notifKey", k).apply()
            a.runOnUiThread {
                if (k.isEmpty()) a.stopService(Intent(a, ListenService::class.java)) else ListenService.startIfNeeded(a)
            }
        }

        // сохранение файла в «Загрузки»
        @JavascriptInterface fun save(b64: String, name: String, mime: String) {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                a.contentResolver.openOutputStream(uri)!!.use { it.write(Base64.decode(b64, Base64.DEFAULT)) }
                a.runOnUiThread { a.toast("Сохранено в «Загрузки»: $name") }
            } catch (e: Exception) {
                a.runOnUiThread { a.toast("Не удалось сохранить файл") }
            }
        }
    }
}
