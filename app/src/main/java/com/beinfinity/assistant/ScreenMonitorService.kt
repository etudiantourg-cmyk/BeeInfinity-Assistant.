package com.beinfinity.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMonitorService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val SERVICE_CHANNEL_ID = "screen_monitor"
        private const val ALERT_CHANNEL_ID = "screen_alerts"
        private const val SERVICE_NOTIFICATION_ID = 71
        private const val ALERT_NOTIFICATION_ID = 72
        private const val FRAME_INTERVAL_MS = 400L
        private val TARGET_PATTERN = Regex("""(?<![0-9])1(?:[.,]0{1,2})?\\s*[X×](?![A-Z0-9])""")
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val processing = AtomicBoolean(false)
    @Volatile private var armed = true
    @Volatile private var lastFrameAt = 0L
    @Volatile private var destroyed = false
    @Volatile private var recognizerClosed = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        try {
            val ongoing = serviceNotification("تحليل الشاشة مباشر — المراقبة نشطة")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    SERVICE_NOTIFICATION_ID,
                    ongoing,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, ongoing)
            }
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (data == null || resultCode != android.app.Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            beginCapture(resultCode, data)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        require(width > 0 && height > 0) { "Invalid display dimensions" }

        worker = HandlerThread("screen-ocr").also { it.start() }
        handler = Handler(worker!!.looper)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader!!.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < FRAME_INTERVAL_MS || !processing.compareAndSet(false, true)) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = source.acquireLatestImage()
            if (image == null) {
                processing.set(false)
                return@setOnImageAvailableListener
            }
            lastFrameAt = now
            processFrame(image)
        }, handler)

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection could not be created")

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        virtualDisplay = projection?.createVirtualDisplay(
            "BeeInfinityScreen",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler
        ) ?: throw IllegalStateException("Virtual display could not be created")
    }

    private fun processFrame(image: Image) {
        var cropped: Bitmap? = null
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            require(pixelStride > 0 && rowStride >= pixelStride * image.width) {
                "Invalid image plane layout"
            }
            val paddedWidth = rowStride / pixelStride
            val paddedBitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            paddedBitmap.copyPixelsFromBuffer(plane.buffer)
            cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height)
            paddedBitmap.recycle()
        } catch (_: Exception) {
            // A malformed frame is skipped; the next frame can still be processed.
        } finally {
            image.close()
        }

        val bitmap = cropped
        if (bitmap == null) {
            processing.set(false)
            return
        }

        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val normalized = result.text.uppercase(Locale.ROOT).replace(',', '.')
                    val found = TARGET_PATTERN.containsMatchIn(normalized)
                    if (found && armed) {
                        armed = false
                        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        manager.notify(
                            ALERT_NOTIFICATION_ID,
                            alertNotification("تم رصد 1X / 1.00X على الشاشة")
                        )
                    } else if (!found) {
                        armed = true
                    }
                }
                .addOnCompleteListener {
                    bitmap.recycle()
                    processing.set(false)
                    if (destroyed) closeRecognizerOnce()
                }
        } catch (_: Exception) {
            bitmap.recycle()
            processing.set(false)
            if (destroyed) closeRecognizerOnce()
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "حالة مراقبة الشاشة",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "تنبيهات الرصد",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun serviceNotification(message: String): Notification =
        NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("BeeInfinity Assistant")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun alertNotification(message: String): Notification =
        NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("BeeInfinity Assistant")
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()

    private fun closeRecognizerOnce() {
        if (!recognizerClosed) {
            synchronized(this) {
                if (!recognizerClosed) {
                    recognizer.close()
                    recognizerClosed = true
                }
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        projection?.unregisterCallback(object : MediaProjection.Callback() {})
        projection?.stop()
        projection = null
        worker?.quitSafely()
        worker = null
        if (!processing.get()) closeRecognizerOnce()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
