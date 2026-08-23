package com.itdeti.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "itdeti_NS"
        private const val LOGIN_URL = "https://itdeti.onrender.com/auth/login"
        private const val NOTIFICATIONS_URL = "https://itdeti.onrender.com/notifications"
        private const val CHANNEL_ID = "itdeti_assistant"
        private const val FOREGROUND_ID = 1001
        private const val DUPLICATE_WINDOW_MS = 10_000L

        @Volatile
        private var authToken = ""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NotificationService started")
        createNotificationChannel()
        startForegroundNotification()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val packageName = sbn.packageName
        if (packageName == applicationContext.packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: Bundle.EMPTY

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val message = if (bigText.isNotBlank()) bigText else text

        if (message.isBlank() && title.isBlank()) return
        if (isDuplicate(packageName, title, message)) return

        val source = detectSource(packageName)
        Log.d(TAG, "Notification: source=$source title=$title text=$message")
        saveToLog(source, title, message)
        sendToServer(source, title, message)
    }

    private fun detectSource(packageName: String): String {
        return when {
            packageName.contains("whatsapp", ignoreCase = true) -> "whatsapp"
            packageName.contains("telegram", ignoreCase = true) -> "internal"
            packageName.contains("mms", ignoreCase = true) ||
                packageName.contains("messaging", ignoreCase = true) ||
                packageName.contains("messages", ignoreCase = true) -> "sms"
            packageName.contains("kaspi", ignoreCase = true) -> "kaspi"
            else -> "android"
        }
    }

    private fun isDuplicate(source: String, sender: String, message: String): Boolean {
        val key = "$source|$sender|$message"
        val prefs = getSharedPreferences("itdeti_notification_dedupe", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previous = prefs.getLong(key.hashCode().toString(), 0L)
        if (now - previous < DUPLICATE_WINDOW_MS) return true
        prefs.edit().putLong(key.hashCode().toString(), now).apply()
        return false
    }

    private fun saveToLog(source: String, sender: String, message: String) {
        val prefs = getSharedPreferences("itdeti_log", Context.MODE_PRIVATE)
        val entry = "[$source] $sender:\n$message\n\n"
        val current = prefs.getString("log", "") ?: ""
        prefs.edit().putString("log", entry + current).apply()
    }

    private fun getToken(): String {
        if (authToken.isNotBlank()) return authToken

        val email: String = BuildConfig.ITDETI_EMAIL
        val password: String = BuildConfig.ITDETI_PASSWORD

        if (email.isBlank() || password.isBlank()) {
            Log.e(TAG, "Не заданы ITDETI_EMAIL / ITDETI_PASSWORD в local.properties")
            return ""
        }

        return try {
            val json = JSONObject().apply {
                put("email", email)
                put("password", password)
            }

            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(LOGIN_URL)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.code == 200) {
                    authToken = JSONObject(responseBody).optString("access_token", "")
                    Log.d(TAG, "Token received")
                } else {
                    Log.e(TAG, "Login error: ${response.code} $responseBody")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
        }

        return authToken
    }

    private fun sendToServer(source: String, sender: String, message: String) {
        scope.launch {
            try {
                var token = getToken()
                if (token.isBlank()) return@launch

                val json = JSONObject().apply {
                    put("source", source)
                    put("sender_name", sender.ifBlank { null })
                    put("raw_text", message)
                }

                val body = json.toString().toRequestBody("application/json".toMediaType())
                var request = Request.Builder()
                    .url(NOTIFICATIONS_URL)
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    Log.d(TAG, "Server response: ${response.code} $responseBody")

                    if (response.code == 401) {
                        authToken = ""
                        token = getToken()
                        if (token.isBlank()) return@use

                        request = Request.Builder()
                            .url(NOTIFICATIONS_URL)
                            .addHeader("Authorization", "Bearer $token")
                            .post(body)
                            .build()

                        client.newCall(request).execute().use { retryResponse ->
                            val retryBody = retryResponse.body?.string().orEmpty()
                            Log.d(TAG, "Server retry response: ${retryResponse.code} $retryBody")
                            showResultNotification(retryResponse.code in 200..299)
                        }
                    } else {
                        showResultNotification(response.code in 200..299)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send notification error", e)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ITdeti Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ITdeti Assistant")
            .setContentText("Мониторинг уведомлений включён")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(FOREGROUND_ID, notification)
    }

    private fun showResultNotification(success: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ITdeti Assistant")
            .setContentText(
                if (success) "Уведомление обработано" else "Не удалось отправить уведомление"
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
