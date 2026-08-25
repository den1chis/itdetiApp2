package com.itdeti.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class ScheduleWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.itdeti.WIDGET_REFRESH"
        private const val PREFS = "itdeti_schedule"
        private const val PREF_ITEMS = "schedule_items"
        private const val EXTRA_EVENT_ID = "event_id"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ScheduleWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            for (id in ids) updateWidget(context, manager, id)
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_schedule)
            val items = readItems(context)
            val today = java.time.LocalDate.now()
            val todayItems = items.filter { item ->
                parseTime(item.optString("start_time"))
                    ?.atZone(ZoneId.systemDefault())
                    ?.toLocalDate() == today
            }.sortedBy { parseTime(it.optString("start_time"))?.toEpochMilli() ?: Long.MAX_VALUE }

            val visible = todayItems.take(3)
            views.setTextViewText(R.id.widget_title, "ITdeti • Сегодня")

            val next = visible.firstOrNull()
            if (next != null) {
                val nextTime = parseTime(next.optString("start_time"))
                    ?.atZone(ZoneId.systemDefault())
                    ?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "--:--"
                views.setTextViewText(R.id.widget_next, "Ближайшее: $nextTime")
            } else {
                views.setTextViewText(R.id.widget_next, "Сегодня событий нет")
            }

            val rowIds = intArrayOf(R.id.widget_event_1, R.id.widget_event_2, R.id.widget_event_3)
            for (i in rowIds.indices) {
                val item = visible.getOrNull(i)
                if (item == null) {
                    views.setTextViewText(rowIds[i], "")
                    continue
                }

                val time = parseTime(item.optString("start_time"))
                    ?.atZone(ZoneId.systemDefault())
                    ?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "--:--"
                val title = item.optString("title", "Событие")
                val student = item.optString("student_name", "")
                val suffix = if (student.isNotBlank() && student != "null") " • $student" else ""
                views.setTextViewText(rowIds[i], "$time  $title$suffix")

                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_EVENT_ID, item.optString("item_id"))
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    stableRequestCode(item.optString("item_id")),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(rowIds[i], pendingIntent)
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                90001,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title, openPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_next, openPendingIntent)
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun readItems(context: Context): List<org.json.JSONObject> {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_ITEMS, "[]") ?: "[]"
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun parseTime(value: String): Instant? {
            if (value.isBlank()) return null
            return try {
                Instant.parse(value)
            } catch (_: DateTimeParseException) {
                null
            }
        }

        private fun stableRequestCode(id: String): Int {
            val hash = id.hashCode()
            return if (hash == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(hash)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) updateAll(context)
    }
}
