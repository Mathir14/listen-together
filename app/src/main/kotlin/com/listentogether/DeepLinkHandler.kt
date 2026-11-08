package com.listentogether

import android.content.Intent
import android.net.Uri

data class DeepLinkJoinRequest(
    val roomId: String,
    val inviteSecret: String,
)

object DeepLinkHandler {

    fun parse(intent: Intent?): DeepLinkJoinRequest? {
        val uri = intent?.data ?: return null
        return parse(uri.toString())
    }

    fun parse(uri: Uri): DeepLinkJoinRequest? {
        return parse(uri.toString())
    }

    fun parse(uriString: String?): DeepLinkJoinRequest? {
        if (uriString.isNullOrBlank()) return null

        // Support listentogether://join?... and https://listentogether.app/join?...
        val uri = uriString.trim()
        val isCustomScheme = uri.startsWith("listentogether://join", ignoreCase = true)
        val isHttps = uri.startsWith("https://listentogether.app/join", ignoreCase = true) ||
            uri.startsWith("http://listentogether.app/join", ignoreCase = true)

        if (!isCustomScheme && !isHttps) {
            return null
        }

        val questionIdx = uri.indexOf('?')
        val queryPart = if (questionIdx >= 0) uri.substring(questionIdx + 1) else ""
        val pathPart = if (questionIdx >= 0) uri.substring(0, questionIdx) else uri

        val queryParams = parseQueryParams(queryPart)

        var roomId = queryParams["roomId"]
            ?: queryParams["room"]
            ?: queryParams["id"]

        // If not in query params, inspect path segment: e.g. /join/{roomId}
        if (roomId.isNullOrBlank()) {
            val afterJoin = pathPart.substringAfter("/join", "").trim('/')
            if (afterJoin.isNotEmpty() && !afterJoin.contains('/')) {
                roomId = afterJoin
            }
        }

        val inviteSecret = queryParams["inviteSecret"]
            ?: queryParams["secret"]
            ?: queryParams["key"]

        if (roomId.isNullOrBlank() || inviteSecret.isNullOrBlank()) {
            return null
        }

        return DeepLinkJoinRequest(
            roomId = roomId.trim(),
            inviteSecret = inviteSecret.trim(),
        )
    }

    fun createJoinUri(roomId: String, inviteSecret: String): Uri {
        return Uri.parse("listentogether://join?roomId=$roomId&inviteSecret=$inviteSecret")
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        val pairs = query.split('&')
        for (pair in pairs) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                result[key] = value
            }
        }
        return result
    }
}
