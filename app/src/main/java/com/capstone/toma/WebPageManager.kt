package com.capstone.toma

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume

class WebPageManager {
    private val client = OkHttpClient()

    /**
     * Coroutine-friendly suspend function to fetch page info via Jina AI
     */
    suspend fun fetchPageInfoSuspend(url: String): Triple<String?, String?, String?> = suspendCancellableCoroutine { continuation ->
        fetchPageInfo(url) { title, content, imageUrl ->
            if (continuation.isActive) {
                continuation.resume(Triple(title, content, imageUrl))
            }
        }
    }

    fun fetchPageInfo(url: String, onResult: (title: String?, content: String?, imageUrl: String?) -> Unit) {
        // ALWAYS use Jina AI Reader to bypass anti-crawling and get clean markdown for all URLs
        val fetchUrl = "https://r.jina.ai/$url"

        val request = Request.Builder()
            .url(fetchUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null, "본문을 읽어오는데 실패했습니다: ${e.message}", null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val content = response.body?.string() ?: ""
                    
                    // Jina AI returns clean markdown. The first line is usually the title.
                    val title = content.lines().firstOrNull { it.isNotBlank() }?.replace("#", "")?.trim()
                    
                    // Pass the full markdown content to the AI
                    onResult(title, content, null)
                } else {
                    onResult(null, "페이지 로드 실패 (HTTP ${response.code})", null)
                }
            }
        })
    }
}
