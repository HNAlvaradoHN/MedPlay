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
    private var microphoneVoiceCapture: MicrophoneVoiceCapture? = null
    private var rollingMicrophoneWriter: RollingAudioSegmentWriter? = null
    private var floatingClipControl: FloatingClipControl? = null
    private var activeRetentionUs: Long = RollingVideoSegmentWriter.DEFAULT_RETENTION_US
    private var activeProfile: RecordingProfile? = null
    private var initialSessionProfile: RecordingProfile? = null
    private var activeVideoDirectory: File? = null
    private var videoEpochSequence = 0L
    private var thermalProfileDowngrades = 0
    private var accumulatedEncodedSamples = 0L
    private var accumulatedTimestampRegressions = 0L
    private var accumulatedMaxWallOutputGapUs = 0L
    private var sessionStartedUs = Long.MIN_VALUE
    private var maxThermalStatus = 0
    private var sessionAudioWasActive = false
    private var sessionMicrophoneWasActive = false
    private var sessionMicrophoneWasSilenced = false
    private var microphoneCurrentlySilenced = false
    @Volatile private var pendingCapturedWidth = 0
    @Volatile private var pendingCapturedHeight = 0
    private val exportInProgress = AtomicBoolean(false)
    private val profileSwitchInProgress = AtomicBoolean(false)
    private val shutdownStarted = AtomicBoolean(false)
    private val shutdownResourcesReleased = AtomicBoolean(false)

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopProjectionAndSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            pendingCapturedWidth = width
            pendingCapturedHeight = height
            Handler(Looper.getMainLooper()).post {
                applyCapturedContentOrientation()
            }
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
                floatingClipControl?.close()
                floatingClipControl = null
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

        val microphoneVoiceRequested =
            MicrophoneVoicePreference.isEnabled(applicationContext) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        startAsProjectionForegroundService(
            includeMicrophone = microphoneVoiceRequested,
        )

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
        val microphoneVoiceEnabled =
            MicrophoneVoicePreference.isEnabled(applicationContext) &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        val retention = RecordingRetentionPolicy.choose(
            videoBitrate = profile.bitrate,
            audioBitrate =
                (if (internalAudioEnabled) InternalAudioCapture.AAC_BITRATE else 0) +
                    (if (microphoneVoiceEnabled) {
                        MicrophoneVoiceCapture.AAC_BITRATE
                    } else {
                        0
                    }),
            usableBytes = StatFs(cacheDir.absolutePath).availableBytes,
        )
        if (retention.retentionSeconds == 0) {
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }
        activeRetentionUs = retention.retentionSeconds * 1_000_000L

        clearStaleVideoEpochDirectories()
        val videoDirectory = nextVideoEpochDirectory()
        val segmentWriter = RollingVideoSegmentWriter(
            directory = videoDirectory,
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
        initialSessionProfile = profile
        activeVideoDirectory = videoDirectory
        thermalProfileDowngrades = 0
        accumulatedEncodedSamples = 0L
        accumulatedTimestampRegressions = 0L
        accumulatedMaxWallOutputGapUs = 0L
        sessionStartedUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        maxThermalStatus = 0
        sessionAudioWasActive = false
        sessionMicrophoneWasActive = false
        sessionMicrophoneWasSilenced = false
        microphoneCurrentlySilenced = false

        val startedThermalGuard = runCatching {
            RuntimeThermalGuard(applicationContext) { action, status ->
                maxThermalStatus = maxOf(maxThermalStatus, status)
                if (action.stopCapture) {
                    Handler(Looper.getMainLooper()).post {
                        stopProjectionAndSelf()
                    }
                } else {
                    val currentProfile = activeProfile
                    val currentEncoder = videoEncoder
                    if (currentProfile != null && currentEncoder != null) {
                        val targetBitrate = (
                            currentProfile.bitrate.toLong() *
                                action.bitratePercent / 100L
                        ).toInt()
                        currentEncoder.updateBitrate(targetBitrate)
                    }
                    if (action.requestProfileStepDown) {
                        requestThermalProfileDowngrade(
                            bitratePercent = action.bitratePercent,
                        )
                    }
                }
            }.also { it.start() }
        }.getOrNull()

        if (startedThermalGuard == null) {
            stopProjectionAndSelf()
            return START_NOT_STICKY
        }
        thermalGuard = startedThermalGuard

        startInternalAudioIfPermitted(mediaProjection)
        startMicrophoneVoiceIfPermitted()
        showFloatingControl()
        updateCaptureNotification(
            buildActiveCaptureStatus(profile)
        )
        applyCapturedContentOrientation()

        return START_NOT_STICKY
    }

    private fun saveRetroactiveClip(seconds: Int): Boolean {
        if (profileSwitchInProgress.get()) {
            updateCaptureNotification(
                tr(
                    "MedPlay está ajustando la carga por temperatura. Intenta Clip de nuevo en unos segundos.",
                    "MedPlay is adjusting thermal load. Try Clip again in a few seconds.",
                )
            )
            return false
        }

        if (!exportInProgress.compareAndSet(false, true)) {
            updateCaptureNotification(
                tr(
                    "Ya se está guardando un clip.",
                    "Clip save already in progress.",
                )
            )
            return false
        }

        val writer = rollingWriter
        if (writer == null) {
            exportInProgress.set(false)
            return false
        }

        val safeSeconds = seconds.coerceIn(MIN_SAVE_SECONDS, MAX_SAVE_SECONDS)
        updateCaptureNotification(
            tr(
                "Guardando los últimos " + safeSeconds + " segundos...",
                "Saving previous " + safeSeconds + " seconds...",
            )
        )
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
            var microphoneLease: RollingAudioSegmentWriter.SegmentLease? = null

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
                        floatingClipControl?.showFailureFeedback()
                        updateCaptureNotification(
                            tr(
                                "Todavía no hay suficiente grabación acumulada.",
                                "Not enough completed recording data yet.",
                            )
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

                val microphoneWriter = rollingMicrophoneWriter
                if (microphoneWriter != null) {
                    runCatching {
                        microphoneWriter.finalizeActiveSegmentForExport()
                    }
                    microphoneLease =
                        microphoneWriter.leaseRange(videoStartUs, videoEndUs)
                }

                val result = RetroactiveVideoExporter(applicationContext).export(
                    videoLease = lease,
                    audioLease = audioLease,
                    microphoneLease = microphoneLease,
                )
                ClipLibrary.recordExport(applicationContext, result)
                Handler(Looper.getMainLooper()).post {
                    floatingClipControl?.showSavedFeedback()
                    val durationSeconds = result.durationUs / 1_000_000L
                    updateCaptureNotification(
                        tr(
                            "Clip guardado (" + durationSeconds + "s). La grabación continúa.",
                            "Clip saved (" + durationSeconds + "s). Recording continues.",
                        )
                    )
                }
            } catch (_: Throwable) {
                Handler(Looper.getMainLooper()).post {
                    floatingClipControl?.showFailureFeedback()
                    updateCaptureNotification(
                        tr(
                            "No se pudo guardar el clip. La grabación continúa.",
                            "Clip save failed. Recording continues.",
                        )
                    )
                }
                microphoneLease?.close()
                audioLease?.close()
                lease?.close()
            } finally {
                exportInProgress.set(false)
                finishServiceIfShutdownReady()
            }
        }

        return true
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

    private fun startMicrophoneVoiceIfPermitted() {
        if (!MicrophoneVoicePreference.isEnabled(applicationContext)) return
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val voiceWriter = RollingAudioSegmentWriter(
            directory = File(cacheDir, "medplay_rolling_microphone"),
            retentionUs = activeRetentionUs,
        )
        val voiceCapture = MicrophoneVoiceCapture(
            context = applicationContext,
            consumer = voiceWriter,
            onSilencedChanged = { silenced ->
                Handler(Looper.getMainLooper()).post {
                    handleMicrophoneSilencedChanged(silenced)
                }
            },
            onFatalError = {
                Handler(Looper.getMainLooper()).post {
                    disableMicrophoneVoice()
                }
            },
        )
        val started = runCatching {
            voiceCapture.start()
            true
        }.getOrDefault(false)

        if (started) {
            rollingMicrophoneWriter = voiceWriter
            microphoneVoiceCapture = voiceCapture
            sessionMicrophoneWasActive = true
        } else {
            voiceCapture.stop()
            runCatching { voiceWriter.close() }
        }
    }

    private fun handleMicrophoneSilencedChanged(silenced: Boolean) {
        if (microphoneVoiceCapture == null && !sessionMicrophoneWasActive) return
        if (microphoneCurrentlySilenced == silenced) return

        microphoneCurrentlySilenced = silenced
        if (silenced) {
            sessionMicrophoneWasSilenced = true
            floatingClipControl?.showMicrophoneBlocked(
                tr(
                    "Micrófono usado por el juego • tu voz no se está grabando",
                    "Microphone used by the game • your voice is not being recorded",
                )
            )
            activeProfile?.let { profile ->
                updateCaptureNotification(
                    buildActiveCaptureStatus(profile) +
                        tr(" • voz pausada", " • voice paused")
                )
            }
        } else {
            floatingClipControl?.showMicrophoneResumed(
                tr(
                    "Micrófono disponible • voz reanudada",
                    "Microphone available • voice resumed",
                )
            )
            activeProfile?.let { profile ->
                updateCaptureNotification(buildActiveCaptureStatus(profile))
            }
        }
    }

    private fun disableMicrophoneVoice() {
        microphoneVoiceCapture?.stop()
        microphoneVoiceCapture = null

        runCatching { rollingMicrophoneWriter?.close() }
        rollingMicrophoneWriter = null
        microphoneCurrentlySilenced = false
    }

    private fun applyCapturedContentOrientation() {
        val width = pendingCapturedWidth
        val height = pendingCapturedHeight
        if (width <= 0 || height <= 0) return

        val current = activeProfile ?: return
        val portrait = RecordingOrientationPolicy.isPortrait(width, height)
        val currentlyPortrait = current.height > current.width
        if (portrait == currentlyPortrait) return

        val target = RecordingOrientationPolicy.orient(current, portrait)
        if (!EncoderCapabilityProbe.supportsProfile(target)) {
            return
        }
        requestOrientationProfileSwitch(target)
    }

    private fun requestOrientationProfileSwitch(nextProfile: RecordingProfile) {
        if (shutdownStarted.get() || mediaProjection == null) return
        if (!profileSwitchInProgress.compareAndSet(false, true)) return
        if (exportInProgress.get()) {
            profileSwitchInProgress.set(false)
            return
        }

        val currentProfile = activeProfile
        val currentEncoder = videoEncoder
        val currentWriter = rollingWriter
        val display = virtualDisplay
        val oldDirectory = activeVideoDirectory
        val oldSurface = currentEncoder?.captureSurface()

        if (
            currentProfile == null ||
            currentEncoder == null ||
            currentWriter == null ||
            display == null ||
            oldSurface == null
        ) {
            profileSwitchInProgress.set(false)
            return
        }
        if (
            currentProfile.width == nextProfile.width &&
            currentProfile.height == nextProfile.height
        ) {
            profileSwitchInProgress.set(false)
            return
        }

        thread(
            start = true,
            isDaemon = true,
            name = "medplay-orientation-profile-switch",
        ) {
            var replacementWriter: RollingVideoSegmentWriter? = null
            var replacementEncoder: HardwareVideoEncoder? = null
            var replacementDirectory: File? = null
            var displayMoved = false

            try {
                if (shutdownStarted.get()) return@thread

                replacementDirectory = nextVideoEpochDirectory()
                replacementWriter = RollingVideoSegmentWriter(
                    directory = replacementDirectory,
                    retentionUs = activeRetentionUs,
                )
                replacementEncoder = HardwareVideoEncoder(
                    profile = nextProfile,
                    sampleConsumer = replacementWriter,
                    onFatalError = {
                        Handler(Looper.getMainLooper()).post {
                            stopProjectionAndSelf()
                        }
                    },
                )
                val replacementSurface = replacementEncoder.start()
                if (shutdownStarted.get()) return@thread

                display.setSurface(null)
                display.resize(
                    nextProfile.width,
                    nextProfile.height,
                    resources.displayMetrics.densityDpi,
                )
                display.setSurface(replacementSurface)
                displayMoved = true
                replacementEncoder.requestSyncFrame()

                accumulateEncoderHealth(currentEncoder)

                rollingWriter = replacementWriter
                videoEncoder = replacementEncoder
                activeProfile = nextProfile
                activeVideoDirectory = replacementDirectory

                runCatching { currentEncoder.stop() }
                runCatching { currentWriter.close() }
                runCatching { oldDirectory?.deleteRecursively() }

                Handler(Looper.getMainLooper()).post {
                    updateCaptureNotification(
                        tr(
                            "Captura ajustada a " +
                                "${nextProfile.width}x${nextProfile.height}. " +
                                "La grabación continúa.",
                            "Capture adjusted to " +
                                "${nextProfile.width}x${nextProfile.height}. " +
                                "Recording continues.",
                        )
                    )
                }
            } catch (_: Throwable) {
                if (displayMoved) {
                    runCatching {
                        display.resize(
                            currentProfile.width,
                            currentProfile.height,
                            resources.displayMetrics.densityDpi,
                        )
                        display.setSurface(oldSurface)
                    }
                }
                runCatching { replacementEncoder?.stop() }
                runCatching { replacementWriter?.close() }
                runCatching { replacementDirectory?.deleteRecursively() }
            } finally {
                profileSwitchInProgress.set(false)
            }
        }
    }

    private fun requestThermalProfileDowngrade(
        bitratePercent: Int,
    ) {
        if (shutdownStarted.get() || mediaProjection == null) return
        if (!profileSwitchInProgress.compareAndSet(false, true)) return
        if (exportInProgress.get()) {
            profileSwitchInProgress.set(false)
            return
        }

        val currentProfile = activeProfile
        val currentEncoder = videoEncoder
        val currentWriter = rollingWriter
        val display = virtualDisplay
        val oldDirectory = activeVideoDirectory
        val oldSurface = currentEncoder?.captureSurface()

        if (
            currentProfile == null ||
            currentEncoder == null ||
            currentWriter == null ||
            display == null ||
            oldSurface == null
        ) {
            profileSwitchInProgress.set(false)
            return
        }

        thread(
            start = true,
            isDaemon = true,
            name = "medplay-thermal-profile-switch",
        ) {
            var replacementWriter: RollingVideoSegmentWriter? = null
            var replacementEncoder: HardwareVideoEncoder? = null
            var replacementDirectory: File? = null
            var displayMoved = false

            try {
                val supported = EncoderCapabilityProbe.supportedStartupProfiles()
                if (shutdownStarted.get()) return@thread

                val lowerProfile =
                    AdaptiveRecordingProfilePolicy.nextLowerProfile(
                        current = currentProfile,
                        supported = supported,
                    ) ?: return@thread
                val nextProfile = EncoderCapabilityProbe.orientLike(
                    profile = lowerProfile,
                    reference = currentProfile,
                ) ?: return@thread

                replacementDirectory = nextVideoEpochDirectory()
                replacementWriter = RollingVideoSegmentWriter(
                    directory = replacementDirectory,
                    retentionUs = activeRetentionUs,
                )
                replacementEncoder = HardwareVideoEncoder(
                    profile = nextProfile,
                    sampleConsumer = replacementWriter,
                    onFatalError = {
                        Handler(Looper.getMainLooper()).post {
                            stopProjectionAndSelf()
                        }
                    },
                )
                val replacementSurface = replacementEncoder.start()
                if (shutdownStarted.get()) return@thread

                display.setSurface(null)
                display.resize(
                    nextProfile.width,
                    nextProfile.height,
                    resources.displayMetrics.densityDpi,
                )
                display.setSurface(replacementSurface)
                displayMoved = true
                replacementEncoder.requestSyncFrame()

                accumulateEncoderHealth(currentEncoder)

                rollingWriter = replacementWriter
                videoEncoder = replacementEncoder
                activeProfile = nextProfile
                activeVideoDirectory = replacementDirectory
                thermalProfileDowngrades += 1

                val reducedBitrate = (
                    nextProfile.bitrate.toLong() * bitratePercent / 100L
                ).toInt()
                replacementEncoder.updateBitrate(reducedBitrate)

                runCatching { currentEncoder.stop() }
                runCatching { currentWriter.close() }
                runCatching { oldDirectory?.deleteRecursively() }

                Handler(Looper.getMainLooper()).post {
                    updateCaptureNotification(
                        tr(
                            "Protección térmica: " +
                                "${nextProfile.width}x${nextProfile.height} @ " +
                                "${nextProfile.fps} FPS. La grabación continúa.",
                            "Thermal protection: " +
                                "${nextProfile.width}x${nextProfile.height} @ " +
                                "${nextProfile.fps} FPS. Recording continues.",
                        )
                    )
                }
            } catch (_: Throwable) {
                if (!displayMoved) {
                    runCatching {
                        display.resize(
                            currentProfile.width,
                            currentProfile.height,
                            resources.displayMetrics.densityDpi,
                        )
                        display.setSurface(oldSurface)
                    }
                }
                runCatching { replacementEncoder?.stop() }
                runCatching { replacementWriter?.close() }
                runCatching { replacementDirectory?.deleteRecursively() }
            } finally {
                profileSwitchInProgress.set(false)
            }
        }
    }

    private fun accumulateEncoderHealth(encoder: HardwareVideoEncoder) {
        val health = encoder.healthSnapshot()
        accumulatedEncodedSamples += health.encodedSamples
        accumulatedTimestampRegressions += health.timestampRegressions
        accumulatedMaxWallOutputGapUs = maxOf(
            accumulatedMaxWallOutputGapUs,
            health.maxWallOutputGapUs,
        )
    }

    private fun combinedEncoderHealth(
        encoder: HardwareVideoEncoder,
    ): EncoderRuntimeHealth.Snapshot {
        val current = encoder.healthSnapshot()
        return EncoderRuntimeHealth.Snapshot(
            encodedSamples = accumulatedEncodedSamples + current.encodedSamples,
            timestampRegressions =
                accumulatedTimestampRegressions + current.timestampRegressions,
            maxWallOutputGapUs = maxOf(
                accumulatedMaxWallOutputGapUs,
                current.maxWallOutputGapUs,
            ),
            timeSinceLastOutputUs = current.timeSinceLastOutputUs,
        )
    }

    @Synchronized
    private fun nextVideoEpochDirectory(): File {
        return File(
            cacheDir,
            "medplay_rolling_video_epoch_%03d".format(videoEpochSequence++),
        )
    }

    private fun clearStaleVideoEpochDirectories() {
        videoEpochSequence = 0L
        cacheDir.listFiles()
            ?.filter {
                it.isDirectory &&
                    it.name.startsWith("medplay_rolling_video")
            }
            ?.forEach { runCatching { it.deleteRecursively() } }
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
                awaitProfileSwitchCompletion()
                releaseVideoPipeline()
                runCatching { projection?.stop() }
            } finally {
                shutdownResourcesReleased.set(true)
                finishServiceIfShutdownReady()
            }
        }
    }

    private fun awaitProfileSwitchCompletion() {
        val deadlineNs =
            System.nanoTime() + PROFILE_SWITCH_SHUTDOWN_WAIT_MS * 1_000_000L
        while (
            profileSwitchInProgress.get() &&
            System.nanoTime() < deadlineNs
        ) {
            Thread.sleep(PROFILE_SWITCH_POLL_MS)
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

        runCatching { microphoneVoiceCapture?.stop() }
        microphoneVoiceCapture = null

        runCatching { rollingMicrophoneWriter?.close() }
        rollingMicrophoneWriter = null
        microphoneCurrentlySilenced = false
        pendingCapturedWidth = 0
        pendingCapturedHeight = 0

        runCatching { thermalGuard?.close() }
        thermalGuard = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { videoEncoder?.stop() }
        videoEncoder = null

        runCatching { rollingWriter?.close() }
        rollingWriter = null
        activeVideoDirectory = null
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
            encoderHealth = combinedEncoderHealth(encoder),
            microphoneWasActive = sessionMicrophoneWasActive,
            microphoneWasSilenced = sessionMicrophoneWasSilenced,
            thermalProfileDowngrades = thermalProfileDowngrades,
            initialProfile = initialSessionProfile,
        )

        maxThermalStatus = 0
        sessionAudioWasActive = false
        sessionMicrophoneWasActive = false
        sessionMicrophoneWasSilenced = false
        initialSessionProfile = null
        thermalProfileDowngrades = 0
        accumulatedEncodedSamples = 0L
        accumulatedTimestampRegressions = 0L
        accumulatedMaxWallOutputGapUs = 0L

        runCatching {
            File(filesDir, LAST_SESSION_DIAGNOSTICS_FILE)
                .writeText(diagnostics.toText())
        }
    }

    private fun buildActiveCaptureStatus(profile: RecordingProfile): String {
        val retentionSeconds = activeRetentionUs / 1_000_000L
        val audioLabel = when {
            sessionAudioWasActive && sessionMicrophoneWasActive ->
                tr("audio + voz", "audio + voice")
            sessionAudioWasActive ->
                tr("audio", "audio")
            sessionMicrophoneWasActive ->
                tr("voz", "voice")
            else ->
                tr("solo video", "video only")
        }
        return profile.width.toString() + "x" + profile.height +
            " @ " + profile.fps + " FPS • " + retentionSeconds +
            "s • " + audioLabel
    }

    private fun updateCaptureNotification(text: String) {
        if (mediaProjection == null) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun startAsProjectionForegroundService(
        includeMicrophone: Boolean,
    ) {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= 29) {
            var serviceTypes =
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= 30 && includeMicrophone) {
                serviceTypes =
                    serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(
                NOTIFICATION_ID,
                notification,
                serviceTypes,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        contentText: String = tr(
            "Grabación de pantalla activa.",
            "Screen capture is active.",
        ),
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
                    tr("Detener", "Stop"),
                    stopIntent,
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun tr(spanish: String, english: String): String {
        return AppLanguagePreference.text(applicationContext, spanish, english)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                tr("Captura de MedPlay", "MedPlay capture"),
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
        private const val PROFILE_SWITCH_SHUTDOWN_WAIT_MS = 3_000L
        private const val PROFILE_SWITCH_POLL_MS = 25L
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
