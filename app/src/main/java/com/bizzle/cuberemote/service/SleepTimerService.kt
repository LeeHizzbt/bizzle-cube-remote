package com.bizzle.cuberemote.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bizzle.cuberemote.CubeRemoteApp
import com.bizzle.cuberemote.MainActivity
import com.bizzle.cuberemote.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sleep timer: a foreground service with a live countdown notification (+10 min / Cancel).
 * When it ends it (optionally) fades the volume down a few steps, then sends SLEEP to the Cube,
 * which also turns the TV off over HDMI-CEC when CEC is on.
 */
class SleepTimerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val min = intent.getIntExtra(EXTRA_MIN, 30)
                endsAt.value = SystemClock.elapsedRealtime() + min * 60_000L
                startForegroundCompat()
                run()
            }
            ACTION_ADD -> {
                if (endsAt.value > 0) endsAt.value += 10 * 60_000L else endsAt.value = SystemClock.elapsedRealtime() + 10 * 60_000L
                startForegroundCompat(); run()
            }
            ACTION_CANCEL -> { stopTimer(); return START_NOT_STICKY }
            else -> if (endsAt.value <= 0) { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        ServiceCompat.startForeground(this, NOTIF_ID, build(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    private fun run() {
        if (job?.isActive == true) return
        val app = application as CubeRemoteApp
        app.cube.acquire()
        job = scope.launch {
            try {
                while (isActive) {
                    val left = endsAt.value - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
                    delay(if (left > 120_000) 30_000 else 5_000)
                }
                if (isActive && endsAt.value > 0) {
                    val cube = app.cube
                    if (cube.ensureConnected()) {
                        if (app.store.ui.value.sleepFade) {
                            repeat(5) { cube.key("VOLUME_DOWN"); delay(1500) }
                        }
                        cube.key("PAUSE"); delay(800)
                        cube.key("SLEEP")
                        cube.emit("Sleep timer done: Cube is going to sleep")
                    } else cube.emit("Sleep timer ended but the Cube wasn't reachable")
                }
            } finally {
                app.cube.release()
                endsAt.value = 0
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
    }

    private fun stopTimer() {
        endsAt.value = 0
        job?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun build(): Notification {
        val left = ((endsAt.value - SystemClock.elapsedRealtime()) / 60_000.0).let { kotlin.math.ceil(it).toInt().coerceAtLeast(0) }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun pi(action: String, rc: Int) = PendingIntent.getService(this, rc, Intent(this, SleepTimerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CubeRemoteApp.CH_TIMER)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle("Sleep timer: $left min left")
            .setContentText("Your Fire TV Cube goes to sleep when this ends")
            .setWhen(System.currentTimeMillis() + (endsAt.value - SystemClock.elapsedRealtime()))
            .setUsesChronometer(true).setChronometerCountDown(true)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(0, "+10 min", pi(ACTION_ADD, 1))
            .addAction(0, "Cancel", pi(ACTION_CANCEL, 2))
            .build()
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    companion object {
        const val NOTIF_ID = 4201
        const val ACTION_START = "start"; const val ACTION_ADD = "add10"; const val ACTION_CANCEL = "cancel"
        const val EXTRA_MIN = "min"
        /** elapsedRealtime when the timer ends, 0 = off. */
        val endsAt = MutableStateFlow(0L)

        fun start(ctx: Context, minutes: Int) = ContextCompat.startForegroundService(ctx, Intent(ctx, SleepTimerService::class.java).setAction(ACTION_START).putExtra(EXTRA_MIN, minutes))
        fun add10(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, SleepTimerService::class.java).setAction(ACTION_ADD))
        fun cancel(ctx: Context) { if (endsAt.value > 0) ctx.startService(Intent(ctx, SleepTimerService::class.java).setAction(ACTION_CANCEL)) }
    }
}
