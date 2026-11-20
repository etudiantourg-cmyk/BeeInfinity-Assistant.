package com.beinfinity.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val service = Intent(this, ScreenMonitorService::class.java).apply {
                    putExtra(ScreenMonitorService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenMonitorService.EXTRA_RESULT_DATA, result.data)
                }
                try {
                    ContextCompat.startForegroundService(this, service)
                    status.text = "المراقبة المباشرة تعمل الآن"
                } catch (_: Exception) {
                    status.text = "تعذر بدء المراقبة. أعد فتح التطبيق وحاول مجدداً."
                }
            } else {
                status.text = "لم تتم الموافقة على مشاركة الشاشة"
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestScreenCapture()
            } else {
                status.text = "يلزم السماح بالإشعارات حتى يصلك تنبيه الرصد"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.startButton).setOnClickListener {
            startMonitoringFlow()
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, ScreenMonitorService::class.java))
            status.text = "المراقبة متوقفة"
        }
    }

    private fun startMonitoringFlow() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }
}
