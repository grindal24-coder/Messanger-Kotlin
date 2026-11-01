package app.hlz

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Проверка обновлений: берёт последний релиз из GitHub (Releases), сравнивает с номером версии приложения,
// скачивает APK и открывает установщик Android.
object Updater {
    fun check(a: MainActivity, manual: Boolean) {
        Thread {
            try {
                val c = URL("https://api.github.com/repos/${Config.UPDATE_REPO}/releases/latest")
                    .openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10000; c.readTimeout = 10000
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val tag = j.getString("tag_name")
                val notes = j.optString("body", "")
                var apk: String? = null
                val assets = j.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val s = assets.getJSONObject(i)
                    if (s.getString("name").endsWith(".apk")) apk = s.getString("browser_download_url")
                }
                val newer = isNewer(tag, BuildConfig.VERSION_NAME)
                a.runOnUiThread {
                    if (!newer || apk == null) {
                        if (manual) a.toast("Установлена последняя версия (${BuildConfig.VERSION_NAME})")
                    } else {
                        AlertDialog.Builder(a)
                            .setTitle("Доступна версия $tag")
                            .setMessage(notes.take(500).ifBlank { "Установить обновление?" })
                            .setPositiveButton("Обновить") { _, _ -> download(a, apk) }
                            .setNegativeButton("Позже", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                if (manual) a.runOnUiThread { a.toast("Не удалось проверить обновление") }
            }
        }.start()
    }

    private fun nums(s: String) = Regex("\\d+").findAll(s).map { it.value.toInt() }.toList()

    private fun isNewer(n: String, o: String): Boolean {
        val x = nums(n); val y = nums(o)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    private fun download(a: MainActivity, url: String) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            a.toast("Разрешите установку приложений из этого источника и нажмите «Обновить» ещё раз")
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return
        }
        val f = File(a.getExternalFilesDir("update"), "hlz.apk")
        f.delete()
        val dm = a.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(url))
                .setTitle("HLZ — обновление")
                .setDestinationUri(Uri.fromFile(f))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        )
        a.toast("Скачиваю обновление…")
        val rec = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == id) {
                    try { a.unregisterReceiver(this) } catch (_: Exception) {}
                    install(a, f)
                }
            }
        }
        ContextCompat.registerReceiver(a, rec, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun install(a: MainActivity, f: File) {
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", f)
        a.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
