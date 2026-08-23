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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "itdeti_NS"
        private const val SERVER_URL = "https://itdeti.onrender.com/notifications"
        private const val LOGIN_URL = "https://itdeti.onrender.com/auth/login"
        private const val ALERT_CHANNEL_ID = "itdeti_alerts"
        private const val ALERT_NOTIFICATION_ID = 2001
        private const val DEDUPE_WINDOW_MS = 5_000L

        private val TARGET_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "kz.kaspi.mobile",
            "kz.kaspi.bank",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "org.telegram.messenger"
        )

        const val FOREGROUND_CHANNEL_ID = "itdeti_foreground"
        const val FOREGROUND_NOTIFICATION_ID = 1001
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
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
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val body = bigText.ifEmpty { text }

        if (body.isBlank()) return
        if (isDuplicate(packageName, title, body)) {
            Log.d(TAG, "Duplicate notification ignored: $packageName / $title")
            return
        }

        val source = when {
            packageName.contains("whatsapp") -> "whatsapp"
            packageName.contains("kaspi") -> "kaspi"
            packageName.contains("messaging") || packageName.contains("messages") -> "sms"
            packageName.contains("telegram") -> "internal"
            else -> return
        }

        Log.d(TAG, "[$source] $title: $body")
        saveToLog(source, title, body)

        val broadcastIntent = Intent("com.itdeti.NOTIFICATION_RECEIVED").apply {
            setPackage(applicationContext.packageName)
            putExtra("source", source)
            putExtra("sender", title)
            putExtra("message", body)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(broadcastIntent)

        sendToServer(source, title, body)
    }

    private fun isDuplicate(packageName: String, title: String, body: String): Boolean {
        val fingerprintSource = "$packageName\u0000$title\u0000$body"
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(fingerprintSource.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val prefs = getSharedPreferences("itdeti_notification_state", Context.MODE_PRIVATE)
        val lastFingerprint = prefs.getString("last_fingerprint", null)
        val lastTime = prefs.getLong("last_time", 0L)
        val now = System.currentTimeMillis()

        val duplicate = lastFingerprint == fingerprint && now - lastTime < DEDUPE_WINDOW_MS

        if (!duplicate) {
            prefs.edit()
                .putString("last_fingerprint", fingerprint)
                .putLong("last_time", now)
                .apply()
        }

        return duplicate
    }

    private fun saveToLog(source: String, sender: String, message: String) {
        val prefs = getSharedPreferences("itdeti_log", Context.MODE_PRIVATE)
        val entry = "[$source] $sender:\n$message\n\n"
        val current = prefs.getString("log", "") ?: ""
        prefs.edit().putString("log", entry + current).apply()
    }

    private fun getToken(): String {
        if (authToken.isNotBlank()) return authToken

        val email = BuildConfig.ITDETI_EMAIL
        val password = BuildConfig.ITDETI_PASSWORD

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
                val responseBody = response.body?.string() ?: ""
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

                val response = sendRequest(token, source, sender, message)

                if (response.code == 401) {
                    response.close()
                    authToken = ""
                    token = getToken()
                    if (token.isBlank()) return@launch

                    val retryResponse = sendRequest(token, source, sender, message)
                    handleServerResponse(retryResponse)
                    return@launch
                }

                handleServerResponse(response)
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }
    }

    private fun sendRequest(
        token: String,
        source: String,
        sender: String,
        message: String
    ): okhttp3.Response {
        val json = JSONObject().apply {
            put("source", source)
            put("sender_name", sender)
            put("raw_text", message)
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(SERVER_URL)
            .addHeader("Authorization", "Bearer $token")
            .post(body)
            .build()

        return client.newCall(request).execute()
    }

    private fun handleServerResponse(response: okhttp3.Response) {
        response.use {
            val responseBody = it.body?.string() ?: ""
            Log.d(TAG, "Server response: ${it.code} $responseBody")

            if (it.code !in 200..201 || responseBody.isBlank()) return

            val responseJson = JSONObject(responseBody)
            val requiresConfirmation = responseJson.optBoolean("requires_confirmation", false)
            val aiSummary = responseJson.optString("ai_summary", "Уведомление получено")

            if (requiresConfirmation) {
                vibrate(longArrayOf(0, 200, 100, 200, 100, 200))
                showPushNotification(aiSummary)
            } else {
                vibrate(longArrayOf(0, 50))
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