package app.hlz

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

// После перезагрузки телефона и после обновления приложения снова включаем фоновые проверки.
// (Push-уведомления Firebase работают и без этого — их будит система.)
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            js.schedule(
                JobInfo.Builder(7081, ComponentName(c, PollJob::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .build()
            )
        } catch (_: Exception) {}
    }
}
