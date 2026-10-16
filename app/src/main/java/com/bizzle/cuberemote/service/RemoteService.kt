package com.bizzle.cuberemote.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bizzle.cuberemote.CubeRemoteApp
import com.bizzle.cuberemote.MainActivity
import com.bizzle.cuberemote.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Lock-screen notification remote. Public visibility so the D-pad works without unlocking;
 * the foreground service also keeps the ADB link warm.
 */
class RemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running.value = false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, NOTIF_ID, build(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (!running.value) {
            running.value = true
            (application as CubeRemoteApp).cube.acquire()
            scope.launch {
                (application as CubeRemoteApp).cube.status.collect {
                    getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, build())
                }
            }
        }
        return START_STICKY
    }

    private fun build(): Notification {
        val app = application as CubeRemoteApp
        val st = app.cube.status.value
        fun keyPi(key: String, rc: Int) = PendingIntent.getBroadcast(
            this, rc, Intent(this, RemoteActionReceiver::class.java).setAction(RemoteActionReceiver.ACTION_KEY).putExtra("key", key),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val small = RemoteViews(packageName, R.layout.notif_remote_small)
        val big = RemoteViews(packageName, R.layout.notif_remote_big)
        for (v in listOf(small, big)) {
            v.setTextViewText(R.id.n_title, "Cube Remote v4.20 · " + st.state.name.lowercase())
            v.setOnClickPendingIntent(R.id.n_playpause, keyPi("PLAY_PAUSE", 10))
            v.setOnClickPendingIntent(R.id.n_voldown, keyPi("VOLUME_DOWN", 11))
            v.setOnClickPendingIntent(R.id.n_volup, keyPi("VOLUME_UP", 12))
            v.setOnClickPendingIntent(R.id.n_ok, keyPi("OK", 13))
        }
        big.setOnClickPendingIntent(R.id.n_up, keyPi("UP", 20))
        big.setOnClickPendingIntent(R.id.n_down, keyPi("DOWN", 21))
        big.setOnClickPendingIntent(R.id.n_left, keyPi("LEFT", 22))
        big.setOnClickPendingIntent(R.id.n_right, keyPi("RIGHT", 23))
        big.setOnClickPendingIntent(R.id.n_back, keyPi("BACK", 24))
        big.setOnClickPendingIntent(R.id.n_home, keyPi("HOME", 25))
        big.setOnClickPendingIntent(R.id.n_mute, keyPi("MUTE", 26))
        val stop = PendingIntent.getService(this, 30, Intent(this, RemoteService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        big.setOnClickPendingIntent(R.id.n_close, stop)
        return NotificationCompat.Builder(this, CubeRemoteApp.CH_REMOTE)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    override fun onDestroy() {
        if (running.value) (application as CubeRemoteApp).cube.release()
        running.value = false
        scope.cancel(); super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 4202
        const val ACTION_STOP = "stop"
        val running = MutableStateFlow(false)
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, RemoteService::class.java))
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, RemoteService::class.java).setAction(ACTION_STOP)) }
    }
}

/** Receives key presses from the notification remote and the home-screen widget. */
class RemoteActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as CubeRemoteApp
        val key = intent.getStringExtra("key") ?: return
        val pending = goAsync()
        app.cube.scope.launch {
            try {
                if (app.cube.ensureConnected()) app.cube.key(key)
                else app.cube.emit("Cube not reachable")
                kotlinx.coroutines.delay(300)
            } finally { pending.finish() }
        }
    }
    companion object { const val ACTION_KEY = "com.bizzle.cuberemote.KEY" }
}
