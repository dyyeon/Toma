package com.capstone.toma

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class RecipeImageFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchImageUrl(keyword: String): String? = withContext(Dispatchers.IO) {
        val normalized = normalizeKeyword(keyword)
        val searchKeywords = listOf(keyword, normalized).distinct().filter { it.isNotBlank() }
        Log.d(TAG, "fetch start: keyword=$keyword, searches=$searchKeywords")

        val candidates = collectCandidates(searchKeywords)
            .map { it.upgradeToHttps() }
            .distinct()
        Log.d(TAG, "candidates collected: ${candidates.size}")
        candidates.forEachIndexed { i, url -> Log.d(TAG, "  [$i] $url") }

        if (candidates.isEmpty()) return@withContext null

        val evaluated = coroutineScope {
            candidates.map { url ->
                async {
                    val reachable = isImageReachable(url)
                    val ai = if (reachable) scoreImageWithAI(url, keyword) else 0
                    Triple(url, ai, reachable)
                }
            }.awaitAll()
        }
        evaluated.forEach { (url, ai, reachable) ->
            Log.d(TAG, "ai=$ai reachable=$reachable url=$url")
        }

        val reachable = evaluated.filter { it.third }
        val best = reachable.maxByOrNull { it.second }
        if (best != null && best.second >= MIN_ACCEPTABLE_SCORE) {
            Log.d(TAG, "selected: ai=${best.second} url=${best.first}")
            best.first
        } else {
            Log.d(TAG, "no reachable candidate above threshold (best ai=${best?.second})")
            null
        }
    }

    private fun String.upgradeToHttps(): String =
        if (startsWith("http://")) replaceFirst("http://", "https://") else this

    private fun isImageReachable(url: String): Boolean {
        // HEAD 시도 → 일부 서버는 HEAD 거부 또는 Content-Type 누락 → Range GET fallback
        val headResult = tryHead(url)
        if (headResult != null) return headResult
        return tryRangeGet(url)
    }

    private fun tryHead(url: String): Boolean? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .head()
                .build()
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    405, 501 -> null
                    in 200..299 -> {
                        val ct = response.header("Content-Type")?.lowercase()
                        if (ct == null) null
                        else ct.startsWith("image/")
                    }
                    else -> false
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun tryRangeGet(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-1023")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) return false
                val ct = response.header("Content-Type")?.lowercase()
                if (ct != null && ct.startsWith("image/")) return true
                // Content-Type이 없거나 모호하면 첫 바이트로 매직 넘버 체크
                return try {
            val inputStream = response.body?.byteStream() ?: return false
            val buffer = ByteArray(8)
            val bytesRead = inputStream.read(buffer)
            if (bytesRead < 8) return false
            isImageBytes(buffer)
        } catch (e: Exception) {
            false
        }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun isImageBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // JPEG: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return true
        // PNG: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        // WebP: RIFF
        if (bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte()) return true
        // GIF: 47 49 46
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte()) return true
        return false
    }

    private suspend fun collectCandidates(keywords: List<String>): List<String> {
        return coroutineScope {
            val tasks = keywords.flatMap { kw ->
                listOf(
                    async { tryNaverOpenApi(kw) },
                    async { listOfNotNull(tryPublicApi(kw)) },
                    async { tryNaverViewSearch(kw) },
                    async { tryRecipeSiteScraping(kw) },
                    async { tryNaverImageSearch(kw) }
                )
            }
            tasks.awaitAll().flatten()
                .filter { it.isNotBlank() && it.startsWith("http") }
                .distinct()
                .take(MAX_CANDIDATES)
        }
    }

    private fun tryNaverOpenApi(keyword: String): List<String> {
        return try {
            val clientId = BuildConfig.NAVER_CLIENT_ID
            val clientSecret = BuildConfig.NAVER_CLIENT_SECRET
            if (clientId.isBlank() || clientSecret.isBlank()) {
                Log.d(TAG, "naverApi: no credentials, skip")
                return emptyList()
            }

            val query = URLEncoder.encode("$keyword 요리", "UTF-8")
            val url = "https://openapi.naver.com/v1/search/image?query=$query&display=20&sort=sim"
            val request = Request.Builder()
                .url(url)
                .header("X-Naver-Client-Id", clientId)
                .header("X-Naver-Client-Secret", clientSecret)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "naverApi HTTP ${response.code}: ${response.body?.string()?.take(200)}")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
                val results = (0 until items.length()).mapNotNull { i ->
                    items.optJSONObject(i)?.optString("link")?.takeIf { it.isNotBlank() }
                }.filter { it.isFoodImage() }
                Log.d(TAG, "naverApi($keyword) → ${results.size} results")
                results
            }
        } catch (e: Exception) {
            Log.w(TAG, "naverApi error", e); emptyList()
        }
    }

    private suspend fun tryPublicApi(keyword: String): String? {
        return try {
            PublicRecipeManager().searchRecipe(keyword)
                ?.mainImageUrl
                ?.takeIf { it.isNotBlank() }
                .also { Log.d(TAG, "publicApi($keyword) → ${if (it == null) "null" else "ok"}") }
        } catch (e: Exception) {
            Log.w(TAG, "publicApi error", e); null
        }
    }

    private fun tryNaverImageSearch(keyword: String): List<String> {
        return try {
            val query = URLEncoder.encode("$keyword 요리", "UTF-8")
            val url = "https://m.search.naver.com/search.naver?where=m_image&query=$query"
            val html = fetchHtml(url) ?: return emptyList()
            val results = extractImageUrls(html).filter { it.isFoodImage() }.take(8)
            Log.d(TAG, "naver($keyword) → ${results.size} results")
            results
        } catch (e: Exception) {
            Log.w(TAG, "naver error", e); emptyList()
        }
    }

    private fun tryNaverViewSearch(keyword: String): List<String> {
        return try {
            val query = URLEncoder.encode("$keyword 레시피", "UTF-8")
            val url = "https://m.search.naver.com/search.naver?where=m_view&query=$query"
            val html = fetchHtml(url) ?: return emptyList()
            // m_view 결과는 search.pstatic.net CDN 썸네일 사용 → 핫링크 없음
            val pstaticPattern = Regex("""(https?://[a-z0-9-]+\.pstatic\.net/[^"'\s]+\.(?:jpg|jpeg|png|webp)[^"'\s]*)""")
            val results = pstaticPattern.findAll(html)
                .map { it.groupValues[1] }
                .filter { it.isFoodImage() }
                .distinct()
                .take(8)
                .toList()
            Log.d(TAG, "naverView($keyword) → ${results.size} results")
            results
        } catch (e: Exception) {
            Log.w(TAG, "naverView error", e); emptyList()
        }
    }

    private fun tryRecipeSiteScraping(keyword: String): List<String> {
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val searchHtml = fetchHtml(
                "https://www.10000recipe.com/recipe/list.html?q=$encoded"
            ) ?: return emptyList()

            val recipeIds = Regex("""/recipe/(\d+)""")
                .findAll(searchHtml)
                .map { it.groupValues[1] }
                .distinct()
                .take(MAX_PAGES_PER_SEARCH)
                .toList()

            val results = recipeIds.mapNotNull { id ->
                val recipeHtml = fetchHtml(
                    "https://www.10000recipe.com/recipe/$id"
                ) ?: return@mapNotNull null
                Regex("""<meta property="og:image" content="([^"]+)"""")
                    .find(recipeHtml)
                    ?.groupValues?.get(1)
            }
            Log.d(TAG, "10000recipe($keyword) → ${results.size} results")
            results
        } catch (e: Exception) {
            Log.w(TAG, "10000recipe error", e); emptyList()
        }
    }

    private fun extractImageUrls(html: String): List<String> {
        val patterns = listOf(
            Regex(""""murl"\s*:\s*"([^"]+)""""),
            Regex(""""originalUrl"\s*:\s*"([^"]+)""""),
            Regex(""""imageUrl"\s*:\s*"([^"]+)""""),
            Regex(""""thumbUrl"\s*:\s*"([^"]+)""""),
            Regex(""""image_url"\s*:\s*"([^"]+)""""),
            Regex(""""thumb"\s*:\s*"([^"]+)""""),
            Regex(""""src"\s*:\s*"(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)""""),
            Regex("""data-source="([^"]+)""""),
            Regex("""data-original="([^"]+)""""),
            Regex("""data-img-src="([^"]+)""""),
            Regex("""data-src="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)""""),
            Regex("""<img[^>]+src="(https?://[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)""""),
            Regex("""\"(https?://[^"\s]+\.(?:jpg|jpeg|png|webp))[^"]*\"""")
        )
        return patterns.flatMap { p ->
            p.findAll(html).map { it.groupValues[1].replace("\\/", "/").replace("\\u002F", "/") }.toList()
        }.distinct()
    }

    private fun String.isFoodImage(): Boolean {
        if (!startsWith("http")) return false
        val uri = try { java.net.URI(this) } catch (e: Exception) { return false }
        val host = uri.host ?: return false
        val path = uri.rawPath ?: ""
        if (host.length < 4 || !host.contains(".")) return false
        if (path.length < 5) return false
        val pathLower = path.lowercase()
        val hasExt = listOf(".jpg", ".jpeg", ".png", ".webp").any { pathLower.contains(it) }
        if (!hasExt) return false
        val lower = lowercase()
        val junkPatterns = listOf(
            "logo", "icon", "favicon", "blank", "noimg", "no_image", "no-image",
            "default", "sprite", "btn_", "bg_", "_bg.", "ad_", "ads/", "/ad/",
            "/profile", "/avatar", "/banner", "/header"
        )
        return junkPatterns.none { lower.contains(it) }
    }

    private fun scoreImageWithAI(imageUrl: String, recipeName: String): Int {
        return try {
            val apiKey = BuildConfig.OPENAI_API_KEY
            if (apiKey.isBlank()) {
                Log.d(TAG, "no API key, defaulting score")
                return DEFAULT_SCORE_NO_API
            }

            val prompt = """
                이 이미지가 "$recipeName" 레시피 사진으로 얼마나 좋은지 1~10점으로 평가하세요.

                평가 기준 (중요도 순):
                1. 요리 정확성 (필수): "$recipeName"이 정확히 맞는가
                2. 시각적 매력: 맛있어 보이는가, 조명·구도·플레이팅이 좋은가
                3. 대표성: 요리의 전체 모습이 잘 보이고 주재료가 명확한가
                4. 사진 품질: 선명하고 해상도가 충분한가 (블로그 썸네일에 큰 글씨가 덮여있으면 감점)

                점수 가이드:
                - 10점: 정확한 요리 + 잡지/광고급 전문가 사진
                - 8~9점: 정확한 요리 + 잘 찍힌 일반 사진 (블로그/레시피 사이트 메인컷)
                - 6~7점: 정확한 요리지만 사진이 평범하거나 약간의 텍스트가 있음
                - 4~5점: 비슷한 다른 요리이거나 사진이 흐릿/어두움
                - 1~3점: 완전히 다른 요리 또는 음식이 거의 안 보임

                요리가 정확히 맞으면 6점 이상, 거기에 사진까지 매력적이면 8~10점 주세요.
                반드시 1~10 사이의 정수 하나만 답하세요.
            """.trimIndent()

            val requestJson = JSONObject().apply {
                put("model", OpenAiConfig.IMAGE_MODEL)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", prompt)
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().apply {
                                    put("url", imageUrl)
                                    put("detail", "low")
                                })
                            })
                        })
                    })
                })
                put("max_completion_tokens", 200)
            }

            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    Log.w(TAG, "AI HTTP ${response.code} for $imageUrl: ${body?.take(200)}")
                    return DEFAULT_SCORE_NO_API
                }
                if (body.isNullOrBlank()) {
                    Log.w(TAG, "AI empty body for $imageUrl")
                    return DEFAULT_SCORE_NO_API
                }
                val content = try {
                    JSONObject(body)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                } catch (e: Exception) {
                    Log.w(TAG, "AI parse error for $imageUrl: ${body.take(300)}", e)
                    return DEFAULT_SCORE_NO_API
                }
                Log.d(TAG, "AI raw='$content' for $imageUrl")
                val score = Regex("""\d+""").find(content)?.value?.toIntOrNull()?.coerceIn(1, 10)
                score ?: run {
                    Log.w(TAG, "AI no number in response: '$content'")
                    DEFAULT_SCORE_NO_API
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI scoring exception for $imageUrl", e)
            DEFAULT_SCORE_NO_API
        }
    }

    private fun fetchHtml(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeKeyword(keyword: String): String {
        return keyword.split(Regex("\\s+"))
            .filter { it.isNotBlank() && it !in DESCRIPTOR_WORDS }
            .joinToString(" ")
            .trim()
    }

    companion object {
        private const val TAG = "RecipeImageFetcher"
        private const val MAX_CANDIDATES = 8
        private const val MAX_PAGES_PER_SEARCH = 2
        private const val MIN_ACCEPTABLE_SCORE = 4
        private const val DEFAULT_SCORE_NO_API = 7
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; SM-G960F) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private val DESCRIPTOR_WORDS = setOf(
            "빨간", "빨강", "하얀", "흰", "노란", "까만", "검은", "초록", "푸른",
            "매운", "맵게", "매콤한", "매콤", "달콤한", "달콤", "짭짤한", "고소한",
            "시원한", "얼큰한", "담백한", "진한", "묽은", "새콤한", "새콤", "구수한",
            "안맵게", "안매운", "덜매운",
            "할머니", "할머니표", "어머니", "엄마", "엄마표", "할매", "외할머니표",
            "옛날", "옛날식", "정통", "전통", "전통식", "토속",
            "집밥", "집에서", "백종원", "백종원식", "수미네",
            "간단한", "간단", "쉬운", "쉽게", "빠른", "초간단", "초보",
            "바삭한", "바삭", "부드러운", "촉촉한", "쫀득한", "쫄깃한",
            "진짜", "찐", "완벽한", "최고의", "최고", "정석", "끝판왕"
        )
    }
}
