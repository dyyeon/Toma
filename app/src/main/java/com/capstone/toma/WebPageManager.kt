package com.capstone.toma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.net.URLEncoder
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

                    // Extract all potential images from markdown
                    val imageRegex = Regex("""!\[.*?\]\((https://[^)]+\.(?:jpg|jpeg|png|webp|GIF)[^)]*)\)""", RegexOption.IGNORE_CASE)
                    val images = imageRegex.findAll(content).map { it.groupValues[1] }.toList()

                    // Filtering logic for representative images:
                    // 1. Prefer images with 'cache/recipe' or 'recipe' in URL (common for 10000recipe main)
                    // 2. Filter out small icons or generic placeholders (e.g. logos, share buttons)
                    val firstImage = images.find { it.contains("cache/recipe") || it.contains("recipe") }
                        ?: images.firstOrNull { 
                            !it.contains("logo", ignoreCase = true) && 
                            !it.contains("icon", ignoreCase = true) &&
                            !it.contains("button", ignoreCase = true)
                        }

                    // Pass the full markdown content to the AI
                    onResult(title, content, firstImage)
                } else {
                    onResult(null, "페이지 로드 실패 (HTTP ${response.code})", null)
                }
            }
        })
    }

    suspend fun searchFoodImage(keyword: String): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val searchUrl = "https://www.10000recipe.com/recipe/list.html?q=$encoded"
            val request = Request.Builder()
                .url("https://r.jina.ai/$searchUrl")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val content = response.body?.string() ?: return@withContext null

            // Improved extraction for 10000recipe search results:
            // The first image in the search list is usually a recipe thumbnail.
            // Thumbnails usually have a specific pattern like 'cache/recipe'.
            val imageRegex = Regex("""!\[.*?\]\((https://[^)]+\.(?:jpg|jpeg|png|webp|GIF)[^)]*)\)""", RegexOption.IGNORE_CASE)
            val images = imageRegex.findAll(content).map { it.groupValues[1] }.toList()

            images.find { it.contains("cache/recipe") } 
                ?: images.firstOrNull { 
                    !it.contains("logo", ignoreCase = true) && 
                    !it.contains("icon", ignoreCase = true) 
                }
        } catch (e: Exception) {
            null
        }
    }
}
