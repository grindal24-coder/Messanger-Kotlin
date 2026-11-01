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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Держит соединение с Supabase Realtime и показывает уведомления (без Firebase/Google).
// Сервер шлёт уведомление в личный канал "notif-<ключ>", ключ знает только это приложение.
class ListenService : Service() {
    companion object {
        fun startIfNeeded(c: Context) {
            val k = c.getSharedPreferences("hlz", 0).getString("notifKey", "") ?: ""
            if (k.isNotEmpty()) {
                try { ContextCompat.startForegroundService(c, Intent(c, ListenService::class.java)) } catch (_: Exception) {}
            }
        }
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private val h = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var open = false
    private var alive = false
    private var backoff = 2000L
    private var wl: PowerManager.WakeLock? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private val reconnect = Runnable { connect() }
    private val beat = object : Runnable {
        override fun run() {
            ws?.send("""{"topic":"phoenix","event":"heartbeat","payload":{},"ref":"hb"}""")
            h.postDelayed(this, 25000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val key = getSharedPreferences("hlz", 0).getString("notifKey", "") ?: ""
        if (key.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        showForeground()
        if (!alive) {
            alive = true
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
        val key = getSharedPreferences("hlz", 0).getString("notifKey", "") ?: ""
        if (key.isEmpty()) { stopSelf(); return }
        h.removeCallbacks(reconnect)
        ws?.cancel()
        val url = Config.SUPABASE_URL.replace("https://", "wss://") +
            "/realtime/v1/websocket?apikey=" + Config.SUPABASE_ANON + "&vsn=1.0.0"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                open = true
                backoff = 2000L
                val cfg = JSONObject()
                    .put("broadcast", JSONObject().put("self", false).put("ack", false))
                    .put("presence", JSONObject().put("key", ""))
                    .put("private", false)
                webSocket.send(
                    JSONObject().put("topic", "realtime:notif-$key").put("event", "phx_join")
                        .put("payload", JSONObject().put("config", cfg))
                        .put("ref", "1").put("join_ref", "1").toString()
                )
                h.removeCallbacks(beat)
                h.postDelayed(beat, 25000)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val j = JSONObject(text)
                    if (j.optString("event") != "broadcast") return
                    val p = j.getJSONObject("payload")
                    if (p.optString("event") != "n") return
                    val d = p.getJSONObject("payload")
                    val call = d.optString("call") == "1"
                    if (MainActivity.visible && !call) return // приложение открыто — оно само всё показывает
                    Notif.show(
                        this@ListenService, d.optString("title", "HLZ"), d.optString("body", ""),
                        d.optString("chat", "").ifEmpty { null }, d.optString("tag", "m"), call
                    )
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost()
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost()
        })
    }

    private fun lost() {
        open = false
        h.removeCallbacks(beat)
        if (!alive) return
        h.removeCallbacks(reconnect)
        h.postDelayed(reconnect, backoff)
        backoff = minOf(backoff * 2, 60000L)
    }

    override fun onDestroy() {
        alive = false
        h.removeCallbacksAndMessages(null)
        ws?.cancel()
        try { netCb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        wl?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
