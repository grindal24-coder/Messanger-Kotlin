package app.hlz

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper

// Звонит телефон, пока не ответили или не отклонили. Работает и когда приложение закрыто,
// потому что запускается из службы приёма, а не из страницы.
object Ring {
    private var mp: MediaPlayer? = null
    private val h = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stop() }

    fun start(c: Context) {
        stop()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val p = MediaPlayer()
            p.setDataSource(c, uri)
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            p.isLooping = true
            p.prepare()
            p.start()
            mp = p
            h.postDelayed(timeout, 35000)
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        h.removeCallbacks(timeout)
        val p = mp ?: return
        mp = null
        try { if (p.isPlaying) p.stop() } catch (_: Exception) {}
        try { p.release() } catch (_: Exception) {}
    }
}
