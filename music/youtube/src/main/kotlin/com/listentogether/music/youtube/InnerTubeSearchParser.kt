package com.listentogether.music.youtube

import com.listentogether.core.model.Track
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses only the stable subset of the InnerTube web response that the catalogue needs. */
internal object InnerTubeSearchParser {
    private const val DURATION_REGEX = "(?:(\\d+):)?(\\d{1,2}):(\\d{2})"

    fun parse(root: JsonObject, limit: Int): List<Track> {
        val renderers = mutableListOf<JsonObject>()
        collectRenderers(root, renderers)
        return renderers
            .mapNotNull(::toTrack)
            .distinctBy { it.providerTrackId }
            .take(limit.coerceAtLeast(1))
    }

    fun videoDetails(root: JsonObject): Track? {
        val details = root["videoDetails"] as? JsonObject ?: return null
        val id = details.string("videoId") ?: return null
        return Track(
            provider = "youtube_music",
            providerTrackId = id,
            title = details.string("title") ?: return null,
            artist = details.string("author") ?: "",
            album = null,
            thumbnailUrl = largestThumbnail(details),
            durationMs = (details.string("lengthSeconds")?.toLongOrNull() ?: 0L) * 1_000L,
        )
    }

    private fun collectRenderers(element: JsonElement, out: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> {
                (element["musicResponsiveListItemRenderer"] as? JsonObject)?.let(out::add)
                element.values.forEach { collectRenderers(it, out) }
            }
            is JsonArray -> element.forEach { collectRenderers(it, out) }
            else -> Unit
        }
    }

    private fun toTrack(renderer: JsonObject): Track? {
        val videoId = findString(renderer, "videoId") ?: findPlaylistItemId(renderer) ?: return null
        val columns = (renderer["flexColumns"] as? JsonArray)
            ?.mapNotNull { column ->
                val columnRenderer = column.jsonObject["musicResponsiveListItemFlexColumnRenderer"] as? JsonObject
                columnRenderer?.get("text")?.let(::runsText)
            }
            .orEmpty()
        val title = columns.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val subtitle = columns.getOrNull(1).orEmpty()
        val subtitleParts = subtitle.split("•").map { it.trim() }.filter { it.isNotBlank() }
        val artist = subtitleParts.firstOrNull().orEmpty()
        val album = subtitleParts.getOrNull(1)?.takeIf { !DURATION_REGEX.toRegex().matches(it) }
        val durationText = findDurationText(renderer) ?: subtitleParts.lastOrNull()
        return Track(
            provider = "youtube_music",
            providerTrackId = videoId,
            title = title,
            artist = artist,
            album = album,
            thumbnailUrl = largestThumbnail(renderer),
            durationMs = parseDuration(durationText),
        )
    }

    private fun findPlaylistItemId(renderer: JsonObject): String? {
        val data = renderer["playlistItemData"] as? JsonObject
        return data?.string("videoId")
    }

    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> element[key]?.let { if (it is JsonPrimitive) it.contentOrNull else null }
            ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    private fun findDurationText(element: JsonElement): String? = when (element) {
        is JsonObject -> element.values.firstNotNullOfOrNull { findDurationText(it) }
        is JsonArray -> element.firstNotNullOfOrNull { findDurationText(it) }
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { DURATION_REGEX.toRegex().matches(it) }
        else -> null
    }

    private fun runsText(container: JsonElement): String {
        val runs = (container as? JsonObject)?.get("runs") as? JsonArray ?: return ""
        return runs.joinToString(separator = "") { run ->
            (run as? JsonObject)?.string("text").orEmpty()
        }.trim()
    }

    private fun largestThumbnail(root: JsonElement): String? {
        val thumbnails = mutableListOf<JsonObject>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    (element["thumbnails"] as? JsonArray)?.forEach { item ->
                        (item as? JsonObject)?.let(thumbnails::add)
                    }
                    element.values.forEach(::walk)
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return thumbnails
            .filter { it.string("url") != null }
            .maxByOrNull { it["width"]?.jsonPrimitive?.intOrNull ?: 0 }
            ?.string("url")
    }

    private fun parseDuration(text: String?): Long {
        val match = text?.let { DURATION_REGEX.toRegex().find(it) } ?: return 0
        val groups = match.groupValues
        val hours = groups[1].toLongOrNull() ?: 0
        val minutes = groups[2].toLongOrNull() ?: 0
        val seconds = groups[3].toLongOrNull() ?: 0
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
