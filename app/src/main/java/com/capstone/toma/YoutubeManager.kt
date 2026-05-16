package com.capstone.toma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume

class YoutubeManager {
    private val client = OkHttpClient()

    fun normalizeUrl(url: String): String {
        val shortsId = Regex("youtube\\.com/shorts/([^?/]+)").find(url)?.groupValues?.get(1)
        return if (shortsId != null) "https://www.youtube.com/watch?v=$shortsId" else url
    }

    // Fetches title + raw content from Jina. No thumbnail logic here.
    private suspend fun fetchContentSuspend(url: String): Pair<String?, String?> =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url("https://r.jina.ai/${normalizeUrl(url)}")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                .build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive)
                        continuation.resume(null to "유튜브 정보를 가져오는데 실패했습니다: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        if (response.isSuccessful) {
                            val content = response.body?.string() ?: ""
                            val title = content.lines().firstOrNull { it.isNotBlank() }?.replace("#", "")?.trim()
                            continuation.resume(title to content)
                        } else {
                            continuation.resume(null to "유튜브 정보를 로드할 수 없습니다 (HTTP ${response.code})")
                        }
                    }
                }
            })
        }

    // HEAD-checks maxresdefault; falls back to hqdefault (always exists for any video/Short).
    private suspend fun fetchBestThumbnail(videoId: String): String = withContext(Dispatchers.IO) {
        val maxRes = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
        val hq    = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        try {
            val resp = client.newCall(Request.Builder().url(maxRes).head().build()).execute()
            if (resp.isSuccessful && (resp.header("Content-Length")?.toLongOrNull() ?: 0L) > 1000L) maxRes else hq
        } catch (e: Exception) { hq }
    }

    // Jina content fetch and thumbnail HEAD check run in parallel.
    // When videoId cannot be extracted the thumbnail deferred is skipped entirely.
    suspend fun fetchVideoInfoSuspend(url: String): Triple<String?, String?, String?> {
        val videoId = extractVideoId(url)
        return coroutineScope {
            val contentDeferred   = async { fetchContentSuspend(url) }
            val thumbnailDeferred = if (videoId != null) async(Dispatchers.IO) { fetchBestThumbnail(videoId) } else null
            val (title, content)  = contentDeferred.await()
            Triple(title, content, thumbnailDeferred?.await())
        }
    }

    fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "shorts/([^?/]+)",
            "v=([^&]+)",
            "youtu\\.be/([^?/]+)",
            "embed/([^?/]+)"
        )
        for (pattern in patterns) {
            val match = Regex(pattern).find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }
}
