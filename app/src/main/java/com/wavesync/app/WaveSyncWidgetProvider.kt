package com.wavesync.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class WaveSyncWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateWidget(context, manager, id) }
    }

    companion object {
        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.wavesync_widget)
            views.setTextViewText(R.id.widget_title, "WaveSync")
            views.setTextViewText(R.id.widget_subtitle, "Quick audio controls")
            fun button(viewId: Int, action: String, request: Int) {
                val intent = Intent(context, MediaActionReceiver::class.java).setAction(action)
                val pending = PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(viewId, pending)
            }
            button(R.id.widget_previous, "com.wavesync.MEDIA_88", 11)
            button(R.id.widget_play, "com.wavesync.MEDIA_85", 12)
            button(R.id.widget_next, "com.wavesync.MEDIA_87", 13)
            val open = PendingIntent.getActivity(context, 14, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_open, open)
            manager.updateAppWidget(id, views)
        }
    }
}
