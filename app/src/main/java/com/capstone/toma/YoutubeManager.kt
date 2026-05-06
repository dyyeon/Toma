package com.capstone.toma

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume

class YoutubeManager {
    private val client = OkHttpClient()

    /**
     * Coroutine-friendly suspend function to fetch video info via Jina AI
     */
    suspend fun fetchVideoInfoSuspend(url: String): Triple<String?, String?, String?> = suspendCancellableCoroutine { continuation ->
        fetchVideoInfo(url) { title, content, imageUrl ->
            if (continuation.isActive) {
                continuation.resume(Triple(title, content, imageUrl))
            }
        }
    }

    /**
     * Fetch YouTube info using Jina AI Reader.
     * Jina AI is excellent at extracting titles, descriptions, and metadata from YouTube pages.
     */
    fun fetchVideoInfo(url: String, onResult: (title: String?, content: String?, imageUrl: String?) -> Unit) {
        val jinaUrl = "https://r.jina.ai/$url"
        
        val request = Request.Builder()
            .url(jinaUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null, "유튜브 정보를 가져오는데 실패했습니다: ${e.message}", null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val content = response.body?.string() ?: ""
                    // Jina AI returns clean markdown.
                    val title = content.lines().firstOrNull { it.isNotBlank() }?.replace("#", "")?.trim()
                    
                    // The content from Jina Reader typically includes description and sometimes transcript elements
                    onResult(title, content, null)
                } else {
                    onResult(null, "유튜브 정보를 로드할 수 없습니다 (HTTP ${response.code})", null)
                }
            }
        })
    }

    /**
     * Helper to extract Video ID for other purposes if needed
     */
    fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "v=([^&]+)",
            "youtu.be/([^?]+)",
            "embed/([^?]+)"
        )
        for (pattern in patterns) {
            val match = Regex(pattern).find(url)
            if (match != null) return match.groupValues[1]
        }
        return null
    }
}
