package app.hlz

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

// Приём push-сообщений от Firebase Cloud Messaging (работает, даже если приложение закрыто).
// Сервер шлёт только data-поля, поэтому показываем уведомление сами — через тот же Notif,
// что и раньше (полноэкранный вызов, кнопки «Принять»/«Отклонить»).
class FcmService : FirebaseMessagingService() {
    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        val call = d["call"] == "1" || d["call"] == "true"
        val chat = d["chat"]
        val title = d["title"] ?: "HLZ"
        val body = d["body"] ?: ""
        val tag = d["tag"] ?: "m"
        if (call) ListenService.writeLog(this, "FCM: звонок от $title") else ListenService.writeLog(this, "FCM: сообщение от $title")
        Notif.show(this, title, body, if (chat.isNullOrEmpty() || chat == "null") null else chat, tag, call)
    }

    override fun onNewToken(token: String) {
        getSharedPreferences("hlz", 0).edit().putString("fcmToken", token).apply()
        ListenService.writeLog(this, "FCM: токен обновлён")
    }
}
