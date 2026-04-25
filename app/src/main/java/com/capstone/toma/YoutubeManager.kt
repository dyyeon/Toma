package com.capstone.toma

import okhttp3.*
import java.io.IOException
import java.util.regex.Pattern

class YoutubeManager {
    private val client = OkHttpClient()

    /**
     * 유튜브 페이지 HTML에서 제목과 설명란을 추출합니다.
     */
    fun fetchVideoInfo(url: String, onResult: (title: String?, description: String?) -> Unit) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(null, null)
            }

            override fun onResponse(call: Call, response: Response) {
                val html = response.body?.string() ?: ""
                
                // 1. 기본 메타 데이터
                val title = extractMeta(html, "title")
                val description = extractMeta(html, "description")
                
                // 2. 구조화된 레시피 데이터 (JSON-LD) 추출 시도
                val recipeJson = extractRecipeJson(html)
                
                // 만약 구조화 데이터가 있다면 설명란 대신 그걸 우선 사용
                val finalDescription = if (!recipeJson.isNullOrBlank()) {
                    "--- 구조화된 레시피 데이터 ---\n$recipeJson\n\n--- 일반 설명 ---\n$description"
                } else {
                    description
                }
                
                onResult(title, finalDescription)
            }
        })
    }

    private fun extractMeta(html: String, property: String): String? {
        val pattern = Pattern.compile("<meta name=\"$property\" content=\"([^\"]+)\">")
        val matcher = pattern.matcher(html)
        return if (matcher.find()) matcher.group(1) else null
    }

    /**
     * 유튜브 HTML 내부의 application/ld+json (Recipe 스키마)을 찾아냅니다.
     */
    private fun extractRecipeJson(html: String): String? {
        return try {
            val pattern = Pattern.compile("<script type=\"application/ld\\+json\">(.+?)</script>", Pattern.DOTALL)
            val matcher = pattern.matcher(html)
            
            val sb = StringBuilder()
            while (matcher.find()) {
                val json = matcher.group(1)
                if (json != null && (json.contains("\"@type\":\"Recipe\"") || json.contains("Recipe"))) {
                    sb.append(json).append("\n")
                }
            }
            if (sb.isEmpty()) null else sb.toString()
        } catch (e: Exception) {
            null
        }
    }
}
