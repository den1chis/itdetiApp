package com.itdeti.assistant

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.Instant
import java.util.concurrent.TimeUnit

object ItdetiApi {
    private const val TAG = "itdeti_API"
    private const val BASE_URL = "https://itdeti.onrender.com"
    private const val LOGIN_URL = "$BASE_URL/auth/login"
    private const val UPCOMING_URL = "$BASE_URL/schedule/upcoming"

    private val email: String
        get() = BuildConfig.ITDETI_EMAIL

    private val password: String
        get() = BuildConfig.ITDETI_PASSWORD

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var authToken: String = ""

    fun syncUpcoming(days: Int = 7): List<ScheduleEvent> {
        return try {
            val token = getToken()
            if (token.isBlank()) {
                Log.e(TAG, "Не удалось получить JWT")
                return emptyList()
            }

            val request = Request.Builder()
                .url("$UPCOMING_URL?days=$days")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                Log.d(TAG, "Upcoming response: ${response.code}")
                if (response.code !in 200..299) {
                    Log.e(TAG, "Ошибка получения расписания: $responseBody")
                    return emptyList()
                }
                parseEvents(responseBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка синхронизации", e)
            emptyList()
        }
    }

    private fun getToken(): String {
        if (authToken.isNotBlank()) return authToken
        if (email.isBlank() || password.isBlank()) return ""

        return try {
            val json = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(LOGIN_URL).post(body).build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.code != 200) {
                    Log.e(TAG, "Login error: ${response.code} $responseBody")
                    return ""
                }
                authToken = JSONObject(responseBody).optString("access_token", "")
                Log.d(TAG, "JWT получен")
                authToken
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка авторизации", e)
            ""
        }
    }

    private fun parseEvents(responseBody: String): List<ScheduleEvent> {
        val result = mutableListOf<ScheduleEvent>()
        try {
            val array = when {
                responseBody.trim().startsWith("[") -> JSONArray(responseBody)
                responseBody.trim().startsWith("{") -> {
                    val obj = JSONObject(responseBody)
                    when {
                        obj.has("items") -> obj.getJSONArray("items")
                        obj.has("events") -> obj.getJSONArray("events")
                        obj.has("data") -> obj.getJSONArray("data")
                        else -> JSONArray()
                    }
                }
                else -> JSONArray()
            }

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val itemId = obj.optString("item_id", obj.optString("id", ""))
                if (itemId.isBlank()) continue
                val itemType = obj.optString("item_type", obj.optString("type", "event"))
                val title = obj.optString("title", if (itemType == "lesson") "Урок" else "Событие")
                val studentName = obj.optString("student_name", null)
                val lessonKind = obj.optString("lesson_kind", null)
                val startTimeString = obj.optString("start_time", "")
                if (startTimeString.isBlank()) continue
                val startTime = parseDateTime(startTimeString)
                if (startTime <= 0L) continue
                val endTime = obj.optString("end_time", "")
                    .takeIf { it.isNotBlank() }
                    ?.let { parseDateTime(it) }

                result.add(
                    ScheduleEvent(
                        itemId = itemId,
                        itemType = itemType,
                        title = title,
                        studentName = studentName,
                        startTime = startTime,
                        endTime = endTime,
                        lessonKind = lessonKind
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка разбора расписания", e)
        }
        return result
    }

    private fun parseDateTime(value: String): Long {
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    ZonedDateTime.parse(value).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    0L
                }
            }
        }
    }
}
