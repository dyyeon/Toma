package com.capstone.toma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class YouTubeManager {
    private val client = OkHttpClient()

    fun normalizeUrl(url: String): String {
        val shortsId = Regex("youtube\\.com/shorts/([^?/]+)").find(url)?.groupValues?.get(1)
        return if (shortsId != null) "https://www.youtube.com/watch?v=$shortsId" else url
    }

    // HEAD-checks maxresdefault; falls back to hqdefault (always exists for any video/Short).
    private suspend fun fetchBestThumbnail(videoId: String): String = withContext(Dispatchers.IO) {
        val maxRes = "https://img.youtube.com/vi/$videoId/maxresdefault.jpg"
        val hq    = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        try {
            client.newCall(Request.Builder().url(maxRes).head().build()).execute().use { resp ->
                if (resp.isSuccessful && (resp.header("Content-Length")?.toLongOrNull() ?: 0L) > 1000L) maxRes else hq
            }
        } catch (e: Exception) { hq }
    }

    // Best-effort: fetch the YouTube watch page and parse the og:description meta tag.
    // Returns null on any failure or interstitial/consent page — caller then falls back to title-only.
    private suspend fun fetchVideoDescription(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(normalizeUrl(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val html = response.body?.string() ?: return@use null

                // YouTube may serialize the meta tag attributes in either order.
                val patterns = listOf(
                    Regex("""<meta\s+property="og:description"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE),
                    Regex("""<meta\s+content="([^"]+)"\s+property="og:description"""", RegexOption.IGNORE_CASE),
                    Regex("""<meta\s+name="description"\s+content="([^"]+)"""", RegexOption.IGNORE_CASE)
                )
                val raw = patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }
                    ?: return@use null

                val decoded = raw
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&#39;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
                    .trim()

                // Filter out generic/consent placeholders.
                val isGeneric = decoded.isBlank()
                    || decoded.equals("YouTube", ignoreCase = true)
                    || decoded.contains("Enjoy the videos and music", ignoreCase = true)
                    || decoded.contains("Share your videos with friends", ignoreCase = true)
                if (isGeneric) null else decoded
            }
        } catch (e: Exception) { null }
    }

    // oEmbed title fetch, og:description fetch, and thumbnail HEAD check all run in parallel.
    // oEmbed is the source of truth for success/failure; description is best-effort context for the AI.
    suspend fun fetchVideoInfoSuspend(url: String): Triple<String?, String?, String?> = coroutineScope {
        val normalizedUrl = normalizeUrl(url)
        val encodedUrl = URLEncoder.encode(normalizedUrl, "UTF-8")
        val oEmbedUrl = "https://www.youtube.com/oembed?url=$encodedUrl&format=json"

        val videoId = extractVideoId(url)
        val thumbnailDeferred = if (videoId != null) async(Dispatchers.IO) { fetchBestThumbnail(videoId) } else null
        val descriptionDeferred = async(Dispatchers.IO) { fetchVideoDescription(url) }

        val (title, errorMsg) = withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(oEmbedUrl).build()).execute().use { response ->
                    if (response.isSuccessful) {
                        val json = JSONObject(response.body?.string() ?: "{}")
                        val t = json.optString("title").takeIf { it.isNotBlank() }
                        t to null
                    } else {
                        null to "유튜브 정보를 로드할 수 없습니다 (HTTP ${response.code})"
                    }
                }
            } catch (e: Exception) {
                null to "유튜브 정보를 가져오는데 실패했습니다: ${e.message}"
            }
        }

        // When oEmbed fails, surface the error in the description slot so TomaNavHost's
        // fetchFailed check fires. On success, surface the og:description (or null) for the AI.
        val secondSlot = errorMsg ?: descriptionDeferred.await()
        Triple(title, secondSlot, thumbnailDeferred?.await())
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
