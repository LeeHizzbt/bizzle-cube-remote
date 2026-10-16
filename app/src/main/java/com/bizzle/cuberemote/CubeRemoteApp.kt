package com.bizzle.cuberemote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.bizzle.cuberemote.cube.CubeManager
import com.bizzle.cuberemote.data.AppStore
import com.bizzle.cuberemote.data.SecureStore

class CubeRemoteApp : Application() {
    lateinit var store: AppStore
    lateinit var secure: SecureStore
    lateinit var cube: CubeManager

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        secure = SecureStore(this)
        cube = CubeManager(this, store)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_REMOTE, "Lock-screen remote", NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_TIMER, "Sleep timer", NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
    }

    companion object {
        const val CH_REMOTE = "remote"
        const val CH_TIMER = "sleep_timer"
    }
}
