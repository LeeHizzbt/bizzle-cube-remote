package com.bizzle.cuberemote.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.bizzle.cuberemote.MainActivity
import com.bizzle.cuberemote.R
import com.bizzle.cuberemote.service.RemoteActionReceiver

/** Home-screen widget: D-pad, OK, back/home, play/pause and volume. */
class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val v = RemoteViews(context.packageName, R.layout.widget_remote)
            fun bind(viewId: Int, key: String, rc: Int) = v.setOnClickPendingIntent(
                viewId, PendingIntent.getBroadcast(
                    context, rc, Intent(context, RemoteActionReceiver::class.java).setAction(RemoteActionReceiver.ACTION_KEY).putExtra("key", key),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            bind(R.id.w_up, "UP", 101); bind(R.id.w_down, "DOWN", 102); bind(R.id.w_left, "LEFT", 103); bind(R.id.w_right, "RIGHT", 104)
            bind(R.id.w_ok, "OK", 105); bind(R.id.w_back, "BACK", 106); bind(R.id.w_home, "HOME", 107)
            bind(R.id.w_playpause, "PLAY_PAUSE", 108); bind(R.id.w_volup, "VOLUME_UP", 109); bind(R.id.w_voldown, "VOLUME_DOWN", 110)
            v.setOnClickPendingIntent(R.id.w_title, PendingIntent.getActivity(context, 111, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            mgr.updateAppWidget(id, v)
        }
    }
}
