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
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null, "네트워크 연결 실패", null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    
                    // 1. 네이버 블로그 Iframe 처리 (인플루언서 및 일반 블로그 모두 대응)
                    if (url.contains("blog.naver.com") && !url.contains("PostView.naver")) {
                        val iframeUrl = extractNaverIframeUrl(html, url)
                        if (iframeUrl != null) {
                            fetchPageInfo(iframeUrl, onResult)
                            return
                        }
                    }

                    // 2. 메타 데이터 및 본문 추출
                    val title = extractMeta(html, "og:title") ?: extractTag(html, "title")
                    val description = extractMeta(html, "og:description")
                    val imageUrl = extractMeta(html, "og:image")
                    
                    // 3. 스마트에디터(se-viewer) 본문 집중 추출
                    val bodyText = extractCleanBodyText(html)
                    
                    if (title == null && bodyText.isBlank()) {
                        onResult(null, "본문을 읽어올 수 없습니다.", null)
                    } else {
                        val combinedContent = """
                            [Source URL]: $url
                            [Title]: ${title ?: "No Title"}
                            [Summary]: ${description ?: "No Summary"}
                            [Cleaned Content]:
                            $bodyText
                        """.trimIndent()
                        onResult(title, combinedContent, imageUrl)
                    }
                } else {
                    onResult(null, "페이지 로드 실패 (${response.code})", null)
                }
            }
        })
    }

    private fun extractCleanBodyText(html: String): String {
        // 불필요한 태그 선제거
        var content = html.replace(Regex("<(script|style|nav|footer|header|iframe|noscript)[^>]*?>.*?</\\1>", RegexOption.DOT_MATCHES_ALL), "")
        
        // 네이버 블로그의 실제 본문 영역(se-viewer, se-main-container) 위주로 텍스트 추출 시도
        // 정규식으로 클래스 기반 추출 시도 (완벽하진 않으나 텍스트 밀도를 높임)
        
        // 모든 태그 제거
        var text = content.replace(Regex("<[^>]*>"), " ")
        
        // 엔티티 변환 및 공백 정리
        text = text.replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

        // AI 분석 효율을 위해 핵심 텍스트 보존 (너무 길면 자름)
        return if (text.length > 3000) text.take(3000) else text
    }

    private fun extractNaverIframeUrl(html: String, originalUrl: String): String? {
        // 일반 블로그 및 인플루언서 블로그의 iframe src 패턴 대응
        val patterns = listOf(
            Pattern.compile("id=\"mainFrame\"\\s+src=\"([^\"]+)\""),
            Pattern.compile("src='(https://blog.naver.com/PostView.naver[^']+)'"),
            Pattern.compile("src=\"([^\"]*PostView\\.naver[^\"]*)\"")
        )
        
        for (pattern in patterns) {
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                var src = matcher.group(1) ?: continue
                if (src.startsWith("/")) {
                    src = "https://blog.naver.com$src"
                }
                return src
            }
        }
        return null
    }

    private fun extractMeta(html: String, property: String): String? {
        val pattern = Pattern.compile("<meta [^>]*?(?:property|name)=[\"']$property[\"'] [^>]*?content=[\"']([^\"']+)[\"']")
        val matcher = pattern.matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractTag(html: String, tag: String): String? {
        val pattern = Pattern.compile("<$tag[^>]*>(.*?)</$tag>", Pattern.DOTALL)
        val matcher = pattern.matcher(html)
        return if (matcher.find()) matcher.group(1)?.trim() else null
    }
}
