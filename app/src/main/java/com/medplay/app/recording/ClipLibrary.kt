package com.medplay.app.recording

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lightweight local index of MedPlay-owned MediaStore clips.
 *
 * The video is never duplicated into app-private storage. Only metadata and
 * the MediaStore URI are stored here so clips remain available from both the
 * Gallery and MedPlay.
 */
object ClipLibrary {

    data class Entry(
        val uri: Uri,
        val displayName: String,
        val createdAtMs: Long,
        val durationUs: Long,
        val hasGameAudio: Boolean,
        val hasMicrophone: Boolean,
    )

    @Synchronized
    fun recordExport(
        context: Context,
        result: RetroactiveVideoExporter.ExportResult,
    ) {
        val entries = readRaw(context)
            .filterNot { it.uri == result.uri }
            .toMutableList()

        entries.add(
            0,
            Entry(
                uri = result.uri,
                displayName = result.displayName,
                createdAtMs = System.currentTimeMillis(),
                durationUs = result.durationUs,
                hasGameAudio = result.audioSegmentCount > 0,
                hasMicrophone = result.microphoneSegmentCount > 0,
            )
        )
        write(context, entries)
    }

    @Synchronized
    fun recordDerivedExport(
        context: Context,
        uri: Uri,
        displayName: String,
        durationUs: Long,
        hasGameAudio: Boolean,
        hasMicrophone: Boolean,
    ) {
        val entries = readRaw(context)
            .filterNot { it.uri == uri }
            .toMutableList()

        entries.add(
            0,
            Entry(
                uri = uri,
                displayName = displayName,
                createdAtMs = System.currentTimeMillis(),
                durationUs = durationUs.coerceAtLeast(0L),
                hasGameAudio = hasGameAudio,
                hasMicrophone = hasMicrophone,
            )
        )
        write(context, entries)
    }

    @Synchronized
    fun listExisting(context: Context): List<Entry> {
        val stored = readRaw(context)
        val existing = stored.filter { entry ->
            runCatching {
                context.contentResolver
                    .openFileDescriptor(entry.uri, "r")
                    ?.use { true }
                    ?: false
            }.getOrDefault(false)
        }

        if (existing.size != stored.size) {
            write(context, existing)
        }
        return existing
    }

    @Synchronized
    fun find(context: Context, uri: Uri): Entry? {
        return listExisting(context).firstOrNull { it.uri == uri }
    }

    @Synchronized
    fun delete(context: Context, uri: Uri): Boolean {
        val deletedRows = runCatching {
            context.contentResolver.delete(uri, null, null)
        }.getOrElse {
            return false
        }

        if (deletedRows <= 0) {
            val stillExists = runCatching {
                context.contentResolver
                    .openFileDescriptor(uri, "r")
                    ?.use { true }
                    ?: false
            }.getOrDefault(false)

            if (stillExists) {
                return false
            }
        }

        val remaining = readRaw(context).filterNot { it.uri == uri }
        write(context, remaining)
        ClipEditProjectStore.deleteForSource(context, uri)
        return true
    }

    private fun readRaw(context: Context): List<Entry> {
        val file = libraryFile(context)
        if (!file.isFile) return emptyList()

        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        Entry(
                            uri = Uri.parse(item.getString(KEY_URI)),
                            displayName = item.getString(KEY_DISPLAY_NAME),
                            createdAtMs = item.getLong(KEY_CREATED_AT_MS),
                            durationUs = item.getLong(KEY_DURATION_US),
                            hasGameAudio = item.optBoolean(KEY_GAME_AUDIO, false),
                            hasMicrophone = item.optBoolean(KEY_MICROPHONE, false),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put(KEY_URI, entry.uri.toString())
                    put(KEY_DISPLAY_NAME, entry.displayName)
                    put(KEY_CREATED_AT_MS, entry.createdAtMs)
                    put(KEY_DURATION_US, entry.durationUs)
                    put(KEY_GAME_AUDIO, entry.hasGameAudio)
                    put(KEY_MICROPHONE, entry.hasMicrophone)
                }
            )
        }

        val destination = libraryFile(context)
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        temporary.writeText(array.toString())

        if (!temporary.renameTo(destination)) {
            destination.writeText(array.toString())
            temporary.delete()
        }
    }

    private fun libraryFile(context: Context): File {
        return File(context.filesDir, LIBRARY_FILE_NAME)
    }

    private const val LIBRARY_FILE_NAME = "clip_library.json"
    private const val KEY_URI = "uri"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_CREATED_AT_MS = "createdAtMs"
    private const val KEY_DURATION_US = "durationUs"
    private const val KEY_GAME_AUDIO = "hasGameAudio"
    private const val KEY_MICROPHONE = "hasMicrophone"
}
