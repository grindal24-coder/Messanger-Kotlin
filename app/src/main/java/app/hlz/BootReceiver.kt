package app.hlz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// После перезагрузки телефона и после обновления приложения снова запускаем приём уведомлений
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        ListenService.startIfNeeded(c)
    }
}
