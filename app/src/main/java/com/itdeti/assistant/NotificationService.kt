package com.itdeti.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        private const val SERVER_URL = "https://itdeti.onrender.com/notifications"
        private const val LOGIN_URL = "https://itdeti.onrender.com/auth/login"
        private const val EMAIL = "sdenmansss@gmail.com"
        private const val PASSWORD = "GhjcnjqDen2552!"
        private const val ALERT_CHANNEL_ID = "itdeti_alerts"
        private const val ALERT_NOTIFICATION_ID = 2001
        private val TARGET_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "kz.kaspi.mobile",
            "kz.kaspi.bank",
            "org.telegram.messenger"
        )
        const val FOREGROUND_CHANNEL_ID = "itdeti_foreground"
        const val FOREGROUND_NOTIFICATION_ID = 1001
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var authToken: String = ""

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        createAlertChannel()
        Log.d(TAG, "NotificationService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val packageName = sbn.packageName
        if (packageName !in TARGET_PACKAGES) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val body = bigText.ifEmpty { text }
        if (body.isBlank()) return

        val source = when {
            packageName.contains("whatsapp") -> "whatsapp"
            packageName.contains("kaspi") -> "kaspi"
            packageName.contains("telegram") -> "telegram"
            else -> packageName
        }

        Log.d(TAG, "[$source] $title: $body")
        saveToLog(source, title, body)

        val broadcastIntent = Intent("com.itdeti.NOTIFICATION_RECEIVED").apply {
            putExtra("source", source)
            putExtra("sender", title)
            putExtra("message", body)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(broadcastIntent)

        sendToServer(source, title, body)
    }

    private fun saveToLog(source: String, sender: String, message: String) {
        val prefs = getSharedPreferences("itdeti_log", Context.MODE_PRIVATE)
        val entry = "[$source] $sender:\n$message\n\n"
        val current = prefs.getString("log", "") ?: ""
        prefs.edit().putString("log", entry + current).apply()
    }

    private fun getToken(): String {
        if (authToken.isNotBlank()) return authToken
        try {
            val json = JSONObject().apply {
                put("email", EMAIL)
                put("password", PASSWORD)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(LOGIN_URL)
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            response.close()
            if (response.code == 200) {
                authToken = JSONObject(responseBody).getString("access_token")
                Log.d(TAG, "Token received")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error: ${e.message}")
        }
        return authToken
    }

    private fun sendToServer(source: String, sender: String, message: String) {
        scope.launch {
            try {
                val token = getToken()
                val json = JSONObject().apply {
                    put("source", source)
                    put("sender", sender)
                    put("raw_text", message)
                    put("timestamp", System.currentTimeMillis())
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(SERVER_URL)
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                Log.d(TAG, "Server response: ${response.code} $responseBody")
                response.close()

                if (response.code in 200..201 && responseBody.isNotBlank()) {
                    val responseJson = JSONObject(responseBody)
                    val requiresConfirmation = responseJson.optBoolean("requires_confirmation", false)
                    val aiSummary = responseJson.optString("ai_summary", "")

                    if (requiresConfirmation) {
                        vibrate(longArrayOf(0, 200, 100, 200, 100, 200))
                        showPushNotification(aiSummary)
                    } else {
                        vibrate(longArrayOf(0, 50))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun showPushNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("itdeti Assistant")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun createAlertChannel() {
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "itdeti Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Уведомления требующие внимания"
            enableVibration(true)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, NotificationService::class.java))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val restartIntent = Intent(applicationContext, NotificationService::class.java)
        startService(restartIntent)
    }

    private fun startForegroundService() {
        val channel = NotificationChannel(
            FOREGROUND_CHANNEL_ID,
            "itdeti Background Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("itdeti Assistant")
            .setContentText("Мониторинг уведомлений активен")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(FOREGROUND_NOTIFICATION_ID, notification)
    }
}