package com.itdeti.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    private val notificationReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                val source =
                    intent?.getStringExtra("source")
                        ?: return

                val sender =
                    intent.getStringExtra("sender")
                        ?: ""

                val message =
                    intent.getStringExtra("message")
                        ?: ""

                val entry =
                    "[$source] $sender:\n$message\n\n"

                val current =
                    prefs.getString("log", "")
                        ?: ""

                prefs.edit()
                    .putString(
                        "log",
                        entry + current
                    )
                    .apply()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        prefs =
            getSharedPreferences(
                "itdeti_log",
                Context.MODE_PRIVATE
            )

        webView =
            findViewById(R.id.webView)

        setupWebView()

        checkPermissionStatus()

        /*
         * Запускаем периодическую синхронизацию.
         */
        ScheduleSyncWorkerStarter.schedulePeriodic(
            this
        )

        /*
         * Сразу синхронизируем ближайшие события.
         */
        syncSchedule()
    }

    private fun setupWebView() {

        webView.webViewClient =
            WebViewClient()

        val settings: WebSettings =
            webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)

        webView.loadUrl(
            "https://den1chis.github.io/itdetiWeb"
        )
    }

    override fun onResume() {

        super.onResume()

        registerReceiver(
            notificationReceiver,
            IntentFilter(
                "com.itdeti.NOTIFICATION_RECEIVED"
            ),
            RECEIVER_EXPORTED
        )

        checkPermissionStatus()

        /*
         * Пользователь мог:
         *
         * - перенести урок;
         * - создать событие;
         * - удалить событие;
         * - изменить расписание;
         *
         * пока приложение было свернуто.
         *
         * Поэтому при возвращении синхронизируемся.
         */
        syncSchedule()
    }

    override fun onPause() {

        super.onPause()

        try {
            unregisterReceiver(
                notificationReceiver
            )
        } catch (_: Exception) {
        }
    }

    override fun onBackPressed() {

        if (webView.canGoBack()) {

            webView.goBack()

        } else {

            super.onBackPressed()
        }
    }

    private fun syncSchedule() {

        lifecycleScope.launch(
            Dispatchers.IO
        ) {

            val events =
                ItdetiApi.syncUpcoming(
                    days = 7
                )

            ReminderScheduler.synchronize(
                applicationContext,
                events
            )
        }
    }

    private fun checkPermissionStatus() {

        val granted =
            NotificationManagerCompat
                .getEnabledListenerPackages(this)
                .contains(packageName)

        if (!granted) {

            Toast.makeText(
                this,
                "Выдайте доступ к уведомлениям!",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(
                    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                )
            )
        }
    }
}