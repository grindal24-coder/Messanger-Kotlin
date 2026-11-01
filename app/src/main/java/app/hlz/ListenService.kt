package app.hlz

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Держит соединение с Supabase Realtime и показывает уведомления (без Firebase/Google).
// Дополнительно раз в ~15 секунд забирает очередь notif_queue через take_notifs:
// если Realtime молчит, уведомление всё равно придёт (с задержкой до ~15 c).
class ListenService : Service() {
    companion object {
        @Volatile var running = false     // служба работает
        @Volatile var connected = false   // канал подтверждён сервером
        @Volatile var lastInbound = 0L    // когда последний раз что-то пришло
        @Volatile var lastError = ""

        fun startIfNeeded(c: Context) {
            val k = c.getSharedPreferences("hlz", 0).getString("notifKey", "") ?: ""
            if (k.isNotEmpty()) {
                try { ContextCompat.startForegroundService(c, Intent(c, ListenService::class.java)) } catch (_: Exception) {}
            }
        }

        // разовая проверка очереди — используется и службой, и страховочным заданием PollJob
        fun pollOnce(c: Context, quiet: Boolean = false) {
            val key = c.getSharedPreferences("hlz", 0).getString("notifKey", "") ?: ""
            if (key.isEmpty()) return
            if (MainActivity.visible) return // приложение на экране — всё делает страница
            try {
                val body = JSONObject().put("k", key).toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url(Config.SUPABASE_URL + "/rest/v1/rpc/take_notifs")
                    .addHeader("apikey", Config.SUPABASE_ANON)
                    .addHeader("Authorization", "Bearer " + Config.SUPABASE_ANON)
                    .post(body).build()
                OkHttpClient().newCall(req).execute().use { r ->
                    val txt = r.body?.string() ?: ""
                    if (!r.isSuccessful) {
                        lastError = "очередь: HTTP ${r.code}"
                        writeLog(c, lastError)
                        return
                    }
                    val arr = JSONArray(txt)
                    for (i in 0 until arr.length()) {
                        val d = arr.getJSONObject(i)
                        // звонки через очередь не показываем: устаревший звонок опаснее, чем пропущенный
                        if (d.optBoolean("is_call", false)) continue
                        val chat = d.optString("chat", "")
                        Notif.show(
                            c, d.optString("title", "HLZ"), d.optString("body", ""),
                            if (chat.isEmpty() || chat == "null") null else chat,
                            d.optString("tag", "m"), false
                        )
                    }
                }
            } catch (e: Exception) {
                lastError = "очередь: ${e.message}"
                writeLog(c, lastError!!)
            }
        }

        fun writeLog(c: Context, s: String) {
            try {
                val sp = c.getSharedPreferences("hlz", 0)
                val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
                val cur = sp.getString("svcLog", "") ?: ""
                sp.edit().putString("svcLog", ((if (cur.isEmpty()) "" else "$cur\n") + "$t $s").takeLast(2000)).apply()
            } catch (_: Exception) {}
        }
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val api = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val h = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var open = false
    private var alive = false
    private var backoff = 2000L
    private var wl: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private val seen = LinkedHashSet<Long>()
    private var pollAt = 0L
    private var joinRef = 0
    private var pollFails = 0

    private fun key(): String = getSharedPreferences("hlz", 0).getString("notifKey", "") ?: ""

    // журнал для экрана «Проверить уведомления»
    private fun log(s: String) {
        try {
            val sp = getSharedPreferences("hlz", 0)
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val cur = sp.getString("svcLog", "") ?: ""
            val next = ((if (cur.isEmpty()) "" else "$cur\n") + "$t $s").takeLast(1500)
            sp.edit().putString("svcLog", next).apply()
        } catch (_: Exception) {}
    }

    private val reconnect = Runnable { connect() }
    private val beat = object : Runnable {
        override fun run() {
            ws?.send("""{"topic":"phoenix","event":"heartbeat","payload":{},"ref":"hb"}""")
            h.postDelayed(this, 25000)
        }
    }

    // каждые 15 c: проверяем, живо ли соединение, и забираем очередь
    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            if (open && System.currentTimeMillis() - lastInbound > 90000) {
                log("нет ответов 90 c — переподключаюсь")
                connected = false
                try { ws?.cancel() } catch (_: Exception) {}
                lost()
            }
            poll()
            h.postDelayed(this, 15000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val k = key()
        if (k.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        showForeground()
        running = true
        if (!alive) {
            alive = true
            log("служба запущена (ключ ${k.length} симв.)")
            if (Config.KEEP_AWAKE) {
                wl = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hlz:listen").apply { acquire() }
            }
            val cm = getSystemService(ConnectivityManager::class.java)
            netCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!open) h.post { backoff = 2000L; connect() }
                }
            }
            try { cm.registerDefaultNetworkCallback(netCb!!) } catch (_: Exception) {}
            connect()
            h.postDelayed(tick, 15000)
        }
        return START_STICKY
    }

    private fun showForeground() {
        Notif.channels(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "listen")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("HLZ на связи")
            .setContentText("Приём сообщений и звонков")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(3, n)
        }
    }

    private fun connect() {
        if (!alive) return
        val k = key()
        if (k.isEmpty()) { stopSelf(); return }
        h.removeCallbacks(reconnect)
        try { ws?.cancel() } catch (_: Exception) {}
        open = false
        connected = false
        val url = Config.SUPABASE_URL.replace("https://", "wss://") +
            "/realtime/v1/websocket?apikey=" + Config.SUPABASE_ANON + "&vsn=1.0.0"
        log("подключаюсь к каналу")
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                backoff = 2000L
                lastInbound = System.currentTimeMillis()
                joinRef++
                val cfg = JSONObject()
                    .put("broadcast", JSONObject().put("self", false).put("ack", false))
                    .put("presence", JSONObject().put("key", ""))
                    .put("postgres_changes", JSONArray())
                    .put("private", false)
                webSocket.send(
                    JSONObject().put("topic", "realtime:notif-$k").put("event", "phx_join")
                        .put("payload", JSONObject().put("config", cfg))
                        .put("ref", joinRef.toString()).put("join_ref", joinRef.toString()).toString()
                )
                log("соединение открыто, вход в канал")
                h.removeCallbacks(beat)
                h.postDelayed(beat, 25000)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInbound = System.currentTimeMillis()
                try {
                    val j = JSONObject(text)
                    val ev = j.optString("event")
                    if (ev == "phx_reply") {
                        val st = j.optJSONObject("payload")?.optString("status") ?: ""
                        if (st == "ok") {
                            connected = true
                            lastError = ""
                            log("канал подтверждён сервером")
                        } else {
                            val why = j.optJSONObject("payload")?.optJSONObject("response")?.toString() ?: st
                            lastError = "канал: $why"
                            log("канал отклонён: ${why.take(140)}")
                        }
                        return
                    }
                    if (ev == "system") {
                        log("канал: сервер прислал system")
                        return
                    }
                    if (ev != "broadcast") return
                    val p = j.getJSONObject("payload")
                    if (p.optString("event") != "n") return
                    connected = true
                    deliver(p.getJSONObject("payload"))
                } catch (e: Exception) {
                    lastError = "разбор: ${e.message}"
                    log("ошибка разбора ответа")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastError = "сеть: ${t.message ?: t.javaClass.simpleName}"
                log("обрыв соединения: ${(t.message ?: t.javaClass.simpleName).take(120)}")
                lost()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log("соединение закрыто ($code)")
                lost()
            }
        })
    }

    private fun lost() {
        open = false
        connected = false
        h.removeCallbacks(beat)
        if (!alive) return
        h.removeCallbacks(reconnect)
        h.postDelayed(reconnect, backoff)
        backoff = minOf(backoff * 2, 60000L)
    }

    // показать уведомление (одно и то же не показываем дважды: Realtime + очередь)
    private fun deliver(d: JSONObject) {
        fun str(k: String, def: String): String {
            val v = d.optString(k, "")
            return if (v.isEmpty() || v == "null") def else v
        }
        val qid = d.optLong("qid", 0L)
        if (qid > 0L && !seen.add(qid)) return
        while (seen.size > 300) {
            val it = seen.iterator()
            if (it.hasNext()) { it.next(); it.remove() } else break
        }
        val call = d.optBoolean("call", false) || d.optBoolean("is_call", false) || str("call", "0") == "1"
        val title = str("title", "HLZ")
        val body = str("body", "")
        val chat = str("chat", "").ifEmpty { null }
        val tag = str("tag", "m")
        // приложение на экране — всё показывает и озвучивает сама страница (кроме теста из настроек)
        if (MainActivity.visible && tag != "test") return
        log("уведомление: $title${if (call) " (звонок)" else ""}")
        h.post { Notif.show(this, title, body, chat, tag, call) }
    }

    // страховка: забрать очередь с сервера (take_notifs), если Realtime не доставил
    private fun poll() {
        if (!alive) return
        val k = key()
        if (k.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - pollAt < 15000) return
        pollAt = now
        Thread {
            try {
                val body = JSONObject().put("k", k).toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder()
                    .url(Config.SUPABASE_URL + "/rest/v1/rpc/take_notifs")
                    .addHeader("apikey", Config.SUPABASE_ANON)
                    .addHeader("Authorization", "Bearer " + Config.SUPABASE_ANON)
                    .post(body)
                    .build()
                api.newCall(req).execute().use { r ->
                    val txt = r.body?.string() ?: ""
                    if (!r.isSuccessful) {
                        if (r.code != 404 && pollFails < 3) {
                            pollFails++
                            lastError = "очередь: HTTP ${r.code}"
                            log(lastError)
                        }
                        return@use
                    }
                    pollFails = 0
                    val arr = JSONArray(txt)
                    for (i in 0 until arr.length()) deliver(arr.getJSONObject(i))
                }
            } catch (e: Exception) {
                if (pollFails < 3) {
                    pollFails++
                    lastError = "очередь: ${e.message}"
                    log(lastError ?: "ошибка очереди")
                }
            }
        }.start()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        log("приложение убрали из списка задач — служба остаётся")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        alive = false
        running = false
        connected = false
        log("служба остановлена")
        h.removeCallbacksAndMessages(null)
        try { ws?.cancel() } catch (_: Exception) {}
        try { netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
