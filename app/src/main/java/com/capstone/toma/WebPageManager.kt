package com.capstone.toma

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resume

class WebPageManager {
    private val client = OkHttpClient()

    private data class NaverBlogInfo(val userId: String, val postNo: String)

    private fun extractNaverBlogInfo(url: String): NaverBlogInfo? {
        // Pattern 1: blog.naver.com/{userId}/{postNo}
        val p1 = Regex("""^https?://(?:www\.)?blog\.naver\.com/([^/?#]+)/(\d+)""")
        p1.find(url)?.let { return NaverBlogInfo(it.groupValues[1], it.groupValues[2]) }

        // Pattern 2: PostView.naver?blogId=...&logNo=...
        val uri = Uri.parse(url)
        val blogId = uri.getQueryParameter("blogId")
        val logNo = uri.getQueryParameter("logNo")
        if (!blogId.isNullOrBlank() && !logNo.isNullOrBlank() && logNo.all { it.isDigit() }) {
            return NaverBlogInfo(blogId, logNo)
        }

        return null
    }

    // Strip known tracking parameters that don't affect content but can confuse Jina's cache
    private fun stripTrackingParams(url: String): String {
        val trackingKeys = setOf("isInf", "trackingCode", "from", "refer", "src", "referrerCode", "nclicks")
        return try {
            val uri = Uri.parse(url)
            if (uri.queryParameterNames.none { it in trackingKeys }) return url
            val builder = uri.buildUpon().clearQuery()
            uri.queryParameterNames
                .filter { it !in trackingKeys }
                .forEach { builder.appendQueryParameter(it, uri.getQueryParameter(it)) }
            builder.build().toString()
        } catch (e: Exception) {
            url
        }
    }

    // Naver CDN serves small thumbnails via ?type=w80 / ?type=w160 etc.
    // Replace with w966 (Naver's largest standard size). For other CDNs strip generic
    // resize params that cause the image to be served at reduced resolution.
    private fun upgradeToFullResolution(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return url
            when {
                host.endsWith("pstatic.net") -> {
                    val typeParam = uri.getQueryParameter("type") ?: return url
                    if (Regex("""^[wm]\d+$""").matches(typeParam)) {
                        uri.buildUpon().clearQuery()
                            .appendQueryParameter("type", "w966")
                            .build().toString()
                    } else url
                }
                else -> {
                    val resizeKeys = setOf("w", "h", "width", "height", "resize", "size")
                    if (uri.queryParameterNames.none { it in resizeKeys }) return url
                    val builder = uri.buildUpon().clearQuery()
                    uri.queryParameterNames
                        .filter { it !in resizeKeys }
                        .forEach { builder.appendQueryParameter(it, uri.getQueryParameter(it)) }
                    builder.build().toString()
                }
            }
        } catch (e: Exception) {
            url
        }
    }

    suspend fun fetchPageInfoSuspend(url: String): Triple<String?, String?, String?> {
        val cleanUrl = stripTrackingParams(url)
        val naverInfo = extractNaverBlogInfo(cleanUrl)

        val primaryUrl = if (naverInfo != null) {
            // Naver blog outer shell is an iframe container — fetch the inner PostView URL directly
            "https://blog.naver.com/PostView.naver?blogId=${naverInfo.userId}&logNo=${naverInfo.postNo}"
        } else {
            cleanUrl
        }

        val result = fetchRawSuspend(primaryUrl)

        // PostView can still return thin content on some posts; mobile renderer is iframe-free
        if (naverInfo != null && (result.second?.length ?: 0) < 200) {
            val mobileUrl = "https://m.blog.naver.com/${naverInfo.userId}/${naverInfo.postNo}"
            val mobileResult = fetchRawSuspend(mobileUrl)
            if ((mobileResult.second?.length ?: 0) > (result.second?.length ?: 0)) {
                return mobileResult
            }
        }

        return result
    }

    fun fetchPageInfo(url: String, onResult: (title: String?, content: String?, imageUrl: String?) -> Unit) {
        val cleanUrl = stripTrackingParams(url)
        val naverInfo = extractNaverBlogInfo(cleanUrl)
        val fetchTarget = if (naverInfo != null) {
            "https://blog.naver.com/PostView.naver?blogId=${naverInfo.userId}&logNo=${naverInfo.postNo}"
        } else {
            cleanUrl
        }
        fetchRaw(fetchTarget, onResult)
    }

    private suspend fun fetchRawSuspend(url: String): Triple<String?, String?, String?> =
        suspendCancellableCoroutine { continuation ->
            fetchRaw(url) { title, content, imageUrl ->
                if (continuation.isActive) continuation.resume(Triple(title, content, imageUrl))
            }
        }

    private fun fetchRaw(url: String, onResult: (title: String?, content: String?, imageUrl: String?) -> Unit) {
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
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val content = resp.body?.string() ?: ""

                        val title = content.lines().firstOrNull { it.isNotBlank() }?.replace("#", "")?.trim()

                        val imageRegex = Regex("""!\[.*?\]\((https://[^)]+\.(?:jpg|jpeg|png|webp|GIF)[^)]*)\)""", RegexOption.IGNORE_CASE)
                        val images = imageRegex.findAll(content).map { upgradeToFullResolution(it.groupValues[1]) }.toList()

                        val facePatterns = listOf(
                            "profile", "author", "avatar", "member",
                            "blogger", "writer", "thumb_p", "portimage", "dthumb", "mugshot"
                        )
                        val firstImage = images.find { it.contains("cache/recipe") || it.contains("recipe") }
                            ?: images.firstOrNull { imgUrl ->
                                !imgUrl.contains("logo", ignoreCase = true) &&
                                !imgUrl.contains("icon", ignoreCase = true) &&
                                !imgUrl.contains("button", ignoreCase = true) &&
                                facePatterns.none { imgUrl.contains(it, ignoreCase = true) }
                            }

                        onResult(title, content, firstImage)
                    } else {
                        onResult(null, "페이지 로드 실패 (HTTP ${resp.code})", null)
                    }
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
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val content = response.body?.string() ?: return@use null

                val imageRegex = Regex("""!\[.*?\]\((https://[^)]+\.(?:jpg|jpeg|png|webp|GIF)[^)]*)\)""", RegexOption.IGNORE_CASE)
                val images = imageRegex.findAll(content).map { it.groupValues[1] }.toList()

                images.find { it.contains("cache/recipe") }
                    ?: images.firstOrNull {
                        !it.contains("logo", ignoreCase = true) &&
                        !it.contains("icon", ignoreCase = true)
                    }
            }
        } catch (e: Exception) {
            null
        }
    }
}
