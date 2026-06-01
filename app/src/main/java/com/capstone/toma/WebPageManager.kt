package com.capstone.toma

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class WebPageManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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

    private fun upgradeToFullResolution(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return url
            when {
                // Naver CDN: type 쿼리에 사이즈 숫자가 있으면 무조건 w966으로 교체 (w80, m120, ffn200_200_0 등 모두)
                host.endsWith("pstatic.net") -> {
                    val typeParam = uri.getQueryParameter("type") ?: return url
                    if (typeParam.none { it.isDigit() }) return url
                    val builder = uri.buildUpon().clearQuery()
                    uri.queryParameterNames
                        .filter { it != "type" }
                        .forEach { builder.appendQueryParameter(it, uri.getQueryParameter(it)) }
                    builder.appendQueryParameter("type", "w966").build().toString()
                }
                // 10000recipe: `_280X205`, `-280x205c` 같은 다양한 사이즈 접미사 제거
                host.endsWith("ezmember.co.kr") -> {
                    val path = uri.path ?: return url
                    val stripped = path.replace(
                        Regex("""[-_]\d+[Xx]\d+[a-zA-Z]?(?=\.[A-Za-z]{3,4}$)"""),
                        ""
                    )
                    if (stripped == path) url
                    else uri.buildUpon().path(stripped).build().toString()
                }
                // Kakao/Daum thumb proxy: `?fname=<원본URL>&width=...`. fname의 원본 URL을 그대로 사용.
                host.endsWith("daumcdn.net") || host.endsWith("kakaocdn.net") -> {
                    val fname = uri.getQueryParameter("fname")
                    if (!fname.isNullOrBlank() &&
                        (fname.startsWith("http://") || fname.startsWith("https://"))
                    ) {
                        fname
                    } else url
                }
                // Tistory: 경로 앞의 `/R800x0/` 같은 리사이즈 세그먼트 제거
                host.endsWith("tistory.com") -> {
                    val path = uri.path ?: return url
                    val stripped = path.replace(Regex("""^/R\d+x\d+/"""), "/")
                    if (stripped == path) url
                    else uri.buildUpon().path(stripped).build().toString()
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
            val k = keyword.trim()
            if (k.isBlank()) return@withContext null

            val encoded = URLEncoder.encode(k, "UTF-8")
            val searchUrl = "https://www.10000recipe.com/recipe/list.html?q=$encoded"
            val request = Request.Builder()
                .url("https://r.jina.ai/$searchUrl")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val content = response.body?.string() ?: return@use null

                pickRecipeImage(content, k)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 마크다운 컨텐츠에서 키워드와 가장 일치하는 레시피 이미지를 선택한다.
     *
     * 점수 기준:
     *  +100  : alt 텍스트가 키워드를 포함
     *  +60   : 이미지 주변 텍스트 윈도우(±250자)에 키워드가 존재
     *  +30   : URL이 cache/recipe 패턴을 포함 (10000recipe 썸네일)
     *
     * 제외 조건: logo, icon, banner, default, profile, avatar 등
     * 최소 점수 60 미만이면 null 반환 → 잘못된 이미지를 보여주느니 안 보여주는 편이 낫다.
     */
    private fun pickRecipeImage(content: String, keyword: String): String? {
        val imageRegex = Regex("""!\[([^\]]*)\]\((https://[^)]+\.(?:jpg|jpeg|png|webp|GIF)[^)]*)\)""", RegexOption.IGNORE_CASE)
        val excludePatterns = listOf("logo", "icon", "banner", "default", "profile", "avatar", "thumb_p", "mugshot")

        val candidates = imageRegex.findAll(content).mapNotNull { match ->
            val alt = match.groupValues[1]
            val url = match.groupValues[2]
            if (excludePatterns.any { url.contains(it, ignoreCase = true) }) return@mapNotNull null

            val start = maxOf(0, match.range.first - 250)
            val end = minOf(content.length, match.range.last + 250)
            val window = content.substring(start, end)

            var score = 0
            if (alt.contains(keyword, ignoreCase = true)) score += 100
            if (window.contains(keyword, ignoreCase = true)) score += 60
            if (url.contains("cache/recipe")) score += 30
            if (score > 0) url to score else null
        }.toList()

        val best = candidates.maxByOrNull { it.second } ?: return null
        return if (best.second >= 60) upgradeToFullResolution(best.first) else null
    }
}
