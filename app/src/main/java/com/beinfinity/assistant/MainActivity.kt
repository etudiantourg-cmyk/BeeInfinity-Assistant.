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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val captureRequest = 4102
    private val notificationRequest = 4103
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), notificationRequest)
            } else {
                requestScreenCapture()
            }
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, ScreenMonitorService::class.java))
            status.text = "Monitoring stopped"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notificationRequest) requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Handled for compatibility with the current MediaProjection flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest && resultCode == Activity.RESULT_OK && data != null) {
            val service = Intent(this, ScreenMonitorService::class.java).apply {
                putExtra(ScreenMonitorService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenMonitorService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, service)
            status.text = "Monitoring screen for 1.00X…"
        } else if (requestCode == captureRequest) {
            status.text = "Screen capture permission was not granted"
        }
    }
}
