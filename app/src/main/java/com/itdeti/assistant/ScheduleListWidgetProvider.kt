package com.itdeti.assistant

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class ScheduleListWidgetProvider : AppWidgetProvider() {
    companion object {
        fun updateAll(context: Context) = ScheduleListWidgetRenderer.updateAll(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { ScheduleListWidgetRenderer.updateWidget(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.itdeti.WIDGET_REFRESH") {
            updateAll(context)
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        super.onDeleted(context, ids)
    }
}
