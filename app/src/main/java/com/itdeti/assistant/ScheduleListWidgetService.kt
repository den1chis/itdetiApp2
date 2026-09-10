package com.itdeti.assistant

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

class ScheduleListWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        return ScheduleListRemoteViewsFactory(applicationContext, widgetId)
    }
}

private class ScheduleListRemoteViewsFactory(
    private val context: android.content.Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(
        val type: Int,
        val date: LocalDate,
        val item: JSONObject? = null
    )

    companion object {
        private const val TYPE_DAY = 0
        private const val TYPE_EVENT = 1
        private const val PREFS = "itdeti_schedule"
        private const val PREF_ITEMS = "schedule_items"
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val russian = Locale("ru")
    }

    private val rows = mutableListOf<Row>()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        rows.clear()
        val items = readItems()
        val today = LocalDate.now()

        for (offset in 0L..6L) {
            val date = today.plusDays(offset)
            rows += Row(TYPE_DAY, date)

            val dayItems = items
                .filter { localDate(it) == date }
                .sortedBy { parseInstant(it.optString("start_time"))?.toEpochMilli() ?: Long.MAX_VALUE }

            dayItems.forEach { rows += Row(TYPE_EVENT, date, it) }
        }
    }

    override fun onDestroy() {
        rows.clear()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in rows.indices) return null
        val row = rows[position]

        if (row.type == TYPE_DAY) {
            val views = RemoteViews(context.packageName, R.layout.widget_schedule_list_day)
            val title = if (row.date == LocalDate.now()) {
                "сегодня, ${row.date.dayOfMonth} ${row.date.month.getDisplayName(TextStyle.FULL, russian)}"
            } else {
                "${row.date.dayOfWeek.getDisplayName(TextStyle.FULL, russian)}, ${row.date.dayOfMonth} ${row.date.month.getDisplayName(TextStyle.FULL, russian)}"
            }
            views.setTextViewText(R.id.widget_schedule_day_text, title.lowercase(russian))
            return views
        }

        val item = row.item ?: return null
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_list_event)
        val start = parseInstant(item.optString("start_time"))?.atZone(ZoneId.systemDefault())
        val end = parseInstant(item.optString("end_time"))?.atZone(ZoneId.systemDefault())
        val time = if (start != null && end != null) {
            "${start.format(timeFormatter)}–${end.format(timeFormatter)}"
        } else {
            "--:--"
        }

        val isLesson = item.optString("item_type") == "lesson"
        val title = item.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: if (isLesson) "Урок" else "Событие"
        val student = item.optString("student_name", "").takeIf { it.isNotBlank() && it != "null" }
        val text = buildString {
            append(time)
            append("  ·  ")
            append(title)
            if (student != null && !title.contains(student)) {
                append("  ·  ")
                append(student)
            }
        }
        views.setTextViewText(R.id.widget_schedule_event_text, text)

        val fallback = if (isLesson) "#4F46E5" else "#64748B"
        val color = parseColor(item.optString("color"), fallback)
        views.setInt(R.id.widget_schedule_event_text, "setBackgroundColor", color)
        views.setTextColor(R.id.widget_schedule_event_text, Color.WHITE)

        val fillInIntent = Intent().apply {
            putExtra("event_id", item.optString("item_id"))
            putExtra("open_schedule", true)
            putExtra("event_query", "?event_id=${item.optString("item_id")}")
        }
        views.setOnClickFillInIntent(R.id.widget_schedule_event_text, fillInIntent)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 2

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true

    private fun readItems(): List<JSONObject> {
        val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getString(PREF_ITEMS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseInstant(value: String): Instant? = try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun localDate(item: JSONObject): LocalDate? = parseInstant(item.optString("start_time"))
        ?.atZone(ZoneId.systemDefault())?.toLocalDate()

    private fun parseColor(value: String, fallback: String): Int = try {
        Color.parseColor(if (value.isBlank() || value == "null") fallback else value)
    } catch (_: IllegalArgumentException) {
        Color.parseColor(fallback)
    }
}
