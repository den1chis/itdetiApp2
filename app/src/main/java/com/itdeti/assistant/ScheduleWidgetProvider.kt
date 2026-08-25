package com.itdeti.assistant

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class ScheduleWidgetProvider : AppWidgetProvider() {
    companion object {
        fun updateAll(context: Context) = ScheduleWidgetRenderer.updateAll(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach {
            ScheduleWidgetRenderer.updateProvider(
                context,
                manager,
                ScheduleWidgetProvider::class.java,
                ScheduleWidgetRenderer.Variant.AGENDA
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.itdeti.WIDGET_RANGE") {
            ScheduleWidgetRenderer.handleRange(
                context, intent, ScheduleWidgetProvider::class.java,
                ScheduleWidgetRenderer.Variant.AGENDA
            )
            return
        }
        super.onReceive(context, intent)
        if (intent.action == "com.itdeti.WIDGET_REFRESH") {
            ScheduleWidgetRenderer.updateAll(context)
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        context.getSharedPreferences("itdeti_schedule", Context.MODE_PRIVATE).edit().apply {
            ids.forEach { remove("widget_range_$it") }
            apply()
        }
        super.onDeleted(context, ids)
    }
}
