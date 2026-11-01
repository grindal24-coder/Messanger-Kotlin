package app.hlz

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notif {
    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("msg", "Сообщения", NotificationManager.IMPORTANCE_HIGH))
        val call = NotificationChannel("call", "Входящие звонки", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                Settings.System.DEFAULT_RINGTONE_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(call)
        nm.createNotificationChannel(NotificationChannel("ongoing", "Идёт звонок", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("listen", "Связь с сервером", NotificationManager.IMPORTANCE_MIN))
    }

    // tag у каждого автора свой — уведомления от разных людей не склеиваются
    fun show(c: Context, title: String, body: String, chat: String?, tag: String, call: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        channels(c)
        val i = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("chat", chat).putExtra("call", call)
        val pi = PendingIntent.getActivity(c, tag.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(c, if (call) "call" else "msg")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFF3B82F6.toInt())
        if (call) {
            b.setCategory(NotificationCompat.CATEGORY_CALL).setFullScreenIntent(pi, true).setTimeoutAfter(30000)
                .addAction(R.drawable.ic_stat, "Принять", pi)
        } else {
            b.setCategory(NotificationCompat.CATEGORY_MESSAGE)
        }
        NotificationManagerCompat.from(c).notify(tag, if (call) 2 else 1, b.build())
    }
}
