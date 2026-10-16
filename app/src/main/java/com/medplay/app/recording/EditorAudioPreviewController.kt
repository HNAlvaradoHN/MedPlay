package com.medplay.app.recording

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * Provides independent realtime preview volume for each encoded audio track in
 * an editor source. The main video player is muted only after all expected
 * audio tracks are available through the auxiliary players.
 */
class EditorAudioPreviewController(
    context: Context,
    sourceUri: Uri,
    private val videoPlayer: ExoPlayer,
    private val expectedTrackCount: Int,
    private val onReady: () -> Unit = {},
    private val onUnavailable: () -> Unit = {},
) {

    private data class AudioSlot(
        val ordinal: Int,
        val player: ExoPlayer,
        var configured: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val slots = mutableListOf<AudioSlot>()
    private var released = false
    private var ready = false
    private var gameGainPercent = 100
    private var microphoneGainPercent = 100

    private val syncRunnable = object : Runnable {
        override fun run() {
            if (released || !ready) return
            syncNow()
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }

    private val videoListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!ready) return
            syncNow()
            slots.forEach { slot ->
                if (isPlaying) {
                    runCatching { slot.player.play() }
                } else {
                    runCatching { slot.player.pause() }
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (ready) syncNow(force = true)
        }
    }

    init {
        if (expectedTrackCount <= 0) {
            onUnavailable()
        } else {
            videoPlayer.addListener(videoListener)
            repeat(expectedTrackCount) { ordinal ->
                createSlot(ordinal, sourceUri)
            }
        }
    }

    fun setGains(
        gameGainPercent: Int,
        microphoneGainPercent: Int,
    ) {
        this.gameGainPercent = gameGainPercent.coerceIn(0, 200)
        this.microphoneGainPercent = microphoneGainPercent.coerceIn(0, 200)
        if (ready) applyVolumes()
    }

    fun syncNow(force: Boolean = false) {
        if (!ready || released) return

        val targetMs = videoPlayer.currentPosition.coerceAtLeast(0L)
        slots.forEach { slot ->
            val audio = slot.player
            val driftMs = abs(audio.currentPosition - targetMs)
            if (force || driftMs > MAX_DRIFT_MS) {
                audio.seekTo(targetMs)
            }

            if (videoPlayer.isPlaying && !audio.isPlaying) {
                audio.play()
            } else if (!videoPlayer.isPlaying && audio.isPlaying) {
                audio.pause()
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(syncRunnable)
        videoPlayer.removeListener(videoListener)
        slots.forEach { runCatching { it.player.release() } }
        slots.clear()
    }

    private fun createSlot(
        ordinal: Int,
        sourceUri: Uri,
    ) {
        val audioPlayer = ExoPlayer.Builder(appContext).build()
        val slot = AudioSlot(ordinal = ordinal, player = audioPlayer)
        slots += slot

        audioPlayer.volume = 0f
        audioPlayer.trackSelectionParameters =
            audioPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()

        audioPlayer.addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (released || slot.configured) return

                    val candidates = buildList {
                        tracks.groups
                            .filter { it.type == C.TRACK_TYPE_AUDIO }
                            .forEach { group ->
                                for (trackIndex in 0 until group.length) {
                                    if (group.isTrackSupported(trackIndex)) {
                                        add(group to trackIndex)
                                    }
                                }
                            }
                    }

                    val candidate = candidates.getOrNull(ordinal)
                    if (candidate == null) {
                        fail()
                        return
                    }

                    val (group, trackIndex) = candidate
                    audioPlayer.trackSelectionParameters =
                        audioPlayer.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(
                                TrackSelectionOverride(
                                    group.mediaTrackGroup,
                                    trackIndex,
                                )
                            )
                            .build()

                    slot.configured = true
                    maybeMarkReady()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (!ready) fail()
                }
            }
        )

        audioPlayer.setMediaItem(MediaItem.fromUri(sourceUri))
        audioPlayer.prepare()
    }

    private fun maybeMarkReady() {
        if (released || ready) return
        if (slots.size != expectedTrackCount) return
        if (slots.any { !it.configured }) return

        ready = true
        videoPlayer.volume = 0f
        applyVolumes()
        syncNow(force = true)
        handler.post(syncRunnable)
        onReady()
    }

    private fun applyVolumes() {
        slots.forEach { slot ->
            val percent = when (slot.ordinal) {
                0 -> gameGainPercent
                1 -> microphoneGainPercent
                else -> 100
            }

            // ExoPlayer preview volume is limited to unity gain. Export still
            // supports 0..200% through ConstantGainAudioProcessor.
            slot.player.volume = (percent.coerceAtMost(100) / 100f)
        }
    }

    private fun fail() {
        if (released || ready) return
        release()
        videoPlayer.volume = 1f
        onUnavailable()
    }

    companion object {
        private const val SYNC_INTERVAL_MS = 500L
        private const val MAX_DRIFT_MS = 140L
    }
}
