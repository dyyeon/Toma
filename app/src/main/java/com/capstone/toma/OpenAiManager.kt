package com.capstone.toma

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.capstone.toma.model.normalizeRecipeCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class OpenAiManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val apiKey = BuildConfig.OPENAI_API_KEY

    private fun hasApiKey(): Boolean = apiKey.isNotBlank()

    private fun parseApiError(response: Response, body: String?): String {
        return try {
            val json = JSONObject(body ?: "")
            val error = json.optJSONObject("error")
            error?.optString("message") ?: "API 요청 실패 (${response.code})"
        } catch (e: Exception) {
            "API 요청 실패 (${response.code})"
        }
    }

    private fun buildChatSystemPrompt(): String {
        return """
            You are 'Toma', a warm and knowledgeable AI cooking assistant.
            You are STRICTLY LIMITED to food, cooking, recipes, ingredients, kitchen tools,
            meal planning, nutrition, and grocery topics. Nothing else.

            ══ SCOPE ENFORCEMENT (HIGHEST PRIORITY) ══
            If the user asks about ANY non-food topic — including but not limited to:
            영화, 드라마, 음악, 게임, 스포츠, 연예인, 날씨, 뉴스, 정치, 주식, 코딩,
            여행, 운동, 의료, 법률, 학업, 연애, 일반 상식, 챗봇 자체에 대한 질문
            (movies, dramas, music, games, sports, celebrities, weather, news, politics,
            stocks, coding, travel, fitness, medical, legal, study, relationships,
            general trivia, meta questions about yourself) —
            you MUST REFUSE.

            Refusal response format (mandatory JSON):
            {
              "type": "chat",
              "response": "저는 요리 전문 어시스턴트라서 그 주제는 도와드릴 수 없어요. 요리, 레시피, 재료, 식단 관련해서 무엇이든 물어봐 주세요!"
            }

            Do NOT suggest categories, do NOT recommend alternatives in the off-topic domain,
            do NOT engage with the off-topic request even partially. Simply refuse and redirect to cooking.

            Borderline cases ALLOWED (treat as on-topic): "오늘 뭐 먹을까", "다이어트 식단",
            "재료 보관법", "주방 도구 추천", "식당 메뉴 따라 만들기" — these are food-related.
            Borderline cases REFUSED: "영화 보고 싶다", "음악 추천", "스트레스 받아" without food context.

            ══ FORMAT RULES ══
            1. Response must be in valid JSON format.
            2. ALL output fields MUST be in Korean.
            3. If the user asks for a recipe (on-topic only), you MUST return "type": "recipe_search" with full "recipe_data".

            [JSON STRUCTURE]
            - For Recipes:
            {
              "type": "recipe_search",
              "keyword": "요리명",
              "response": "친절한 안내 문구",
              "recipe_data": {
                "title": "요리명",
                "category": "한식/중식/일식/양식/디저트 등",
                "ingredients": ["재료1 분량", "재료2 분량", ...],
                "steps": ["1단계 상세 설명", "2단계 상세 설명", ...],
                "difficulty": "쉬움/보통/어려움",
                "time": "00분",
                "image_url": "완성된 요리가 잘 담긴 대표 이미지 URL 1개. 없으면 빈 문자열."
              }
            }
            - For General Chat (on-topic food questions) AND for refusals:
            {
              "type": "chat",
              "response": "한국어 답변"
            }
            - For web pages where there is truly no readable recipe content at all:
            {
              "type": "insufficient_content",
              "keyword": "추정 요리명. 페이지 제목에서 유추. 알 수 없으면 빈 문자열.",
              "response": "이 페이지에서 레시피 내용을 충분히 읽어오지 못했어요.\n제목을 기반으로 일반 레시피를 만들어 드릴까요?"
            }
            Use 'insufficient_content' ONLY when the page body is essentially empty:
            e.g. blank page, JS-rendered with no text, login-walled, or just a title with no food content.
            If the page contains a dish name plus ANY ingredients or cooking steps — even informal,
            incomplete, or unstructured — extract what is available and return 'recipe_search'.
            Missing quantities, casual tone, and imperfect formatting are NOT reasons to reject.
            Be permissive about extracting. Only use 'insufficient_content' when there is
            truly nothing to work with.
        """.trimIndent()
    }

    fun transcribeAudio(audioFile: File, onResult: (String?) -> Unit) {
        if (!hasApiKey()) {
            onResult(null)
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("language", "ko")
            .addFormDataPart(
                "prompt",
                "한국어 요리 관련 음성입니다. 요리 재료, 조리법, 음식 이름이 포함될 수 있습니다. " +
                        "발음이 불명확한 경우 요리 관련 단어로 보정해주세요. " +
                        "예: 김치찌개, 된장국, 볶음밥, 삼겹살, 파스타"
            )
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onResult(null)

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    onResult(JSONObject(body).optString("text"))
                } else {
                    onResult(null)
                }
            }
        })
    }

    suspend fun processChatRequestSuspend(
        userText: String,
        history: List<Pair<String, Boolean>>
    ): VoiceRequestResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            processChatRequest(userText, history) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    fun processChatRequest(
        userText: String,
        history: List<Pair<String, Boolean>>,
        onResult: (VoiceRequestResult) -> Unit
    ) {
        if (!hasApiKey()) {
            onResult(VoiceRequestResult.Error("API 키 없음"))
            return
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", buildChatSystemPrompt())
            })

            history.takeLast(10).forEach { (text, isUser) ->
                put(JSONObject().apply {
                    put("role", if (isUser) "user" else "assistant")
                    put("content", text)
                })
            }

            put(JSONObject().apply {
                put("role", "user")
                put("content", userText)
            })
        }

        val chosenModel =
            if (isComplexRequest(userText)) OpenAiConfig.ADVANCED_MODEL
            else OpenAiConfig.DEFAULT_TEXT_MODEL

        val requestJson = JSONObject().apply {
            put("model", chosenModel)
            put("messages", messages)
            put("response_format", JSONObject().apply { put("type", "json_object") })
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) =
                onResult(VoiceRequestResult.Error(e.message ?: "Network Error"))

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody == null) {
                    onResult(VoiceRequestResult.Error(parseApiError(response, responseBody)))
                    return
                }

                try {
                    val content = JSONObject(responseBody)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")

                    val resultJson = JSONObject(content)

                    onResult(
                        VoiceRequestResult.Success(
                            requestType = resultJson.optString("type", "chat"),
                            keyword = resultJson.optString("keyword", ""),
                            responseMessage = resultJson.optString("response", ""),
                            recipeData = normalizeRecipeData(resultJson.optJSONObject("recipe_data"))?.toString()
                        )
                    )
                } catch (e: Exception) {
                    onResult(VoiceRequestResult.Error("응답 파싱 오류"))
                }
            }
        })
    }

    suspend fun analyzeRecipeImageSuspend(
        context: Context,
        imageUri: String
    ): VoiceRequestResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                val uri = Uri.parse(imageUri)
                val bitmap = decodeImageForAnalysis(context, uri) ?: throw Exception("이미지 로드 실패")

                val outputStream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

                val requestJson = JSONObject().apply {
                    put("model", OpenAiConfig.IMAGE_MODEL)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", """
                                        너는 한국어 요리 레시피 이미지 OCR 분석기다.

                                        이 작업은 음식 사진 분류가 아니라, 이미지 속 한글 레시피 문서를 읽는 작업이다.
                                        반드시 이미지에 실제로 보이는 텍스트를 최우선으로 읽어라.
                                        절대 추측하지 마라.
                                        이미지에 없는 재료를 추가하지 마라.
                                        요리 상식으로 보정하지 마라.
                                        잘 안 보이면 "불확실"로 표시하라.

                                        규칙:
                                        1. 제목은 이미지에 실제로 보이는 텍스트 기반으로만 추출한다.
                                        2. 재료는 이미지에 명시된 것만 적는다.
                                        3. 양념/재료가 불분명하면 추론하지 말고 제외하거나 "불확실"에 넣는다.
                                        4. 조리 순서는 이미지에 보이는 텍스트 기준으로만 정리한다.
                                        5. JSON 외의 텍스트는 출력하지 마라.

                                        아래 형식으로만 반환:
                                        {
                                          "type": "recipe_search",
                                          "keyword": "대표 요리명 또는 불확실",
                                          "response": "레시피 문서를 분석했어요.",
                                          "recipe_data": {
                                            "title": "요리명 또는 불확실",
                                            "category": "한식/중식/일식/양식/디저트/기타",
                                            "ingredients": ["재료1", "재료2"],
                                            "steps": ["1단계", "2단계"],
                                            "difficulty": "쉬움/보통/어려움/불확실",
                                            "time": "00분/불확실"
                                          }
                                        }
                                    """.trimIndent())
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:image/jpeg;base64,$base64Image")
                                        put("detail", "high")
                                    })
                                })
                            })
                        })
                    })
                    put("response_format", JSONObject().apply { put("type", "json_object") })
                }

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resume(VoiceRequestResult.Error(e.message ?: "Error"))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            try {
                                val content = JSONObject(body)
                                    .getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")

                                val resultJson = JSONObject(content)
                                val recipeJson = normalizeRecipeImageResult(resultJson, imageUri)

                                if (continuation.isActive) {
                                    continuation.resume(
                                        VoiceRequestResult.Success(
                                            "recipe_search",
                                            recipeJson.optString("title", resultJson.optString("keyword", "이미지 레시피")),
                                            resultJson.optString("response", "분석 완료"),
                                            recipeJson.toString()
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                if (continuation.isActive) {
                                    continuation.resume(VoiceRequestResult.Error("이미지 분석 응답 파싱 실패"))
                                }
                            }
                        } else {
                            if (continuation.isActive) {
                                continuation.resume(VoiceRequestResult.Error(parseApiError(response, body)))
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(VoiceRequestResult.Error(e.message ?: "Exception"))
                }
            }
        }
    }

    private fun decodeImageForAnalysis(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sampleSize = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / sampleSize > 2200) {
            sampleSize *= 2
        }

        return resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }
    }

    private fun normalizeRecipeImageResult(resultJson: JSONObject, imageUri: String): JSONObject {
        val recipeJson = resultJson.optJSONObject("recipe_data") ?: JSONObject()

        val ingredientsArray = recipeJson.optJSONArray("ingredients")
        if (ingredientsArray != null && ingredientsArray.length() > 0) {
            recipeJson.put("ingredients", normalizeIngredientArray(ingredientsArray))
        }

        val stepsArrayRaw = recipeJson.optJSONArray("steps")
        if (stepsArrayRaw != null && stepsArrayRaw.length() > 0) {
            recipeJson.put("steps", normalizeStepArray(stepsArrayRaw))
        }

        val stepsArray = recipeJson.optJSONArray("steps")
        val stepsText = buildString {
            if (stepsArray != null) {
                for (i in 0 until stepsArray.length()) {
                    append(stepsArray.optString(i))
                    append(" ")
                }
            }
        }

        val normalizedIngredientsArray = recipeJson.optJSONArray("ingredients")
        val ingredientsText = buildString {
            if (normalizedIngredientsArray != null) {
                for (i in 0 until normalizedIngredientsArray.length()) {
                    append(normalizedIngredientsArray.optString(i))
                    append(" ")
                }
            }
        }

        val combinedText = (
                recipeJson.optString("title") + " " +
                        resultJson.optString("keyword") + " " +
                        stepsText + " " +
                        ingredientsText
                ).lowercase()

        if (
            combinedText.contains("간장") &&
            combinedText.contains("계란") &&
            (combinedText.contains("밥") || combinedText.contains("밥위에") || combinedText.contains("한공기"))
        ) {
            if (recipeJson.optString("title").isBlank() || recipeJson.optString("title") == "불확실") {
                recipeJson.put("title", "간장계란밥")
            }
            if (recipeJson.optString("category").isBlank()) {
                recipeJson.put("category", "한식")
            }
            if (recipeJson.optString("difficulty").isBlank()) {
                recipeJson.put("difficulty", "쉬움")
            }
        }

        if (recipeJson.optString("image_url").isBlank()) {
            recipeJson.put("image_url", imageUri)
        }

        return normalizeRecipeData(recipeJson) ?: recipeJson
    }

    private fun normalizeIngredientArray(ingredientsArray: JSONArray): JSONArray {
        val normalized = JSONArray()
        val seen = linkedSetOf<String>()

        for (i in 0 until ingredientsArray.length()) {
            val raw = ingredientsArray.optString(i).trim()
            if (raw.isBlank()) continue

            val fixed = fixCommonOcrErrors(raw)
            if (fixed.isBlank()) continue
            if (seen.add(fixed)) {
                normalized.put(fixed)
            }
        }
        return normalized
    }

    private fun normalizeStepArray(stepsArray: JSONArray): JSONArray {
        val normalized = JSONArray()

        for (i in 0 until stepsArray.length()) {
            val raw = stepsArray.optString(i).trim()
            if (raw.isBlank()) continue

            val fixed = fixCommonStepOcrErrors(raw)
            if (fixed.isBlank()) continue
            normalized.put(fixed)
        }
        return normalized
    }

    private fun fixCommonOcrErrors(text: String): String {
        var fixed = text

        fixed = fixed.replace(Regex("\\s+"), " ").trim()
        fixed = fixed.replace("：", ":")
        fixed = fixed.replace("，", ",")
        fixed = fixed.replace("．", ".")
        fixed = fixed.replace("／", "/")

        fixed = fixed.replace(Regex("(\\d)\\s*공\\s*기"), "$1공기")
        fixed = fixed.replace(Regex("(\\d)\\s*개"), "$1개")
        fixed = fixed.replace(Regex("(\\d)\\s*T"), "$1T")
        fixed = fixed.replace(Regex("(\\d)\\s*t"), "$1T")
        fixed = fixed.replace(Regex("(\\d)\\s*큰\\s*술"), "$1T")
        fixed = fixed.replace(Regex("(\\d)\\s*작은\\s*술"), "$1t")
        fixed = fixed.replace(Regex("(\\d)\\s*컵"), "$1컵")

        fixed = fixed.replace(Regex("([가-힣A-Za-z]+)(\\d+공기)"), "$1 $2")
        fixed = fixed.replace(Regex("([가-힣A-Za-z]+)(\\d+개)"), "$1 $2")
        fixed = fixed.replace(Regex("([가-힣A-Za-z]+)(\\d+T)"), "$1 $2")
        fixed = fixed.replace(Regex("([가-힣A-Za-z]+)(\\d+t)"), "$1 $2")
        fixed = fixed.replace(Regex("([가-힣A-Za-z]+)(\\d+컵)"), "$1 $2")

        fixed = fixed.replace("공기공기", "공기")
        fixed = fixed.replace("개개", "개")
        fixed = fixed.replace("TT", "T")
        fixed = fixed.replace("tt", "t")
        fixed = fixed.replace("컵컵", "컵")

        fixed = fixed.replace(Regex("(\\d+공기)\\s+\\1"), "$1")
        fixed = fixed.replace(Regex("(\\d+개)\\s+\\1"), "$1")
        fixed = fixed.replace(Regex("(\\d+T)\\s+\\1"), "$1")
        fixed = fixed.replace(Regex("(\\d+t)\\s+\\1"), "$1")
        fixed = fixed.replace(Regex("(\\d+컵)\\s+\\1"), "$1")

        val safeCorrections = mapOf(
            "참기론" to "참기름",
            "참기룸" to "참기름",
            "잠기름" to "참기름",
            "짐간장" to "진간장",
            "긴간장" to "진간장",
            "게란" to "계란",
            "통게" to "통깨"
        )

        safeCorrections.forEach { (wrong, correct) ->
            fixed = fixed.replace(wrong, correct)
        }

        fixed = fixed.replace(Regex("\\s+"), " ").trim()
        return fixed
    }

    private fun fixCommonStepOcrErrors(text: String): String {
        var fixed = text

        fixed = fixed.replace(Regex("\\s+"), " ").trim()
        fixed = fixed.replace("：", ":")
        fixed = fixed.replace("，", ",")
        fixed = fixed.replace("．", ".")
        fixed = fixed.replace("／", "/")

        fixed = fixed.replace("전자렌지", "전자레인지")
        fixed = fixed.replace("레인지에에", "레인지에")
        fixed = fixed.replace("노른자룰", "노른자를")
        fixed = fixed.replace("살찍", "살짝")
        fixed = fixed.replace("살작", "살짝")
        fixed = fixed.replace("찔러 주세오", "찔러주세요")
        fixed = fixed.replace("찔러주세오", "찔러주세요")
        fixed = fixed.replace("질러주세요", "찔러주세요")

        val weirdPrefixToRemove = listOf("코코나", "코로나", "코코아")
        weirdPrefixToRemove.forEach { prefix ->
            fixed = fixed.replace("$prefix 젓가락으로", "젓가락으로")
        }

        fixed = fixed.replace("저가락으로", "젓가락으로")
        fixed = fixed.replace("젖가락으로", "젓가락으로")
        fixed = fixed.replace("젓가락으르", "젓가락으로")
        fixed = fixed.replace("젓가락으토", "젓가락으로")
        fixed = fixed.replace("젓가락으로로", "젓가락으로")

        fixed = fixed.replace("때문에 때문에", "때문에")
        fixed = fixed.replace("살짝 살짝", "살짝")

        fixed = fixed.replace(Regex("\\s+"), " ").trim()
        return fixed
    }

    private fun normalizeRecipeData(recipeJson: JSONObject?): JSONObject? {
        if (recipeJson == null) return null
        recipeJson.put(
            "category",
            normalizeRecipeCategory(recipeJson.optString("category"), recipeJson.optString("title"))
        )
        return recipeJson
    }

    private fun isComplexRequest(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("알레르기") || lower.contains("빼고") || lower.contains("왜") || text.length > 200
    }
}

sealed class VoiceRequestResult {
    data class Success(
        val requestType: String,
        val keyword: String,
        val responseMessage: String,
        val recipeData: String? = null
    ) : VoiceRequestResult()

    data class Error(val message: String) : VoiceRequestResult()
}