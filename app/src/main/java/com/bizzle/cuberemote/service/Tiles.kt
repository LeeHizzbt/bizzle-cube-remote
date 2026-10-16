package com.bizzle.cuberemote.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.bizzle.cuberemote.CubeRemoteApp
import com.bizzle.cuberemote.MainActivity

private fun TileService.openApp(extra: String) {
    val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open", extra)
    if (Build.VERSION.SDK_INT >= 34) {
        startActivityAndCollapse(PendingIntent.getActivity(this, extra.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
        startActivityAndCollapseLegacy(i)
    }
}

@Suppress("DEPRECATION")
@SuppressLint("StartActivityAndCollapseDeprecated")
private fun TileService.startActivityAndCollapseLegacy(i: Intent) = startActivityAndCollapse(i)

/** QS tile: toggles the lock-screen notification remote; long-press opens the app. */
class RemoteTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = if (RemoteService.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
    }
    override fun onClick() {
        if (RemoteService.running.value) RemoteService.stop(this) else RemoteService.start(this)
        qsTile?.apply { state = if (!RemoteService.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; updateTile() }
    }
}

/** QS tile: starts the default sleep timer, or cancels a running one. */
class SleepTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (SleepTimerService.endsAt.value > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (SleepTimerService.endsAt.value > 0) "On (tap to cancel)" else "${(application as CubeRemoteApp).store.ui.value.defaultSleepMin} min"
            updateTile()
        }
    }
    override fun onClick() {
        if (SleepTimerService.endsAt.value > 0) SleepTimerService.cancel(this)
        else SleepTimerService.start(this, (application as CubeRemoteApp).store.ui.value.defaultSleepMin)
        onStartListening()
    }
}

/** QS tile: opens Bizzy straight into push-to-talk voice. */
class BizzyVoiceTileService : TileService() {
    override fun onClick() = openApp("voice")
}
