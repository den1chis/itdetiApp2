package com.itdeti.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object ScheduleListWidgetRenderer {
    private const val PREFS = "itdeti_schedule"
    private const val PREF_ITEMS = "schedule_items"
    private const val EXTRA_EVENT_ID = "event_id"
    private const val WEB_EVENT_QUERY = "?event_id="

    private val dayFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru"))
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private val dayIds = intArrayOf(
        R.id.widget_day_1, R.id.widget_day_2, R.id.widget_day_3,
        R.id.widget_day_4, R.id.widget_day_5, R.id.widget_day_6, R.id.widget_day_7
    )

    private val eventIds = arrayOf(
        intArrayOf(R.id.widget_event_101, R.id.widget_event_102, R.id.widget_event_103, R.id.widget_event_104),
        intArrayOf(R.id.widget_event_201, R.id.widget_event_202, R.id.widget_event_203, R.id.widget_event_204),
        intArrayOf(R.id.widget_event_301, R.id.widget_event_302, R.id.widget_event_303, R.id.widget_event_304),
        intArrayOf(R.id.widget_event_401, R.id.widget_event_402, R.id.widget_event_403, R.id.widget_event_404),
        intArrayOf(R.id.widget_event_501, R.id.widget_event_502, R.id.widget_event_503, R.id.widget_event_504),
        intArrayOf(R.id.widget_event_601, R.id.widget_event_602, R.id.widget_event_603, R.id.widget_event_604),
        intArrayOf(R.id.widget_event_701, R.id.widget_event_702, R.id.widget_event_703, R.id.widget_event_704)
    )

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, ScheduleListWidgetProvider::class.java)
        manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
    }

    fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_list)
        val items = readItems(context)
        val today = LocalDate.now()
        val dates = (0..6).map { today.plusDays(it.toLong()) }

        dayIds.forEachIndexed { dayIndex, dayId ->
            val date = dates[dayIndex]
            val isToday = dayIndex == 0
            val title = if (isToday) "сегодня, ${date.dayOfMonth} ${date.month.getDisplayName(java.time.format.TextStyle.FULL, Locale("ru"))}" else date.format(dayFormatter)
            views.setTextViewText(dayId, title.lowercase(Locale("ru")))
            views.setViewVisibility(dayId, View.VISIBLE)

            val dayItems = items
                .filter { localDate(it) == date }
                .sortedBy { parseInstant(it.optString("start_time"))?.toEpochMilli() ?: Long.MAX_VALUE }

            eventIds[dayIndex].forEachIndexed { rowIndex, rowId ->
                val item = dayItems.getOrNull(rowIndex)
                if (item == null) {
                    views.setViewVisibility(rowId, View.GONE)
                    return@forEachIndexed
                }

                views.setViewVisibility(rowId, View.VISIBLE)
                val start = parseInstant(item.optString("start_time"))?.atZone(ZoneId.systemDefault())
                val end = parseInstant(item.optString("end_time"))?.atZone(ZoneId.systemDefault())
                val time = if (start != null && end != null) "${start.format(timeFormatter)}–${end.format(timeFormatter)}" else "--:--"
                val isLesson = item.optString("item_type") == "lesson"
                val titleText = item.optString("title", "Событие")
                val student = item.optString("student_name", "").takeIf { it.isNotBlank() && it != "null" }
                val subtitle = if (isLesson) student ?: "Урок" else "Событие"
                views.setTextViewText(rowId, "$time  ·  $titleText${if (student != null && !titleText.contains(student)) "  ·  $student" else ""}")

                val color = parseColor(item.optString("color"), if (isLesson) "#4F46E5" else "#64748B")
                views.setInt(rowId, "setBackgroundColor", color)
                views.setTextColor(rowId, if (isDark(color) || isLesson) Color.WHITE else Color.WHITE)

                val clickIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_EVENT_ID, item.optString("item_id"))
                    putExtra("open_schedule", true)
                    putExtra("event_query", WEB_EVENT_QUERY + item.optString("item_id"))
                }
                val pending = PendingIntent.getActivity(
                    context,
                    stableRequestCode("list_${widgetId}_${item.optString("item_id")}"),
                    clickIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(rowId, pending)
            }
        }

        manager.updateAppWidget(widgetId, views)
    }

    private fun readItems(context: Context): List<JSONObject> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_ITEMS, "[]") ?: "[]"
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

    private fun isDark(color: Int): Boolean {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return luminance < 0.62
    }

    private fun stableRequestCode(value: String): Int {
        val hash = value.hashCode()
        return if (hash == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(hash).coerceAtLeast(1)
    }
}
