package com.medplay.app.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import com.medplay.app.MainActivity

class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: HardwareVideoEncoder? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopProjectionAndSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsProjectionForegroundService()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = intent.getParcelableExtraCompat(EXTRA_RESULT_DATA)

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()

        mediaProjection = manager.getMediaProjection(resultCode, resultData).also { projection ->
            projection.registerCallback(
                projectionCallback,
                Handler(Looper.getMainLooper()),
            )
        }

        val profile = EncoderCapabilityProbe.chooseSafeStartupProfile()
        if (profile == null) {
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }

        val encoder = HardwareVideoEncoder(profile)
        val encoderSurface = runCatching { encoder.start() }.getOrElse {
            encoder.stop()
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }

        val densityDpi = resources.displayMetrics.densityDpi
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MedPlayCapture",
            profile.width,
            profile.height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            encoderSurface,
            null,
            null,
        )

        if (virtualDisplay == null) {
            encoder.stop()
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }

        videoEncoder = encoder

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseVideoPipeline()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopProjectionAndSelf() {
        releaseVideoPipeline()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection = null
        stopSelf()
    }

    private fun releaseVideoPipeline() {
        virtualDisplay?.release()
        virtualDisplay = null

        videoEncoder?.stop()
        videoEncoder = null
    }

    private fun startAsProjectionForegroundService() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MedPlay")
            .setContentText("Screen capture is active.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MedPlay capture",
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    companion object {
        private const val CHANNEL_ID = "medplay_capture"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.medplay.app.action.START_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun createStartIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
        ): Intent {
            return Intent(context, CaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
        }
    }
}

private fun Intent.getParcelableExtraCompat(key: String): Intent? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key) as? Intent
    }
}
