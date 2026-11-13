package com.simoneluconi.betterauto.audio

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.simoneluconi.betterauto.AapService
import com.simoneluconi.betterauto.EventLog
import kotlin.concurrent.thread

/**
 * After the phone restarts, Shizuku started without root is gone until the driver starts it again, and that was
 * found out only in the car, with the music on the phone. A while after the restart (Shizuku may be set to start
 * by itself, which takes a moment) a notification says so if it is installed but not running, or not allowed.
 */
object ShizukuBootCheck {
    private const val JOB_ID = 4201
    /** Time for Shizuku's own start at boot, where it has one. */
    private const val DELAY_MS = 2 * 60 * 1000L
    /** How long the job waits for Shizuku's binder to reach this freshly started process. */
    private const val BINDER_WAIT_MS = 10_000L

    fun schedule(context: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, ShizukuCheckJob::class.java))
            .setMinimumLatency(DELAY_MS)
            .setOverrideDeadline(DELAY_MS * 2)
            .build()
        context.getSystemService(JobScheduler::class.java)?.schedule(job)
    }

    /** Runs off the main thread; tells the driver when Shizuku will not move the audio. */
    fun check(context: Context) {
        var state = ShizukuAudio.state(context)
        if (state == ShizukuAudio.State.NOT_INSTALLED) return
        val until = System.currentTimeMillis() + BINDER_WAIT_MS
        while (state == ShizukuAudio.State.NOT_RUNNING && System.currentTimeMillis() < until) {
            Thread.sleep(500)
            state = ShizukuAudio.state(context)
        }
        EventLog.log("Shizuku after the phone restarted: $state")
        when (state) {
            ShizukuAudio.State.NOT_RUNNING -> AapService.showShizukuAfterBoot(context, running = false)
            ShizukuAudio.State.NOT_ALLOWED -> AapService.showShizukuAfterBoot(context, running = true)
            else -> Unit
        }
    }
}

/** The phone finished starting: the check runs a little later. */
class ShizukuBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ShizukuBootCheck.schedule(context)
    }
}

class ShizukuCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        // On the main thread: the listeners that bring Shizuku's binder, and cancel the notice when it comes
        ShizukuAudio.init(applicationContext)
        thread(name = "shizuku-boot-check") {
            runCatching { ShizukuBootCheck.check(applicationContext) }
                .onFailure { EventLog.log("Shizuku after the phone restarted: check failed, $it") }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
