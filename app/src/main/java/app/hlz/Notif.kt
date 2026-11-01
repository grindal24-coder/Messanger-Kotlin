package app.hlz

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notif {
    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("msg", "Сообщения", NotificationManager.IMPORTANCE_HIGH))
        // звук звонка играет Ring (в цикле), канал только для баннера — иначе звук был бы двойным
        val call = NotificationChannel("call2", "Входящие звонки", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(call)
        nm.createNotificationChannel(NotificationChannel("ongoing", "Идёт звонок", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("listen", "Связь с сервером", NotificationManager.IMPORTANCE_MIN))
    }

    // убрать уведомление входящего звонка (после ответа, отказа или открытия приложения)
    fun cancelCall(c: Context) {
        try { NotificationManagerCompat.from(c).cancel("call", 2) } catch (_: Exception) {}
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
        val b = NotificationCompat.Builder(c, if (call) "call2" else "msg")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFF3B82F6.toInt())
        if (call) {
            Ring.start(c) // звонит телефон, а не одно короткое уведомление
            // «Принять» — открыть приложение и сразу подключиться к звонку
            val acc = Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("chat", chat).putExtra("call", true).putExtra("accept", true)
            val api = PendingIntent.getActivity(
                c, tag.hashCode() + 2, acc,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // «Отклонить» — открывает приложение и сразу отклоняет вызов
            val dec = Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("chat", chat).putExtra("call", true).putExtra("decline", true)
            val dpi = PendingIntent.getActivity(
                c, tag.hashCode() + 1, dec,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            // как уведомление: сверху HLZ, ниже — кто звонит
            b.setContentTitle("HLZ").setContentText(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(title))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setTimeoutAfter(30000)
                .addAction(R.drawable.ic_stat, "Принять", api)
                .addAction(R.drawable.ic_stat, "Отклонить", dpi)
        } else {
            b.setCategory(NotificationCompat.CATEGORY_MESSAGE)
        }
        NotificationManagerCompat.from(c).notify(tag, if (call) 2 else 1, b.build())
    }
}
