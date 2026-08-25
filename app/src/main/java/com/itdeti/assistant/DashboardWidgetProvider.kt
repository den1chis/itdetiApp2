package com.itdeti.assistant

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class DashboardWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ScheduleWidgetRenderer.updateProvider(
            context,
            manager,
            DashboardWidgetProvider::class.java,
            ScheduleWidgetRenderer.Variant.DASHBOARD
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.itdeti.WIDGET_RANGE") {
            ScheduleWidgetRenderer.handleRange(
                context,
                intent,
                DashboardWidgetProvider::class.java,
                ScheduleWidgetRenderer.Variant.DASHBOARD
            )
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        val editor = context
            .getSharedPreferences("itdeti_schedule", Context.MODE_PRIVATE)
            .edit()

        ids.forEach { widgetId ->
            editor.remove("widget_range_$widgetId")
        }

        editor.apply()
        super.onDeleted(context, ids)
    }
}
