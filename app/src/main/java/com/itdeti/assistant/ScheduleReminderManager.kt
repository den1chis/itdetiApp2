package com.itdeti.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

object ScheduleReminderManager {

    private const val TAG = "itdeti_Schedule"
    private const val UPCOMING_URL = "https://itdeti.onrender.com/schedule/upcoming?days=7"
    private const val LOGIN_URL = "https://itdeti.onrender.com/auth/login"
    private const val EMAIL = "sdenmansss@gmail.com"
    private const val PASSWORD = "GhjcnjqDen2552!"
    private const val PREFS = "itdeti_schedule"
    private const val PREF_SCHEDULED_IDS = "scheduled_ids"
    private const val PREF_ITEMS = "schedule_items"
    private const val REMINDER_MINUTES = 30L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var authToken = ""

    suspend fun sync(context: Context) {
        try {
            val token = getToken() ?: return
            val request = Request.Builder()
                .url(UPCOMING_URL)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                Log.d(TAG, "Upcoming response: ${response.code}")

                if (response.code == 401) {
                    authToken = ""
                    return
                }

                if (!response.isSuccessful) {
                    Log.e(TAG, "Upcoming request failed: ${response.code} $body")
                    return
                }

                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_ITEMS, body)
                    .apply()

                scheduleItems(context, JSONArray(body))
                ScheduleWidgetProvider.updateAll(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Schedule sync error", e)
        }
    }

    private fun getToken(): String? {
        if (authToken.isNotBlank()) return authToken

        return try {
            val json = JSONObject().apply {
                put("email", EMAIL)
                put("password", PASSWORD)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(LOGIN_URL)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Login failed: ${response.code} $responseBody")
                    return null
                }

                authToken = JSONObject(responseBody).optString("access_token")
                authToken.ifBlank { null }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            null
        }
    }

    private fun scheduleItems(context: Context, items: JSONArray) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val scheduledIds = mutableSetOf<String>()
        val oldIds = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(PREF_SCHEDULED_IDS, emptySet())
            ?.toSet()
            ?: emptySet()

        for (oldId in oldIds) {
            if (!containsItem(items, oldId)) {
                cancelAlarm(context, alarmManager, oldId)
            }
        }

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("item_id")
            val startTime = parseInstant(item.optString("start_time")) ?: continue
            if (id.isBlank()) continue

            val triggerAt = startTime.toEpochMilli() - REMINDER_MINUTES * 60_000L
            if (triggerAt <= now) continue

            val itemType = item.optString("item_type", "event")
            val title = item.optString("title", "Событие")
            val studentName = item.optString("student_name", "")
            val lessonKind = item.optString("lesson_kind", "")

            scheduleAlarm(
                context = context,
                alarmManager = alarmManager,
                id = id,
                triggerAt = triggerAt,
                itemType = itemType,
                title = title,
                studentName = studentName,
                lessonKind = lessonKind
            )
            scheduledIds.add(id)
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_SCHEDULED_IDS, scheduledIds)
            .apply()

        Log.d(TAG, "Scheduled reminders: ${scheduledIds.size}")
    }

    private fun containsItem(items: JSONArray, id: String): Boolean {
        for (i in 0 until items.length()) {
            if (items.optJSONObject(i)?.optString("item_id") == id) return true
        }
        return false
    }

    private fun scheduleAlarm(
        context: Context,
        alarmManager: AlarmManager,
        id: String,
        triggerAt: Long,
        itemType: String,
        title: String,
        studentName: String,
        lessonKind: String
    ) {
        val intent = Intent(context, ScheduleNotificationReceiver::class.java).apply {
            action = "com.itdeti.SCHEDULE_REMINDER"
            data = android.net.Uri.parse("itdeti://schedule/$id")
            putExtra("item_id", id)
            putExtra("item_type", itemType)
            putExtra("title", title)
            putExtra("student_name", studentName)
            putExtra("lesson_kind", lessonKind)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            stableRequestCode(id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
        }
    }

    private fun cancelAlarm(context: Context, alarmManager: AlarmManager, id: String) {
        val intent = Intent(context, ScheduleNotificationReceiver::class.java).apply {
            action = "com.itdeti.SCHEDULE_REMINDER"
            data = android.net.Uri.parse("itdeti://schedule/$id")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            stableRequestCode(id),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun stableRequestCode(id: String): Int {
        val hash = id.hashCode()
        return if (hash == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(hash)
    }

    private fun parseInstant(value: String): Instant? {
        if (value.isBlank()) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(value).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
