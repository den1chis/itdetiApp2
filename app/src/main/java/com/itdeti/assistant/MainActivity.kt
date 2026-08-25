package com.itdeti.assistant

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_EVENT_ID = "event_id"
        private const val WEB_URL = "https://den1chis.github.io/itdetiWeb"
    }

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val source = intent?.getStringExtra("source") ?: return
            val sender = intent.getStringExtra("sender") ?: ""
            val message = intent.getStringExtra("message") ?: ""
            val entry = "[$source] $sender:\n$message\n\n"
            val current = prefs.getString("log", "") ?: ""
            prefs.edit().putString("log", entry + current).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("itdeti_log", Context.MODE_PRIVATE)

        webView = findViewById(R.id.webView)
        setupWebView()

        startNotificationService()
        requestNotificationPermission()
        checkPermissionStatus()
    }

    private fun setupWebView() {
        webView.webViewClient = WebViewClient()
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        loadRequestedPage(intent)
    }

    private fun loadRequestedPage(sourceIntent: Intent?) {
        val eventId = sourceIntent?.getStringExtra(EXTRA_EVENT_ID)
        if (eventId.isNullOrBlank()) {
            webView.loadUrl(WEB_URL)
            return
        }

        val encodedId = Uri.encode(eventId)
        webView.loadUrl("$WEB_URL?event_id=$encodedId")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::webView.isInitialized) {
            loadRequestedPage(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            notificationReceiver,
            IntentFilter("com.itdeti.NOTIFICATION_RECEIVED"),
            RECEIVER_EXPORTED
        )
        val listenerGranted = checkPermissionStatus()
        if (listenerGranted) {
            requestExactAlarmPermission()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(notificationReceiver)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun startNotificationService() {
        val serviceIntent = Intent(this, NotificationService::class.java)
        startForegroundService(serviceIntent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }

    private fun checkPermissionStatus(): Boolean {
        val granted = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)

        if (!granted) {
            Toast.makeText(this, "Выдайте доступ к уведомлениям!", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        return granted
    }

    private fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val alarmManager = getSystemService(AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) return

        val promptPrefs = getSharedPreferences("itdeti_settings", Context.MODE_PRIVATE)
        if (promptPrefs.getBoolean("exact_alarm_prompted", false)) return

        promptPrefs.edit().putBoolean("exact_alarm_prompted", true).apply()
        Toast.makeText(
            this,
            "Разрешите точные будильники для напоминаний за 30 минут",
            Toast.LENGTH_LONG
        ).show()

        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
        }
    }
}
