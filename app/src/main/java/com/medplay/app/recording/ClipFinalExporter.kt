package com.medplay.app.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

@OptIn(UnstableApi::class)
class ClipFinalExporter(
    private val context: Context,
) {

    data class Result(
        val uri: Uri,
        val displayName: String,
        val durationUs: Long,
        val mixedAudio: Boolean,
        val visualProcessing: Boolean,
    )

    fun start(
        sourceUri: Uri,
        trimStartUs: Long,
        trimEndUs: Long,
        hasGameAudio: Boolean,
        hasMicrophone: Boolean,
        gameGainPercent: Int,
        microphoneGainPercent: Int,
        visualSettings: EditorVisualSettings.Values,
        onSuccess: (Result) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        require(trimEndUs > trimStartUs)

        val temporaryOutput = File(
            context.cacheDir,
            "medplay_final_export_${System.currentTimeMillis()}.mp4",
        )
        runCatching { temporaryOutput.delete() }

        val extractedAudio = runCatching {
            ClipAudioTrackDemuxer(context).extract(
                sourceUri = sourceUri,
                trimStartUs = trimStartUs,
                trimEndUs = trimEndUs,
            )
        }.getOrElse {
            onError(it)
            return
        }

        val videoMediaItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(trimStartUs.coerceAtLeast(0L))
                    .setEndPositionUs(
                        trimEndUs.coerceAtLeast(trimStartUs + 1L)
                    )
                    .build()
            )
            .build()

        val videoItem = EditedMediaItem.Builder(videoMediaItem)
            .setRemoveAudio(true)
            .setEffects(
                Effects(
                    emptyList(),
                    EditorVisualSettings.buildVideoEffects(visualSettings),
                )
            )
            .build()

        val sequences = mutableListOf(
            EditedMediaItemSequence(listOf(videoItem))
        )

        extractedAudio.forEachIndexed { ordinal, track ->
            val gainPercent = when {
                ordinal == 0 && hasGameAudio -> gameGainPercent
                ordinal == 0 && !hasGameAudio && hasMicrophone ->
                    microphoneGainPercent
                ordinal == 1 && hasMicrophone -> microphoneGainPercent
                else -> 100
            }

            val audioItem = EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(track.file))
            )
                .setEffects(
                    Effects(
                        listOf(
                            ConstantGainAudioProcessor(gainPercent)
                        ),
                        emptyList(),
                    )
                )
                .build()

            sequences += EditedMediaItemSequence(
                listOf(audioItem)
            )
        }

        val composition = Composition.Builder(sequences).build()
        val visualProcessing =
            EditorVisualSettings.hasVisualChanges(visualSettings)
        val mixedAudio = extractedAudio.size > 1 ||
            gameGainPercent != 100 ||
            microphoneGainPercent != 100

        val transformer = Transformer.Builder(context)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult,
                    ) {
                        val published = runCatching {
                            publishTemporary(
                                temporary = temporaryOutput,
                                durationUs = trimEndUs - trimStartUs,
                                mixedAudio = mixedAudio,
                                visualProcessing = visualProcessing,
                            )
                        }
                        cleanup(temporaryOutput, extractedAudio)
                        published
                            .onSuccess(onSuccess)
                            .onFailure(onError)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        cleanup(temporaryOutput, extractedAudio)
                        onError(exportException)
                    }
                }
            )
            .build()

        runCatching {
            transformer.start(composition, temporaryOutput.absolutePath)
        }.onFailure {
            cleanup(temporaryOutput, extractedAudio)
            onError(it)
        }
    }

    private fun publishTemporary(
        temporary: File,
        durationUs: Long,
        mixedAudio: Boolean,
        visualProcessing: Boolean,
    ): Result {
        check(temporary.isFile && temporary.length() > 0L) {
            "Final editor export is missing"
        }

        val displayName =
            "MedPlay_Edit_${System.currentTimeMillis()}.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MedPlay")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: error("Unable to create final MediaStore item")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                temporary.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open final MediaStore item")

            val updated = resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
            check(updated == 1) { "Unable to publish final edit" }

            return Result(
                uri = uri,
                displayName = displayName,
                durationUs = durationUs.coerceAtLeast(0L),
                mixedAudio = mixedAudio,
                visualProcessing = visualProcessing,
            )
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun cleanup(
        temporaryOutput: File,
        extractedAudio: List<ClipAudioTrackDemuxer.TrackFile>,
    ) {
        runCatching { temporaryOutput.delete() }
        extractedAudio.forEach { track ->
            runCatching { track.file.delete() }
        }
    }
}
