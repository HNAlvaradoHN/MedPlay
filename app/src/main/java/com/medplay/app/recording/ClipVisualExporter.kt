package com.medplay.app.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

@OptIn(UnstableApi::class)
class ClipVisualExporter(
    private val context: Context,
) {

    data class Result(
        val uri: Uri,
        val displayName: String,
        val durationUs: Long,
    )

    fun start(
        sourceUri: Uri,
        trimStartUs: Long,
        trimEndUs: Long,
        visualSettings: EditorVisualSettings.Values,
        onSuccess: (Result) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        require(trimEndUs > trimStartUs)

        val temporary = File(
            context.cacheDir,
            "medplay_visual_export_${System.currentTimeMillis()}.mp4",
        )
        runCatching { temporary.delete() }

        val mediaItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(trimStartUs.coerceAtLeast(0L))
                    .setEndPositionUs(trimEndUs.coerceAtLeast(trimStartUs + 1L))
                    .build()
            )
            .build()

        val effects = Effects(
            emptyList(),
            EditorVisualSettings.buildVideoEffects(visualSettings),
        )
        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
            .setEffects(effects)
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(
                object : Transformer.Listener {
                    override fun onCompleted(
                        composition: Composition,
                        exportResult: ExportResult,
                    ) {
                        val published = runCatching {
                            publishTemporary(
                                temporary = temporary,
                                durationUs = trimEndUs - trimStartUs,
                            )
                        }
                        runCatching { temporary.delete() }
                        published
                            .onSuccess(onSuccess)
                            .onFailure(onError)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        runCatching { temporary.delete() }
                        onError(exportException)
                    }
                }
            )
            .build()

        transformer.start(editedMediaItem, temporary.absolutePath)
    }

    private fun publishTemporary(
        temporary: File,
        durationUs: Long,
    ): Result {
        check(temporary.isFile && temporary.length() > 0L) {
            "Processed export file is missing"
        }

        val displayName =
            "MedPlay_Filtered_${System.currentTimeMillis()}.mp4"
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
        ) ?: error("Unable to create filtered MediaStore item")

        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                temporary.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: error("Unable to open filtered MediaStore item")

            val updated = resolver.update(
                uri,
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                },
                null,
                null,
            )
            check(updated == 1) { "Unable to publish filtered clip" }

            return Result(
                uri = uri,
                displayName = displayName,
                durationUs = durationUs.coerceAtLeast(0L),
            )
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
