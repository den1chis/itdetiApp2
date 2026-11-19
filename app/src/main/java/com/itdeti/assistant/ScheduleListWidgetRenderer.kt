package com.itdeti.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.RemoteViews
import java.time.LocalDate

object ScheduleListWidgetRenderer {
    const val MODE_CALENDAR = "calendar"
    const val MODE_TASKS = "tasks"

    const val ACTION_MODE = "com.itdeti.WIDGET_LIST_MODE"
    const val ACTION_TASK_TOGGLE = "com.itdeti.WIDGET_TASK_TOGGLE"
    const val EXTRA_MODE = "mode"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_OCCURRENCE = "occurrence_date"
    const val EXTRA_DONE = "done"

    private const val PREFS = "itdeti_schedule"
    private const val PREF_MODE = "list_mode_"

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, ScheduleListWidgetProvider::class.java)
        manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
    }

    fun mode(context: Context, widgetId: Int): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_MODE + widgetId, MODE_CALENDAR) ?: MODE_CALENDAR

    fun setMode(context: Context, widgetId: Int, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_MODE + widgetId, mode).apply()
    }

    fun forget(context: Context, widgetIds: IntArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            widgetIds.forEach { remove(PREF_MODE + it) }
            apply()
        }
    }

    fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val mode = mode(context, widgetId)
        val tasks = mode == MODE_TASKS

        val views = RemoteViews(context.packageName, R.layout.widget_schedule_list)

        // Фабрика строк сама читает режим виджета при каждом onDataSetChanged.
        val serviceIntent = Intent(context, ScheduleListWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widget_schedule_list, serviceIntent)
        views.setEmptyView(R.id.widget_schedule_list, R.id.widget_list_empty)
        views.setTextViewText(
            R.id.widget_list_empty,
            if (TaskRepository.hasCache(context)) "Задач нет 🎉" else "Загружаю задачи…"
        )

        // Шапка: переключатель «Календарь / Задачи» и кнопка открытия приложения.
        styleTab(views, R.id.widget_list_tab_calendar, "Календарь", !tasks)
        styleTab(views, R.id.widget_list_tab_tasks, tasksTabTitle(context, tasks), tasks)
        views.setOnClickPendingIntent(R.id.widget_list_tab_calendar, modeIntent(context, widgetId, MODE_CALENDAR))
        views.setOnClickPendingIntent(R.id.widget_list_tab_tasks, modeIntent(context, widgetId, MODE_TASKS))
        views.setOnClickPendingIntent(R.id.widget_list_open, openAppIntent(context, widgetId))

        // Шаблон нажатия по строкам списка.
        views.setPendingIntentTemplate(
            R.id.widget_schedule_list,
            if (tasks) toggleTemplate(context, widgetId) else openScheduleTemplate(context, widgetId)
        )

        manager.updateAppWidget(widgetId, views)
        manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_schedule_list)
    }

    private fun tasksTabTitle(context: Context, active: Boolean): String {
        if (!active || !TaskRepository.hasCache(context)) return "Задачи"
        val today = LocalDate.now()
        val feed = TaskRepository.readFeed(context)
        val todayTasks = feed.items.filter { it.date == today }
        if (todayTasks.isEmpty()) return "Задачи"
        return "Задачи ${todayTasks.count { it.isDone }}/${todayTasks.size}"
    }

    private fun styleTab(views: RemoteViews, id: Int, title: String, selected: Boolean) {
        views.setTextViewText(id, title)
        views.setTextColor(id, if (selected) 0xFFFFFFFF.toInt() else 0xFF73819B.toInt())
        views.setInt(id, "setBackgroundResource", if (selected) R.drawable.widget_tab_selected else R.drawable.widget_tab)
    }

    private fun modeIntent(context: Context, widgetId: Int, mode: String): PendingIntent {
        val intent = Intent(context, ScheduleListWidgetProvider::class.java).apply {
            action = ACTION_MODE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            putExtra(EXTRA_MODE, mode)
        }
        return PendingIntent.getBroadcast(
            context,
            stableRequestCode("list_mode_${widgetId}_$mode"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun toggleTemplate(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, ScheduleListWidgetProvider::class.java).apply {
            action = ACTION_TASK_TOGGLE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        // Шаблон должен быть изменяемым, иначе Android 12+ не подставит id задачи из строки.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            stableRequestCode("task_toggle_$widgetId"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
    }

    private fun openScheduleTemplate(context: Context, widgetId: Int): PendingIntent {
        val clickIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_schedule", true)
        }
        return PendingIntent.getActivity(
            context,
            stableRequestCode("schedule_list_widget_$widgetId"),
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openAppIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            stableRequestCode("schedule_list_open_$widgetId"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stableRequestCode(value: String): Int {
        val hash = value.hashCode()
        return if (hash == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(hash).coerceAtLeast(1)
    }
}
