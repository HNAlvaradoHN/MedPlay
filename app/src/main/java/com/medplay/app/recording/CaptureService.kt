package com.medplay.app.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.StatFs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import com.medplay.app.MainActivity

class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: HardwareVideoEncoder? = null
    private var rollingWriter: RollingVideoSegmentWriter? = null
    private var thermalGuard: RuntimeThermalGuard? = null
    private var internalAudioCapture: InternalAudioCapture? = null
    private var rollingAudioWriter: RollingAudioSegmentWriter? = null
    private var floatingClipControl: FloatingClipControl? = null
    private var activeRetentionUs: Long = RollingVideoSegmentWriter.DEFAULT_RETENTION_US
    private val exportInProgress = AtomicBoolean(false)

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
        when (intent?.action) {
            ACTION_START -> return startCapture(intent)
            ACTION_SAVE -> {
                if (rollingWriter == null) {
                    if (mediaProjection == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                saveRetroactiveClip(
                    intent.getIntExtra(EXTRA_SAVE_SECONDS, DEFAULT_SAVE_SECONDS)
                )
                return START_NOT_STICKY
            }
            ACTION_SHOW_OVERLAY -> {
                if (mediaProjection == null) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                showFloatingControl()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun startCapture(intent: Intent): Int {
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

        val retention = RecordingRetentionPolicy.choose(
            videoBitrate = profile.bitrate,
            audioBitrate = InternalAudioCapture.AAC_BITRATE,
            usableBytes = StatFs(cacheDir.absolutePath).availableBytes,
        )
        if (retention.retentionSeconds == 0) {
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }
        activeRetentionUs = retention.retentionSeconds * 1_000_000L

        val segmentWriter = RollingVideoSegmentWriter(
            directory = File(cacheDir, "medplay_rolling_video"),
            retentionUs = activeRetentionUs,
        )
        val encoder = HardwareVideoEncoder(
            profile = profile,
            sampleConsumer = segmentWriter,
            onFatalError = {
                Handler(Looper.getMainLooper()).post {
                    stopProjectionAndSelf()
                }
            },
        )
        val encoderSurface = runCatching { encoder.start() }.getOrElse {
            encoder.stop()
            segmentWriter.close()
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
            segmentWriter.close()
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }

        rollingWriter = segmentWriter
        videoEncoder = encoder

        thermalGuard = RuntimeThermalGuard(applicationContext) { action, _ ->
            if (action.stopCapture) {
                Handler(Looper.getMainLooper()).post {
                    stopProjectionAndSelf()
                }
            } else {
                val targetBitrate = (
                    profile.bitrate.toLong() * action.bitratePercent / 100L
                ).toInt()
                encoder.updateBitrate(targetBitrate)
            }
        }.also { it.start() }

        startInternalAudioIfPermitted(mediaProjection)
        showFloatingControl()

        return START_NOT_STICKY
    }

    private fun saveRetroactiveClip(seconds: Int) {
        if (!exportInProgress.compareAndSet(false, true)) return

        val writer = rollingWriter
        if (writer == null) {
            exportInProgress.set(false)
            return
        }

        val safeSeconds = seconds.coerceIn(MIN_SAVE_SECONDS, MAX_SAVE_SECONDS)
        val lease = writer.leaseCompleted(safeSeconds * 1_000_000L)
        if (lease == null) {
            exportInProgress.set(false)
            return
        }

        val videoStartUs = lease.segments.first().sourceStartUs
        val videoEndUs = lease.segments.last().sourceEndUs
        val audioLease = rollingAudioWriter?.leaseRange(videoStartUs, videoEndUs)

        thread(
            start = true,
            isDaemon = true,
            name = "medplay-clip-export",
        ) {
            try {
                RetroactiveVideoExporter(applicationContext).export(
                    videoLease = lease,
                    audioLease = audioLease,
                )
            } catch (_: Throwable) {
                audioLease?.close()
                lease.close()
            } finally {
                exportInProgress.set(false)
            }
        }
    }

    private fun startInternalAudioIfPermitted(projection: MediaProjection?) {
        if (projection == null) return
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val audioWriter = RollingAudioSegmentWriter(
            directory = File(cacheDir, "medplay_rolling_audio"),
            retentionUs = activeRetentionUs,
        )
        val audioCapture = InternalAudioCapture(
            mediaProjection = projection,
            consumer = audioWriter,
        )
        val started = runCatching {
            audioCapture.start()
            true
        }.getOrDefault(false)

        if (started) {
            rollingAudioWriter = audioWriter
            internalAudioCapture = audioCapture
        } else {
            audioCapture.stop()
            audioWriter.close()
        }
    }

    private fun showFloatingControl() {
        if (mediaProjection == null) return
        if (floatingClipControl != null) return

        val control = FloatingClipControl(applicationContext) {
            saveRetroactiveClip(DEFAULT_SAVE_SECONDS)
        }
        if (control.show()) {
            floatingClipControl = control
        } else {
            control.close()
        }
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
        floatingClipControl?.close()
        floatingClipControl = null

        internalAudioCapture?.stop()
        internalAudioCapture = null

        rollingAudioWriter?.close()
        rollingAudioWriter = null

        thermalGuard?.close()
        thermalGuard = null

        virtualDisplay?.release()
        virtualDisplay = null

        videoEncoder?.stop()
        videoEncoder = null

        rollingWriter?.close()
        rollingWriter = null
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
        private const val ACTION_SAVE = "com.medplay.app.action.SAVE_RETROACTIVE"
        private const val ACTION_SHOW_OVERLAY = "com.medplay.app.action.SHOW_OVERLAY"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_SAVE_SECONDS = "save_seconds"

        private const val DEFAULT_SAVE_SECONDS = 30
        private const val MIN_SAVE_SECONDS = 5
        private const val MAX_SAVE_SECONDS = 300

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

        fun createSaveIntent(
            context: Context,
            seconds: Int,
        ): Intent {
            return Intent(context, CaptureService::class.java).apply {
                action = ACTION_SAVE
                putExtra(EXTRA_SAVE_SECONDS, seconds)
            }
        }

        fun createShowOverlayIntent(context: Context): Intent {
            return Intent(context, CaptureService::class.java).apply {
                action = ACTION_SHOW_OVERLAY
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
