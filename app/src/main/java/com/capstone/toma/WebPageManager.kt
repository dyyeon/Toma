package com.capstone.toma

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.regex.Pattern
import kotlin.coroutines.resume

class WebPageManager {
    private val client = OkHttpClient()

    /**
     * Coroutine-friendly suspend function to fetch page info
     */
    suspend fun fetchPageInfoSuspend(url: String): Triple<String?, String?, String?> = suspendCancellableCoroutine { continuation ->
        fetchPageInfo(url) { title, content, imageUrl ->
            if (continuation.isActive) {
                continuation.resume(Triple(title, content, imageUrl))
            }
        }
    }

    fun fetchPageInfo(url: String, onResult: (title: String?, content: String?, imageUrl: String?) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null, null, null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    
                    // 네이버 블로그 iframe 대응
                    if (url.contains("blog.naver.com") && !url.contains("PostView.naver")) {
                        val iframeUrl = extractNaverIframeUrl(html, url)
                        if (iframeUrl != null) {
                            fetchPageInfo(iframeUrl, onResult)
                            return
                        }
                    }

                    val title = extractMeta(html, "og:title") ?: extractTag(html, "title")
                    val description = extractMeta(html, "og:description") ?: extractMeta(html, "description")
                    val imageUrl = extractMeta(html, "og:image")
                    
                    // 본문 텍스트 추가 추출 (HTML 태그 제거 후 핵심 텍스트만)
                    val bodyText = extractVisibleText(html)
                    
                    if (title == null && (bodyText.isEmpty() || bodyText.length < 50)) {
                        onResult(null, "페이지 내용을 읽어올 수 없습니다. 보호된 페이지거나 접근이 제한되었을 수 있습니다.", null)
                    } else {
                        val combinedContent = "요약: ${description ?: "없음"}\n본문: $bodyText"
                        onResult(title, combinedContent, imageUrl)
                    }
                } else {
                    onResult(null, "웹 페이지를 불러오는 데 실패했습니다. (에러 코드: ${response.code})", null)
                }
            }
        })
    }

    private fun extractVisibleText(html: String): String {
        // 간단한 방식으로 HTML 태그를 제거하고 텍스트만 추출 (최대 1000자)
        val textOnly = html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        return if (textOnly.length > 1000) textOnly.take(1000) else textOnly
    }

    private fun extractNaverIframeUrl(html: String, originalUrl: String): String? {
        return try {
            val pattern = Pattern.compile("id=\"mainFrame\" src=\"([^\"]+)\"")
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val src = matcher.group(1)
                if (src != null) {
                    "https://blog.naver.com$src"
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractMeta(html: String, property: String): String? {
        val pattern = Pattern.compile("<meta [^>]*property=[\"']$property[\"'] [^>]*content=[\"']([^\"']+)[\"'][^>]*>")
        val patternAlt = Pattern.compile("<meta [^>]*name=[\"']$property[\"'] [^>]*content=[\"']([^\"']+)[\"'][^>]*>")
        
        val matcher = pattern.matcher(html)
        if (matcher.find()) return matcher.group(1)
        
        val matcherAlt = patternAlt.matcher(html)
        if (matcherAlt.find()) return matcherAlt.group(1)
        
        return null
    }

    private fun extractTag(html: String, tag: String): String? {
        val pattern = Pattern.compile("<$tag>(.*?)</$tag>", Pattern.DOTALL)
        val matcher = pattern.matcher(html)
        return if (matcher.find()) matcher.group(1)?.trim() else null
    }
}
