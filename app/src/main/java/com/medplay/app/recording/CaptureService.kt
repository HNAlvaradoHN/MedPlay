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
import android.os.SystemClock
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
    private var activeProfile: RecordingProfile? = null
    private var sessionStartedUs = Long.MIN_VALUE
    private var maxThermalStatus = 0
    private var sessionAudioWasActive = false
    private val exportInProgress = AtomicBoolean(false)
    private val shutdownStarted = AtomicBoolean(false)
    private val shutdownResourcesReleased = AtomicBoolean(false)

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
        if (shutdownStarted.get() && intent?.action != ACTION_STOP) {
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> return startCapture(intent)
            ACTION_SAVE -> {
                if (rollingWriter == null) {
                    if (mediaProjection == null) stopSelf(startId)
                    return START_NOT_STICKY
                }
                val requestedSeconds = if (intent.hasExtra(EXTRA_SAVE_SECONDS)) {
                    intent.getIntExtra(EXTRA_SAVE_SECONDS, DEFAULT_SAVE_SECONDS)
                } else {
                    ClipLengthPreference.get(applicationContext)
                }
                saveRetroactiveClip(requestedSeconds)
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
            ACTION_STOP -> {
                stopProjectionAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    private fun startCapture(intent: Intent): Int {
        if (shutdownStarted.get()) {
            return START_NOT_STICKY
        }
        if (mediaProjection != null) {
            updateCaptureNotification(
                tr(
                    "La grabación ya está activa.",
                    "Capture is already active.",
                )
            )
            return START_NOT_STICKY
        }
        if (exportInProgress.get()) {
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

        releaseActiveProjectionForRestart()

        var createdProjection: MediaProjection? = null
        val projection = runCatching {
            manager.getMediaProjection(resultCode, resultData).also { newProjection ->
                createdProjection = newProjection
                newProjection.registerCallback(
                    projectionCallback,
                    Handler(Looper.getMainLooper()),
                )
            }
        }.getOrElse {
            runCatching { createdProjection?.stop() }
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = projection

        val profile = runCatching {
            EncoderCapabilityProbe.chooseSafeStartupProfile(
                RecordingQualityPreference.get(applicationContext)
            )
        }.getOrNull()
        if (profile == null) {
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }

        val internalAudioEnabled =
            InternalAudioPreference.isEnabled(applicationContext) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        val retention = RecordingRetentionPolicy.choose(
            videoBitrate = profile.bitrate,
            audioBitrate = if (internalAudioEnabled) {
                InternalAudioCapture.AAC_BITRATE
            } else {
                0
            },
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
            runCatching { encoder.stop() }
            runCatching { segmentWriter.close() }
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }

        val densityDpi = resources.displayMetrics.densityDpi
        virtualDisplay = runCatching {
            mediaProjection?.createVirtualDisplay(
                "MedPlayCapture",
                profile.width,
                profile.height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderSurface,
                null,
                null,
            )
        }.getOrNull()

        if (virtualDisplay == null) {
            runCatching { encoder.stop() }
            runCatching { segmentWriter.close() }
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }

        rollingWriter = segmentWriter
        videoEncoder = encoder
        activeProfile = profile
        sessionStartedUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        maxThermalStatus = 0
        sessionAudioWasActive = false

        val startedThermalGuard = runCatching {
            RuntimeThermalGuard(applicationContext) { action, status ->
                maxThermalStatus = maxOf(maxThermalStatus, status)
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
        }.getOrNull()

        if (startedThermalGuard == null) {
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }
        thermalGuard = startedThermalGuard

        startInternalAudioIfPermitted(mediaProjection)
        showFloatingControl()
        updateCaptureNotification(
            buildActiveCaptureStatus(profile)
        )

        return START_NOT_STICKY
    }

    private fun saveRetroactiveClip(seconds: Int) {
        if (!exportInProgress.compareAndSet(false, true)) {
            updateCaptureNotification(
                tr(
                    "Ya se está guardando un clip.",
                    "Clip save already in progress.",
                )
            )
            return
        }

        val writer = rollingWriter
        if (writer == null) {
            exportInProgress.set(false)
            return
        }

        val safeSeconds = seconds.coerceIn(MIN_SAVE_SECONDS, MAX_SAVE_SECONDS)
        updateCaptureNotification("Saving previous $safeSeconds seconds...")
        val completionVersion = writer.completionVersion()
        writer.requestRolloverOnNextSyncFrame()
        val syncFrameRequested = videoEncoder?.requestSyncFrame() == true

        thread(
            start = true,
            isDaemon = true,
            name = "medplay-clip-export",
        ) {
            var lease: RollingVideoSegmentWriter.SegmentLease? = null
            var audioLease: RollingAudioSegmentWriter.SegmentLease? = null

            try {
                if (syncFrameRequested) {
                    writer.awaitCompletionAfter(
                        version = completionVersion,
                        timeoutMs = SAVE_ROLLOVER_WAIT_MS,
                    )
                }

                lease = writer.leaseCompleted(safeSeconds * 1_000_000L)
                if (lease == null) {
                    Handler(Looper.getMainLooper()).post {
                        updateCaptureNotification(
                            "Not enough completed recording data yet."
                        )
                    }
                    return@thread
                }

                val videoStartUs = lease.segments.first().sourceStartUs
                val videoEndUs = lease.segments.last().sourceEndUs
                val audioWriter = rollingAudioWriter
                if (audioWriter != null) {
                    runCatching { audioWriter.finalizeActiveSegmentForExport() }
                    audioLease = audioWriter.leaseRange(videoStartUs, videoEndUs)
                }

                val result = RetroactiveVideoExporter(applicationContext).export(
                    videoLease = lease,
                    audioLease = audioLease,
                )
                Handler(Looper.getMainLooper()).post {
                    val durationSeconds = result.durationUs / 1_000_000L
                    updateCaptureNotification(
                        "Clip saved (${durationSeconds}s). Recording continues."
                    )
                }
            } catch (_: Throwable) {
                Handler(Looper.getMainLooper()).post {
                    updateCaptureNotification(
                        "Clip save failed. Recording continues."
                    )
                }
                audioLease?.close()
                lease?.close()
            } finally {
                exportInProgress.set(false)
                finishServiceIfShutdownReady()
            }
        }
    }

    private fun startInternalAudioIfPermitted(projection: MediaProjection?) {
        if (projection == null) return
        if (!InternalAudioPreference.isEnabled(applicationContext)) return
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
            onFatalError = {
                Handler(Looper.getMainLooper()).post {
                    disableInternalAudio()
                }
            },
        )
        val started = runCatching {
            audioCapture.start()
            true
        }.getOrDefault(false)

        if (started) {
            rollingAudioWriter = audioWriter
            internalAudioCapture = audioCapture
            sessionAudioWasActive = true
        } else {
            audioCapture.stop()
            runCatching { audioWriter.close() }
        }
    }

    private fun disableInternalAudio() {
        internalAudioCapture?.stop()
        internalAudioCapture = null

        runCatching { rollingAudioWriter?.close() }
        rollingAudioWriter = null
    }

    private fun showFloatingControl() {
        if (mediaProjection == null) return
        if (floatingClipControl != null) return

        val control = FloatingClipControl(applicationContext) {
            saveRetroactiveClip(
                ClipLengthPreference.get(applicationContext)
            )
        }
        if (control.show()) {
            floatingClipControl = control
        } else {
            control.close()
        }
    }

    override fun onDestroy() {
        if (!shutdownStarted.get()) {
            releaseActiveProjectionForShutdown()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopProjectionAndSelf() {
        if (!shutdownStarted.compareAndSet(false, true)) return

        val projection = mediaProjection
        mediaProjection = null
        runCatching { projection?.unregisterCallback(projectionCallback) }

        floatingClipControl?.close()
        floatingClipControl = null

        thread(
            start = true,
            isDaemon = true,
            name = "medplay-recorder-shutdown",
        ) {
            try {
                releaseVideoPipeline()
                runCatching { projection?.stop() }
            } finally {
                shutdownResourcesReleased.set(true)
                finishServiceIfShutdownReady()
            }
        }
    }

    private fun finishServiceIfShutdownReady() {
        if (!shutdownStarted.get()) return
        if (!shutdownResourcesReleased.get()) return
        if (exportInProgress.get()) return

        Handler(Looper.getMainLooper()).post {
            if (
                shutdownStarted.get() &&
                shutdownResourcesReleased.get() &&
                !exportInProgress.get()
            ) {
                stopSelf()
            }
        }
    }

    private fun releaseActiveProjectionForRestart() {
        val projection = mediaProjection
        mediaProjection = null

        runCatching { projection?.unregisterCallback(projectionCallback) }
        releaseVideoPipeline()
        runCatching { projection?.stop() }
    }

    private fun releaseActiveProjectionForShutdown() {
        val projection = mediaProjection
        mediaProjection = null

        runCatching { projection?.unregisterCallback(projectionCallback) }
        releaseVideoPipeline()
        runCatching { projection?.stop() }
    }

    private fun releaseVideoPipeline() {
        runCatching { persistSessionDiagnostics() }

        runCatching { floatingClipControl?.close() }
        floatingClipControl = null

        runCatching { internalAudioCapture?.stop() }
        internalAudioCapture = null

        runCatching { rollingAudioWriter?.close() }
        rollingAudioWriter = null

        runCatching { thermalGuard?.close() }
        thermalGuard = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { videoEncoder?.stop() }
        videoEncoder = null

        runCatching { rollingWriter?.close() }
        rollingWriter = null
    }

    private fun persistSessionDiagnostics() {
        val profile = activeProfile ?: return
        val startedUs = sessionStartedUs
        val encoder = videoEncoder ?: return
        if (startedUs == Long.MIN_VALUE) return

        activeProfile = null
        sessionStartedUs = Long.MIN_VALUE

        val diagnostics = RecordingSessionDiagnostics(
            startedMonotonicUs = startedUs,
            endedMonotonicUs = SystemClock.elapsedRealtimeNanos() / 1_000L,
            profile = profile,
            retentionSeconds = (activeRetentionUs / 1_000_000L).toInt(),
            maxThermalStatus = maxThermalStatus,
            audioWasActive = sessionAudioWasActive,
            encoderHealth = encoder.healthSnapshot(),
        )

        maxThermalStatus = 0
        sessionAudioWasActive = false

        runCatching {
            File(filesDir, LAST_SESSION_DIAGNOSTICS_FILE)
                .writeText(diagnostics.toText())
        }
    }

    private fun buildActiveCaptureStatus(profile: RecordingProfile): String {
        val retentionSeconds = activeRetentionUs / 1_000_000L
        val audioLabel = if (sessionAudioWasActive) {
            "audio"
        } else {
            "video only"
        }
        return "${profile.width}x${profile.height} @ ${profile.fps} • " +
            "${retentionSeconds}s • $audioLabel"
    }

    private fun updateCaptureNotification(text: String) {
        if (mediaProjection == null) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
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

    private fun buildNotification(
        contentText: String = "Screen capture is active.",
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val saveIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).apply {
                action = ACTION_SAVE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CaptureService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("MedPlay")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Clip",
                    saveIntent,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stopIntent,
                ).build()
            )
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
        private const val ACTION_STOP = "com.medplay.app.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_SAVE_SECONDS = "save_seconds"

        private const val DEFAULT_SAVE_SECONDS = 30
        private const val MIN_SAVE_SECONDS = 5
        private const val MAX_SAVE_SECONDS = 300
        private const val SAVE_ROLLOVER_WAIT_MS = 750L
        private const val LAST_SESSION_DIAGNOSTICS_FILE =
            "last_session_diagnostics.txt"

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

        fun createStopIntent(context: Context): Intent {
            return Intent(context, CaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }

        fun readLastSessionDiagnostics(context: Context): String? {
            val file = File(context.filesDir, LAST_SESSION_DIAGNOSTICS_FILE)
            if (!file.isFile) return null
            return runCatching { file.readText() }.getOrNull()
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
