package com.itdeti.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class ScheduleWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.itdeti.WIDGET_REFRESH"
        private const val ACTION_RANGE = "com.itdeti.WIDGET_RANGE"
        private const val PREFS = "itdeti_schedule"
        private const val PREF_ITEMS = "schedule_items"
        private const val PREF_WIDGET_RANGE = "widget_range_"
        private const val EXTRA_EVENT_ID = "event_id"
        private const val EXTRA_WIDGET_ID = "widget_id"
        private const val EXTRA_RANGE = "range"

        private const val RANGE_TODAY = "today"
        private const val RANGE_TOMORROW = "tomorrow"
        private const val RANGE_WEEK = "week"

        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM")

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ScheduleWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_schedule)
            val items = readItems(context)
            val range = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREF_WIDGET_RANGE + appWidgetId, RANGE_TODAY)
                ?: RANGE_TODAY

            val options = manager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120)
            val large = minWidth >= 280 && minHeight >= 180

            setupRangeButtons(context, views, appWidgetId, range)

            val now = LocalDate.now()
            val startDate = when (range) {
                RANGE_TOMORROW -> now.plusDays(1)
                else -> now
            }
            val endDate = when (range) {
                RANGE_TODAY -> startDate
                RANGE_TOMORROW -> startDate
                else -> now.plusDays(6)
            }

            val filtered = items.filter { item ->
                val date = localDate(item) ?: return@filter false
                date >= startDate && date <= endDate
            }.sortedBy { parseTime(it.optString("start_time"))?.toEpochMilli() ?: Long.MAX_VALUE }

            val title = when (range) {
                RANGE_TODAY -> "ITdeti • Сегодня"
                RANGE_TOMORROW -> "ITdeti • Завтра"
                else -> "ITdeti • Неделя"
            }
            views.setTextViewText(R.id.widget_title, title)

            val first = filtered.firstOrNull()
            views.setTextViewText(
                R.id.widget_next,
                if (first != null) {
                    val prefix = if (range == RANGE_WEEK) formatDateTime(first) else formatTime(first)
                    "Ближайшее: $prefix"
                } else {
                    if (range == RANGE_TODAY) "Сегодня событий нет" else "Событий нет"
                }
            )

            val lessons = filtered.count { it.optString("item_type") == "lesson" }
            val events = filtered.size - lessons
            views.setTextViewText(
                R.id.widget_summary,
                "$lessons уроков  •  $events событий  •  ${filtered.size} всего"
            )

            val rowIds = intArrayOf(
                R.id.widget_event_1,
                R.id.widget_event_2,
                R.id.widget_event_3,
                R.id.widget_event_4,
                R.id.widget_event_5
            )
            val visibleCount = if (large) 5 else 3
            rowIds.forEachIndexed { index, rowId ->
                val item = filtered.getOrNull(index)
                if (index >= visibleCount || item == null) {
                    views.setViewVisibility(rowId, View.GONE)
                    return@forEachIndexed
                }

                views.setViewVisibility(rowId, View.VISIBLE)
                val line = if (range == RANGE_WEEK) {
                    "${formatDateTime(item)}  ${displayTitle(item)}"
                } else {
                    "${formatTime(item)}  ${displayTitle(item)}"
                }
                views.setTextViewText(rowId, line)

                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_EVENT_ID, item.optString("item_id"))
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    stableRequestCode("event_${item.optString("item_id")}"),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(rowId, pendingIntent)
            }

            views.setTextViewText(R.id.widget_more, when {
                filtered.size > visibleCount -> "Ещё ${filtered.size - visibleCount} →"
                range == RANGE_WEEK -> "Открыть расписание →"
                else -> "Открыть расписание →"
            })
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                stableRequestCode("open_$appWidgetId"),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_title, openPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_more, openPendingIntent)

            views.setViewVisibility(R.id.widget_summary, if (large) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_more, View.VISIBLE)

            manager.updateAppWidget(appWidgetId, views)
        }

        private fun setupRangeButtons(context: Context, views: RemoteViews, appWidgetId: Int, range: String) {
            val ids = listOf(
                R.id.widget_today to RANGE_TODAY,
                R.id.widget_tomorrow to RANGE_TOMORROW,
                R.id.widget_week to RANGE_WEEK
            )
            ids.forEach { (viewId, value) ->
                views.setTextViewText(viewId, when (value) {
                    RANGE_TODAY -> "Сегодня"
                    RANGE_TOMORROW -> "Завтра"
                    else -> "Неделя"
                })
                views.setTextColor(viewId, if (value == range) 0xFF111111.toInt() else 0xFF777777.toInt())
                val intent = Intent(context, ScheduleWidgetProvider::class.java).apply {
                    action = ACTION_RANGE
                    putExtra(EXTRA_WIDGET_ID, appWidgetId)
                    putExtra(EXTRA_RANGE, value)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    stableRequestCode("range_${appWidgetId}_$value"),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(viewId, pendingIntent)
            }
        }

        private fun displayTitle(item: JSONObject): String {
            val title = item.optString("title", "Событие")
            val student = item.optString("student_name", "")
            return if (student.isNotBlank() && student != "null" && !title.contains(student)) {
                "$title • $student"
            } else {
                title
            }
        }

        private fun formatTime(item: JSONObject): String =
            parseTime(item.optString("start_time"))
                ?.atZone(ZoneId.systemDefault())
                ?.format(timeFormatter)
                ?: "--:--"

        private fun formatDateTime(item: JSONObject): String {
            val value = parseTime(item.optString("start_time"))?.atZone(ZoneId.systemDefault()) ?: return "--.-- --:--"
            return "${value.toLocalDate().format(dateFormatter)} ${value.toLocalTime().format(timeFormatter)}"
        }

        private fun localDate(item: JSONObject): LocalDate? =
            parseTime(item.optString("start_time"))?.atZone(ZoneId.systemDefault())?.toLocalDate()

        private fun readItems(context: Context): List<JSONObject> {
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

        private fun stableRequestCode(value: String): Int {
            val hash = value.hashCode()
            return if (hash == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(hash)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        appWidgetIds.forEach { prefs.remove(PREF_WIDGET_RANGE + it) }
        prefs.apply()
        super.onDeleted(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_RANGE) {
            val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val range = intent.getStringExtra(EXTRA_RANGE) ?: RANGE_TODAY
            if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_WIDGET_RANGE + widgetId, range)
                    .apply()
                val manager = AppWidgetManager.getInstance(context)
                updateWidget(context, manager, widgetId)
            }
            return
        }
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) updateAll(context)
    }
}
