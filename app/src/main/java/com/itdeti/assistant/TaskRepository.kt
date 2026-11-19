package com.itdeti.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Задачи для виджета: кэш ленты /tasks/feed, проекты и очередь отметок «выполнено».
 *
 * Отметка в виджете применяется сразу (локально), а на сервер уходит в фоне.
 * Если сети нет или Render ещё просыпается, отметка остаётся в очереди и
 * отправляется при следующей синхронизации (раз в 5 минут из NotificationService).
 */
object TaskRepository {
    private const val TAG = "itdeti_Tasks"
    private const val BASE_URL = "https://itdeti.onrender.com"
    private const val PREFS = "itdeti_tasks"
    private const val PREF_FEED = "task_feed"
    private const val PREF_PROJECTS = "task_projects"
    private const val PREF_PENDING = "task_pending"
    private const val FEED_DAYS = 6L

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val prefsLock = Any()

    data class Task(
        val taskId: String,
        val title: String,
        val projectId: String?,
        val date: LocalDate?,
        val occurrenceDate: String?,
        val isDone: Boolean,
        val repeat: String
    ) {
        val key: String get() = pendingKey(taskId, occurrenceDate)
    }

    data class Feed(val items: List<Task>, val undated: List<Task>, val overdue: List<Task>)
    data class Project(val name: String, val color: String)

    fun pendingKey(taskId: String, occurrenceDate: String?): String = "$taskId|${occurrenceDate.orEmpty()}"

    // ------------------------------------------------------------------ чтение кэша

    fun readFeed(context: Context): Feed {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = readPending(context)
        val raw = prefs.getString(PREF_FEED, null) ?: return Feed(emptyList(), emptyList(), emptyList())
        return try {
            val json = JSONObject(raw)
            Feed(
                items = parseTasks(json.optJSONArray("items"), pending),
                undated = parseTasks(json.optJSONArray("undated"), pending),
                overdue = parseTasks(json.optJSONArray("overdue"), pending)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Bad cached feed", e)
            Feed(emptyList(), emptyList(), emptyList())
        }
    }

    fun readProjects(context: Context): Map<String, Project> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_PROJECTS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }.associate {
                it.optString("id") to Project(it.optString("name"), it.optString("color", "#4f46e5"))
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun hasCache(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(PREF_FEED)

    private fun parseTasks(array: JSONArray?, pending: Map<String, Boolean>): List<Task> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapNotNull { obj ->
            val taskId = obj.optString("task_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val occurrence = obj.optNullableString("occurrence_date")
            val serverDone = obj.optBoolean("is_done", false)
            Task(
                taskId = taskId,
                title = obj.optNullableString("title") ?: "Задача",
                projectId = obj.optNullableString("project_id"),
                date = obj.optNullableString("date")?.let { parseDate(it) },
                occurrenceDate = occurrence,
                isDone = pending[pendingKey(taskId, occurrence)] ?: serverDone,
                repeat = obj.optNullableString("repeat") ?: "none"
            )
        }
    }

    // ------------------------------------------------------------------ отметка в виджете

    /** Мгновенно меняет отметку локально и запускает отправку на сервер. */
    fun toggle(context: Context, taskId: String, occurrenceDate: String?, done: Boolean) {
        synchronized(prefsLock) {
            val pending = readPendingJson(context)
            pending.put(pendingKey(taskId, occurrenceDate), done)
            writePending(context, pending)
        }
        ScheduleListWidgetRenderer.updateAll(context)
        requestSync(context)
    }

    fun requestSync(context: Context) {
        val app = context.applicationContext
        scope.launch { sync(app) }
    }

    // ------------------------------------------------------------------ синхронизация

    suspend fun sync(context: Context) = syncMutex.withLock {
        try {
            val sent = flushPending(context)
            val feedOk = fetchFeed(context)
            fetchProjects(context)
            if (feedOk) clearConfirmed(context, sent)
        } catch (e: Exception) {
            Log.e(TAG, "Task sync error", e)
        }
        ScheduleListWidgetRenderer.updateAll(context)
    }

    /** Отправляет очередь отметок. Возвращает отправленные (или уже неактуальные) отметки. */
    private fun flushPending(context: Context): Map<String, Boolean> {
        val pending = readPending(context)
        val sent = mutableMapOf<String, Boolean>()
        for ((key, done) in pending) {
            val taskId = key.substringBefore('|')
            val occurrence = key.substringAfter('|').ifBlank { null }
            val body = JSONObject().apply {
                put("done", done)
                if (occurrence != null) put("occurrence_date", occurrence)
            }
            val code = call(
                Request.Builder()
                    .url("$BASE_URL/tasks/$taskId/done")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
            )?.first ?: break // нет сети — остальное тоже не уйдёт
            when {
                code in 200..299 -> sent[key] = done
                code == 404 || code == 400 || code == 422 -> {
                    // задачу удалили или дата повтора больше не существует — отметку выбрасываем
                    Log.w(TAG, "Drop pending $key: HTTP $code")
                    sent[key] = done
                }
                else -> Log.e(TAG, "Toggle $key failed: HTTP $code")
            }
        }
        return sent
    }

    private fun fetchFeed(context: Context): Boolean {
        val today = LocalDate.now()
        val url = "$BASE_URL/tasks/feed?date_from=$today&date_to=${today.plusDays(FEED_DAYS)}&today=$today"
        val (code, body) = call(Request.Builder().url(url).get()) ?: return false
        if (code !in 200..299) {
            Log.e(TAG, "Feed failed: HTTP $code")
            return false
        }
        JSONObject(body) // проверка формата до записи в кэш
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_FEED, body).apply()
        return true
    }

    private fun fetchProjects(context: Context) {
        val (code, body) = call(Request.Builder().url("$BASE_URL/tasks/projects").get()) ?: return
        if (code !in 200..299) return
        JSONArray(body)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_PROJECTS, body).apply()
    }

    /** Запрос с токеном; при 401 один раз перелогинивается. null — сеть недоступна. */
    private fun call(builder: Request.Builder): Pair<Int, String>? {
        repeat(2) { attempt ->
            val token = ScheduleReminderManager.token() ?: return null
            try {
                ScheduleReminderManager.client.newCall(
                    builder.header("Authorization", "Bearer $token").build()
                ).execute().use { response ->
                    if (response.code == 401 && attempt == 0) {
                        ScheduleReminderManager.invalidateToken()
                        return@repeat
                    }
                    return response.code to response.body?.string().orEmpty()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error", e)
                return null
            }
        }
        return null
    }

    // ------------------------------------------------------------------ очередь

    private fun clearConfirmed(context: Context, sent: Map<String, Boolean>) {
        if (sent.isEmpty()) return
        synchronized(prefsLock) {
            val pending = readPendingJson(context)
            sent.forEach { (key, done) ->
                // пока шёл запрос, могли нажать ещё раз — такую отметку оставляем
                if (pending.has(key) && pending.optBoolean(key) == done) pending.remove(key)
            }
            writePending(context, pending)
        }
    }

    private fun readPending(context: Context): Map<String, Boolean> = synchronized(prefsLock) {
        val json = readPendingJson(context)
        json.keys().asSequence().associateWith { json.optBoolean(it) }
    }

    private fun readPendingJson(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_PENDING, "{}") ?: "{}"
        return try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
    }

    private fun writePending(context: Context, json: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_PENDING, json.toString()).commit()
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

    private fun parseDate(value: String): LocalDate? =
        try { LocalDate.parse(value.take(10)) } catch (_: DateTimeParseException) { null }
}
