package com.itdeti.assistant

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
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

object ScheduleWidgetRenderer {
    const val RANGE_TODAY = "today"
    const val RANGE_TOMORROW = "tomorrow"
    const val RANGE_WEEK = "week"

    private const val PREFS = "itdeti_schedule"
    private const val PREF_ITEMS = "schedule_items"
    private const val PREF_WIDGET_RANGE = "widget_range_"
    private const val EXTRA_EVENT_ID = "event_id"
    private const val EXTRA_WIDGET_ID = "widget_id"
    private const val EXTRA_RANGE = "range"
    private const val EXTRA_VARIANT = "variant"
    private const val ACTION_RANGE = "com.itdeti.WIDGET_RANGE"

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM")

    enum class Variant(val key: String, val layout: Int, val maxRows: Int) {
        COMPACT("compact", R.layout.widget_compact, 3),
        AGENDA("agenda", R.layout.widget_agenda, 4),
        DASHBOARD("dashboard", R.layout.widget_dashboard, 6)
    }

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        updateProvider(context, manager, ScheduleWidgetProvider::class.java, Variant.AGENDA)
        updateProvider(context, manager, CompactWidgetProvider::class.java, Variant.COMPACT)
        updateProvider(context, manager, DashboardWidgetProvider::class.java, Variant.DASHBOARD)
    }

    fun updateProvider(context: Context, manager: AppWidgetManager, provider: Class<*>, variant: Variant) {
        val component = ComponentName(context, provider)
        manager.getAppWidgetIds(component).forEach { updateWidget(context, manager, it, provider, variant) }
    }

    fun handleRange(context: Context, intent: Intent, provider: Class<*>, variant: Variant) {
        val widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val range = intent.getStringExtra(EXTRA_RANGE) ?: RANGE_TODAY
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_WIDGET_RANGE + widgetId, range).apply()
        updateWidget(context, AppWidgetManager.getInstance(context), widgetId, provider, variant)
    }

    fun buildRangeIntent(context: Context, widgetId: Int, range: String, variant: Variant): PendingIntent {
        val intent = Intent(context, providerClass(variant)).apply {
            action = ACTION_RANGE
            putExtra(EXTRA_WIDGET_ID, widgetId)
            putExtra(EXTRA_RANGE, range)
            putExtra(EXTRA_VARIANT, variant.key)
        }
        return PendingIntent.getBroadcast(
            context,
            stableRequestCode("range_${variant.key}_${widgetId}_$range"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun providerClass(variant: Variant): Class<*> = when (variant) {
        Variant.COMPACT -> CompactWidgetProvider::class.java
        Variant.AGENDA -> ScheduleWidgetProvider::class.java
        Variant.DASHBOARD -> DashboardWidgetProvider::class.java
    }

    private fun updateWidget(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        provider: Class<*>,
        variant: Variant
    ) {
        val views = RemoteViews(context.packageName, variant.layout)
        val items = readItems(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val range = prefs.getString(PREF_WIDGET_RANGE + appWidgetId, RANGE_TODAY) ?: RANGE_TODAY

        val options = manager.getAppWidgetOptions(appWidgetId)
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120)
        val wide = minWidth >= 300
        val tall = minHeight >= 190

        val now = LocalDate.now()
        val startDate = if (range == RANGE_TOMORROW) now.plusDays(1) else now
        val endDate = when (range) {
            RANGE_WEEK -> now.plusDays(6)
            else -> startDate
        }
        val filtered = items.filter { item ->
            val date = localDate(item) ?: return@filter false
            date >= startDate && date <= endDate
        }.sortedBy { parseTime(it.optString("start_time"))?.toEpochMilli() ?: Long.MAX_VALUE }

        setupTabs(context, views, appWidgetId, range, variant)
        setText(views, R.id.widget_title, when (range) {
            RANGE_TOMORROW -> "ITdeti  •  Завтра"
            RANGE_WEEK -> "ITdeti  •  Неделя"
            else -> "ITdeti  •  Сегодня"
        })

        val first = filtered.firstOrNull()
        setText(views, R.id.widget_next, when {
            first == null && range == RANGE_TODAY -> "Сегодня свободно"
            first == null -> "Событий нет"
            else -> "Ближайшее  •  ${if (range == RANGE_WEEK) formatDateTime(first) else formatTime(first)}"
        })

        val lessons = filtered.count { it.optString("item_type") == "lesson" }
        val events = filtered.size - lessons
        setText(views, R.id.widget_summary, "$lessons уроков  ·  $events событий  ·  ${filtered.size} всего")

        if (variant == Variant.DASHBOARD) {
            setText(views, R.id.widget_stat_1, "${filtered.size}")
            setText(views, R.id.widget_stat_2, "$lessons")
            setText(views, R.id.widget_stat_3, "$events")
            setText(views, R.id.widget_stat_4, nextCountdown(first))
            setVisibility(views, R.id.widget_stats, if (tall || wide) View.VISIBLE else View.GONE)
        }

        val rowIds = intArrayOf(
            R.id.widget_event_1, R.id.widget_event_2, R.id.widget_event_3,
            R.id.widget_event_4, R.id.widget_event_5, R.id.widget_event_6
        )
        val visibleRows = when (variant) {
            Variant.COMPACT -> if (tall) 3 else 2
            Variant.AGENDA -> if (tall || wide) 4 else 3
            Variant.DASHBOARD -> if (tall) 6 else if (wide) 4 else 3
        }

        rowIds.forEachIndexed { index, rowId ->
            val item = filtered.getOrNull(index)
            if (item == null || index >= visibleRows) {
                setVisibility(views, rowId, View.GONE)
                return@forEachIndexed
            }
            setVisibility(views, rowId, View.VISIBLE)
            val type = if (item.optString("item_type") == "lesson") "УРОК" else "СОБЫТИЕ"
            val prefix = if (range == RANGE_WEEK) formatDateTime(item) else formatTime(item)
            val student = item.optString("student_name", "").takeIf { it.isNotBlank() && it != "null" }
            val title = item.optString("title", "Событие")
            val text = buildString {
                append(prefix)
                append("   ")
                append(type)
                append("\n")
                append(title)
                if (student != null && !title.contains(student)) append("  ·  ").append(student)
            }
            setText(views, rowId, text)
            val clickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_EVENT_ID, item.optString("item_id"))
            }
            val pending = PendingIntent.getActivity(
                context,
                stableRequestCode("event_${variant.key}_${item.optString("item_id")}"),
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(rowId, pending)
        }

        setVisibility(views, R.id.widget_summary, if (variant == Variant.COMPACT) View.GONE else View.VISIBLE)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            stableRequestCode("open_${variant.key}_$appWidgetId"),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        setClick(views, R.id.widget_title, openPending)
        setClick(views, R.id.widget_more, openPending)
        setText(views, R.id.widget_more, if (filtered.size > visibleRows) "Ещё ${filtered.size - visibleRows}  →" else "Открыть расписание  →")

        manager.updateAppWidget(appWidgetId, views)
    }

    private fun setupTabs(context: Context, views: RemoteViews, widgetId: Int, range: String, variant: Variant) {
        listOf(
            R.id.widget_today to RANGE_TODAY,
            R.id.widget_tomorrow to RANGE_TOMORROW,
            R.id.widget_week to RANGE_WEEK
        ).forEach { (id, value) ->
            setText(views, id, when (value) {
                RANGE_TODAY -> "Сегодня"
                RANGE_TOMORROW -> "Завтра"
                else -> "Неделя"
            })
            views.setTextColor(id, if (value == range) 0xFFFFFFFF.toInt() else 0xFF7A8494.toInt())
            views.setInt(id, "setBackgroundResource", if (value == range) R.drawable.widget_tab_selected else R.drawable.widget_tab)
            setClick(views, id, buildRangeIntent(context, widgetId, value, variant))
        }
    }

    private fun nextCountdown(item: JSONObject?): String {
        if (item == null) return "—"
        val millis = (parseTime(item.optString("start_time"))?.toEpochMilli() ?: return "—") - System.currentTimeMillis()
        if (millis <= 0) return "сейчас"
        val minutes = millis / 60_000
        return if (minutes < 60) "$minutes мин" else "${minutes / 60} ч"
    }

    private fun displayStudent(item: JSONObject): String = item.optString("student_name", "")
    private fun setText(v: RemoteViews, id: Int, value: String) = v.setTextViewText(id, value)
    private fun setVisibility(v: RemoteViews, id: Int, value: Int) = v.setViewVisibility(id, value)
    private fun setClick(v: RemoteViews, id: Int, pending: PendingIntent) = v.setOnClickPendingIntent(id, pending)

    private fun readItems(context: Context): List<JSONObject> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_ITEMS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseTime(value: String): Instant? = try { Instant.parse(value) } catch (_: DateTimeParseException) { null }
    private fun localDate(item: JSONObject): LocalDate? = parseTime(item.optString("start_time"))?.atZone(ZoneId.systemDefault())?.toLocalDate()
    private fun formatTime(item: JSONObject): String = parseTime(item.optString("start_time"))?.atZone(ZoneId.systemDefault())?.format(timeFormatter) ?: "--:--"
    private fun formatDateTime(item: JSONObject): String = parseTime(item.optString("start_time"))?.atZone(ZoneId.systemDefault())?.let { "${it.toLocalDate().format(dateFormatter)} ${it.toLocalTime().format(timeFormatter)}" } ?: "--.-- --:--"
    private fun stableRequestCode(value: String): Int = kotlin.math.abs(value.hashCode()).coerceAtLeast(1)
}
