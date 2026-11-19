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
        if (ids.any { ScheduleListWidgetRenderer.mode(context, it) == ScheduleListWidgetRenderer.MODE_TASKS }) {
            TaskRepository.requestSync(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ScheduleListWidgetRenderer.ACTION_MODE -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val mode = intent.getStringExtra(ScheduleListWidgetRenderer.EXTRA_MODE)
                    ?: ScheduleListWidgetRenderer.MODE_CALENDAR
                ScheduleListWidgetRenderer.setMode(context, widgetId, mode)
                ScheduleListWidgetRenderer.updateWidget(context, AppWidgetManager.getInstance(context), widgetId)
                if (mode == ScheduleListWidgetRenderer.MODE_TASKS) TaskRepository.requestSync(context)
                return
            }
            ScheduleListWidgetRenderer.ACTION_TASK_TOGGLE -> {
                val taskId = intent.getStringExtra(ScheduleListWidgetRenderer.EXTRA_TASK_ID)
                if (taskId.isNullOrBlank()) return
                val occurrence = intent.getStringExtra(ScheduleListWidgetRenderer.EXTRA_OCCURRENCE)?.ifBlank { null }
                val done = intent.getBooleanExtra(ScheduleListWidgetRenderer.EXTRA_DONE, true)
                TaskRepository.toggle(context, taskId, occurrence, done)
                return
            }
        }
        super.onReceive(context, intent)
        if (intent.action == "com.itdeti.WIDGET_REFRESH") {
            updateAll(context)
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ScheduleListWidgetRenderer.forget(context, ids)
        super.onDeleted(context, ids)
    }
}
