package com.medplay.app.recording

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ClipEditProjectStore {

    data class Project(
        val sourceUri: Uri,
        val trimStartUs: Long,
        val trimEndUs: Long,
        val gameAudioGainPercent: Int,
        val microphoneGainPercent: Int,
        val updatedAtMs: Long,
    )

    @Synchronized
    fun load(
        context: Context,
        sourceUri: Uri,
        sourceDurationUs: Long,
    ): Project {
        val existing = readAll(context)
            .firstOrNull { it.sourceUri == sourceUri }

        return existing ?: Project(
            sourceUri = sourceUri,
            trimStartUs = 0L,
            trimEndUs = sourceDurationUs.coerceAtLeast(0L),
            gameAudioGainPercent = 100,
            microphoneGainPercent = 100,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    @Synchronized
    fun save(
        context: Context,
        project: Project,
        sourceDurationUs: Long,
    ) {
        val sanitized = sanitize(project, sourceDurationUs)
        val all = readAll(context)
            .filterNot { it.sourceUri == sanitized.sourceUri }
            .toMutableList()
        all += sanitized
        writeAll(context, all)
    }

    @Synchronized
    fun deleteForSource(context: Context, sourceUri: Uri) {
        val all = readAll(context)
        val remaining = all.filterNot { it.sourceUri == sourceUri }
        if (remaining.size != all.size) {
            writeAll(context, remaining)
        }
    }

    private fun sanitize(
        project: Project,
        sourceDurationUs: Long,
    ): Project {
        val duration = sourceDurationUs.coerceAtLeast(0L)
        val start = project.trimStartUs.coerceIn(0L, duration)
        val end = project.trimEndUs.coerceIn(start, duration)

        return project.copy(
            trimStartUs = start,
            trimEndUs = end,
            gameAudioGainPercent =
                project.gameAudioGainPercent.coerceIn(0, 200),
            microphoneGainPercent =
                project.microphoneGainPercent.coerceIn(0, 200),
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun readAll(context: Context): List<Project> {
        val file = file(context)
        if (!file.isFile) return emptyList()

        return runCatching {
            val array = JSONArray(file.readText())
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        Project(
                            sourceUri = Uri.parse(item.getString(KEY_SOURCE_URI)),
                            trimStartUs = item.getLong(KEY_TRIM_START_US),
                            trimEndUs = item.getLong(KEY_TRIM_END_US),
                            gameAudioGainPercent =
                                item.optInt(KEY_GAME_GAIN, 100),
                            microphoneGainPercent =
                                item.optInt(KEY_MIC_GAIN, 100),
                            updatedAtMs =
                                item.optLong(KEY_UPDATED_AT_MS, 0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(context: Context, projects: List<Project>) {
        val array = JSONArray()
        projects.forEach { project ->
            array.put(
                JSONObject().apply {
                    put(KEY_SOURCE_URI, project.sourceUri.toString())
                    put(KEY_TRIM_START_US, project.trimStartUs)
                    put(KEY_TRIM_END_US, project.trimEndUs)
                    put(KEY_GAME_GAIN, project.gameAudioGainPercent)
                    put(KEY_MIC_GAIN, project.microphoneGainPercent)
                    put(KEY_UPDATED_AT_MS, project.updatedAtMs)
                }
            )
        }

        val destination = file(context)
        val temporary = File(
            destination.parentFile,
            destination.name + ".tmp",
        )
        temporary.writeText(array.toString())
        if (!temporary.renameTo(destination)) {
            destination.writeText(array.toString())
            temporary.delete()
        }
    }

    private fun file(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }

    private const val FILE_NAME = "clip_edit_projects.json"
    private const val KEY_SOURCE_URI = "sourceUri"
    private const val KEY_TRIM_START_US = "trimStartUs"
    private const val KEY_TRIM_END_US = "trimEndUs"
    private const val KEY_GAME_GAIN = "gameGainPercent"
    private const val KEY_MIC_GAIN = "microphoneGainPercent"
    private const val KEY_UPDATED_AT_MS = "updatedAtMs"
}
