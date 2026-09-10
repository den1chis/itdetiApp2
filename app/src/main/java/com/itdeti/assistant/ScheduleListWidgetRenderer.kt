package com.itdeti.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

object ScheduleListWidgetRenderer {
    private const val ACTION_REFRESH = "com.itdeti.WIDGET_REFRESH"

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, ScheduleListWidgetProvider::class.java)
        manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
    }

    fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        val views = RemoteViews(context.packageName, R.layout.widget_schedule_list)
        val serviceIntent = Intent(context, ScheduleListWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widget_schedule_list, serviceIntent)

        val clickIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_schedule", true)
        }
        val clickPendingIntent = PendingIntent.getActivity(
            context,
            stableRequestCode("schedule_list_widget_$widgetId"),
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setPendingIntentTemplate(R.id.widget_schedule_list, clickPendingIntent)

        manager.updateAppWidget(widgetId, views)
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_schedule_list)
    }

    private fun stableRequestCode(value: String): Int {
        val hash = value.hashCode()
        return if (hash == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(hash).coerceAtLeast(1)
    }
}
