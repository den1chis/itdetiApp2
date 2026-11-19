package com.itdeti.assistant

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
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
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return ScheduleListRemoteViewsFactory(applicationContext, widgetId)
    }
}

private class ScheduleListRemoteViewsFactory(
    private val context: android.content.Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(
        val type: Int,
        val date: LocalDate?,
        val item: JSONObject? = null,
        val task: TaskRepository.Task? = null,
        val label: String? = null,
        val labelColor: Int? = null
    )

    companion object {
        private const val TYPE_DAY = 0
        private const val TYPE_EVENT = 1
        private const val TYPE_TASK = 2
        private const val TYPE_SECTION = 3
        private const val DAY_TEXT_COLOR = 0xFFB8C1D1.toInt()
        private const val OVERDUE_COLOR = 0xFFF87171.toInt()
        private const val PREFS = "itdeti_schedule"
        private const val PREF_ITEMS = "schedule_items"
        private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val russian = Locale("ru")
    }

    private val rows = mutableListOf<Row>()

    override fun onCreate() = Unit

    private var projects: Map<String, TaskRepository.Project> = emptyMap()

    override fun onDataSetChanged() {
        rows.clear()
        if (ScheduleListWidgetRenderer.mode(context, widgetId) == ScheduleListWidgetRenderer.MODE_TASKS) {
            buildTaskRows()
        } else {
            buildCalendarRows()
        }
    }

    private fun buildTaskRows() {
        if (!TaskRepository.hasCache(context)) return
        val feed = TaskRepository.readFeed(context)
        projects = TaskRepository.readProjects(context)
        val today = LocalDate.now()

        fun section(label: String, tasks: List<TaskRepository.Task>, color: Int = DAY_TEXT_COLOR) {
            if (tasks.isEmpty()) return
            rows += Row(TYPE_SECTION, null, label = label, labelColor = color)
            // невыполненные сверху, выполненные внизу секции (зачёркнутые, пока не обновится лента)
            tasks.sortedBy { it.isDone }.forEach { rows += Row(TYPE_TASK, it.date, task = it) }
        }

        // Прошедшие дни не показываем. Невыполненная разовая задача с прошлой датой — в «просрочено»
        // (сервер делает так же; здесь это нужно, если кэш остался со вчера и ещё не обновился).
        val overdue = (feed.overdue + feed.items.filter { task ->
            val date = task.date
            date != null && date < today && !task.isDone && task.repeat == "none"
        }).distinctBy { it.key }.sortedBy { it.date }

        section("просрочено", overdue, OVERDUE_COLOR)
        for (offset in 0L..6L) {
            val date = today.plusDays(offset)
            section(dayTitle(date), feed.items.filter { it.date == date })
        }
        section("без срока", feed.undated)
    }

    private fun dayTitle(date: LocalDate): String {
        val today = LocalDate.now()
        val month = date.month.getDisplayName(TextStyle.FULL, russian)
        val prefix = when (date) {
            today -> "сегодня"
            today.plusDays(1) -> "завтра"
            else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, russian)
        }
        return "$prefix, ${date.dayOfMonth} $month".lowercase(russian)
    }

    private fun buildCalendarRows() {
        val items = readItems()
        val today = LocalDate.now()

        for (offset in 0L..6L) {
            val date = today.plusDays(offset)
            rows += Row(TYPE_DAY, date)

            items
                .filter { localDate(it) == date }
                .sortedBy { parseInstant(it.optString("start_time"))?.toEpochMilli() ?: Long.MAX_VALUE }
                .forEach { rows += Row(TYPE_EVENT, date, it) }
        }
    }

    override fun onDestroy() {
        rows.clear()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in rows.indices) return null
        val row = rows[position]

        if (row.type == TYPE_SECTION) {
            val views = RemoteViews(context.packageName, R.layout.widget_schedule_list_day)
            views.setTextViewText(R.id.widget_schedule_day_text, row.label.orEmpty())
            views.setTextColor(R.id.widget_schedule_day_text, row.labelColor ?: DAY_TEXT_COLOR)
            return views
        }
        if (row.type == TYPE_TASK) return taskView(row.task ?: return null)

        val date = row.date ?: return null
        if (row.type == TYPE_DAY) {
            val views = RemoteViews(context.packageName, R.layout.widget_schedule_list_day)
            val title = if (date == LocalDate.now()) {
                "сегодня, ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, russian)}"
            } else {
                "${date.dayOfWeek.getDisplayName(TextStyle.FULL, russian)}, ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, russian)}"
            }
            views.setTextViewText(R.id.widget_schedule_day_text, title.lowercase(russian))
            views.setTextColor(R.id.widget_schedule_day_text, DAY_TEXT_COLOR)
            return views
        }

        val item = row.item ?: return null
        val views = RemoteViews(context.packageName, R.layout.widget_schedule_list_event)

        val start = parseInstant(item.optString("start_time"))?.atZone(ZoneId.systemDefault())
        val end = parseInstant(item.optString("end_time"))?.atZone(ZoneId.systemDefault())
        val time = when {
            start == null -> "--:--"
            end != null -> "${start.format(timeFormatter)}–${end.format(timeFormatter)}"
            else -> start.format(timeFormatter)
        }

        val isLesson = item.optString("item_type") == "lesson"
        val title = item.optString("title")
            .takeIf { it.isNotBlank() && it != "null" }
            ?: if (isLesson) "Урок" else "Событие"

        val student = item.optString("student_name", "")
            .takeIf { it.isNotBlank() && it != "null" }

        val meta = if (isLesson) {
            if (item.optString("lesson_kind") == "masterclass") {
                "Мастер-класс · $time"
            } else {
                val course = item.optString("course", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: "Урок"
                "$course · $time"
            }
        } else {
            val eventMeta = item.optString("location", "")
                .takeIf { it.isNotBlank() && it != "null" }
                ?: item.optString("event_type", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                ?: ""
            if (eventMeta.isNotBlank()) "$eventMeta · $time" else time
        }

        views.setTextViewText(
            R.id.widget_schedule_event_title,
            if (isLesson && student != null) student else title
        )
        views.setTextViewText(R.id.widget_schedule_event_meta, meta)

        val fallback = if (isLesson) "#4F46E5" else "#64748B"
        val color = parseColor(item.optString("color"), fallback)
        val textColor = readableTextColor(color)

        views.setColorStateList(
            R.id.widget_schedule_event_root,
            "setBackgroundTintList",
            ColorStateList.valueOf(color)
        )
        views.setTextColor(R.id.widget_schedule_event_title, textColor)
        views.setTextColor(R.id.widget_schedule_event_meta, textColor)

        val fillInIntent = Intent().apply {
            putExtra("event_id", item.optString("item_id"))
            putExtra("open_schedule", true)
            putExtra("event_query", "?event_id=${item.optString("item_id")}")
        }
        views.setOnClickFillInIntent(R.id.widget_schedule_event_content, fillInIntent)
        return views
    }

    private fun taskView(task: TaskRepository.Task): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_task_item)

        val title = SpannableStringBuilder(task.title)
        if (task.isDone) {
            title.setSpan(StrikethroughSpan(), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        views.setTextViewText(R.id.widget_task_title, title)
        views.setTextColor(R.id.widget_task_title, if (task.isDone) 0xFF6B7A99.toInt() else Color.WHITE)
        views.setTextViewText(R.id.widget_task_check, if (task.isDone) "✓" else "")
        views.setInt(
            R.id.widget_task_check,
            "setBackgroundResource",
            if (task.isDone) R.drawable.widget_task_check_done else R.drawable.widget_task_check
        )

        val meta = taskMeta(task)
        views.setViewVisibility(
            R.id.widget_task_meta,
            if (meta.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        )
        views.setTextViewText(R.id.widget_task_meta, meta)

        val fillIn = Intent().apply {
            putExtra(ScheduleListWidgetRenderer.EXTRA_TASK_ID, task.taskId)
            putExtra(ScheduleListWidgetRenderer.EXTRA_OCCURRENCE, task.occurrenceDate.orEmpty())
            putExtra(ScheduleListWidgetRenderer.EXTRA_DONE, !task.isDone)
        }
        views.setOnClickFillInIntent(R.id.widget_task_root, fillIn)
        return views
    }

    private fun taskMeta(task: TaskRepository.Task): CharSequence {
        val meta = SpannableStringBuilder()
        task.projectId?.let { projects[it] }?.let { project ->
            meta.append("● ")
            meta.setSpan(
                ForegroundColorSpan(parseColor(project.color, "#4F46E5")),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            meta.append(project.name)
        }
        if (task.repeat != "none") {
            if (meta.isNotEmpty()) meta.append("  ·  ")
            meta.append("↻ ").append(repeatLabel(task.repeat))
        }
        val date = task.date
        if (date != null && date < LocalDate.now()) {
            if (meta.isNotEmpty()) meta.append("  ·  ")
            meta.append("${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, russian)}")
        }
        return meta
    }

    private fun repeatLabel(repeat: String): String = when (repeat) {
        "daily" -> "каждый день"
        "weekly" -> "каждую неделю"
        "monthly" -> "каждый месяц"
        "yearly" -> "каждый год"
        else -> "повтор"
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 3
    override fun getItemId(position: Int): Long {
        val row = rows.getOrNull(position) ?: return position.toLong()
        val key = when (row.type) {
            TYPE_TASK -> "task:${row.task?.key}"
            TYPE_SECTION -> "section:${row.label}"
            TYPE_DAY -> "day:${row.date}"
            else -> "event:${row.item?.optString("item_id")}:${row.date}:$position"
        }
        return key.hashCode().toLong()
    }
    override fun hasStableIds(): Boolean = true

    private fun readItems(): List<JSONObject> {
        val raw = context.getSharedPreferences(
            PREFS,
            android.content.Context.MODE_PRIVATE
        ).getString(PREF_ITEMS, "[]") ?: "[]"

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

    private fun localDate(item: JSONObject): LocalDate? =
        parseInstant(item.optString("start_time"))
            ?.atZone(ZoneId.systemDefault())
            ?.toLocalDate()

    private fun parseColor(value: String, fallback: String): Int = try {
        Color.parseColor(
            if (value.isBlank() || value == "null") fallback else value
        )
    } catch (_: IllegalArgumentException) {
        Color.parseColor(fallback)
    }

    private fun readableTextColor(background: Int): Int {
        val red = Color.red(background) / 255.0
        val green = Color.green(background) / 255.0
        val blue = Color.blue(background) / 255.0

        val luminance =
            0.2126 * red +
            0.7152 * green +
            0.0722 * blue

        return if (luminance > 0.62) Color.rgb(17, 24, 39) else Color.WHITE
    }
}
