package app.hlz

import android.app.job.JobParameters
import android.app.job.JobService

// Страховка: если система убила службу приёма (приложение «закрыли»), раз в ~15 минут
// забираем очередь уведомлений. Работает и при полностью закрытом приложении —
// систему будит само задание, а не наша служба.
class PollJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread {
            try { ListenService.pollOnce(this) } catch (_: Exception) {}
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
