package com.itdeti.assistant

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class CompactWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ScheduleWidgetRenderer.updateProvider(
            context, manager, CompactWidgetProvider::class.java,
            ScheduleWidgetRenderer.Variant.COMPACT
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.itdeti.WIDGET_RANGE") {
            ScheduleWidgetRenderer.handleRange(
                context, intent, CompactWidgetProvider::class.java,
                ScheduleWidgetRenderer.Variant.COMPACT
            )
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        context.getSharedPreferences("itdeti_schedule", Context.MODE_PRIVATE).edit().apply {
            ids.forEach { remove("widget_range_$it") }
            apply()
        }
        super.onDeleted(context, ids)
    }
}
