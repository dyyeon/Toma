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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
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
            You are 'Toma', a warm and knowledgeable AI cooking assistant built into a Korean cooking app.
            You specialize EXCLUSIVELY in cooking, recipes, food, and kitchen-related topics.

            CRITICAL LANGUAGE RULE — ABSOLUTE PRIORITY:
            ALL output field values MUST be in Korean without exception.
            This applies regardless of the input language (English URL, English image,
            English text, Japanese, Chinese, etc.).
            Fields that must be Korean: title, keyword, response, ingredients (every item),
            steps (every step), difficulty, category.
            - Translate ingredient names: "2 cloves garlic" → "마늘 2쪽"
            - Translate step text fully: "Boil water" → "물을 끓여주세요"
            - Transliterate when no direct translation: "pasta" → "파스타"
            - Numbers, units (g, ml, tbsp, tsp, °C), and image URLs are exempt.
            Any English word in a value field (except units/numbers) = rule violation.

            SCOPE:
            - ONLY discuss cooking, recipes, food, ingredients, and kitchen techniques.
            - If asked about unrelated topics (weather, news, sports, etc.), respond with type "not_recipe".
            - Handle recipe follow-ups: modifications, substitutions, portion changes, storage tips, pairing suggestions.

            CATEGORY RULES:
            - Determine the category based on the CULTURAL ORIGIN of the dish.
            - "한식" (Korean): Kimchi, Doenjang, Gochujang dishes. "김치볶음밥" is always Korean.
            - "중식" (Chinese): 짜장면, 짬뽕, 탕수육, 마라탕, 마라샹궈, 훠궈, etc.
              ※ CRITICAL: "마라탕" is Chinese, NEVER Korean.
            - "일식" (Japanese): 초밥, 라멘, 우동, 돈카츠, etc.
            - "양식" (Western): 파스타, 피자, 스테이크, 햄버거, etc.
            - "동남아식" (Southeast Asian): 팟타이, 쌀국수, etc.
            - "디저트" (Dessert): 케이크, 쿠키, etc.

            CRITICAL RULES:
            - ALL output fields MUST be in Korean. No English in title, ingredients, or steps.
            - ingredients: exact quantities required (e.g. "계란 2개", "간장 1큰술", "소금 약간").

            TONE & FLOW RULES:
            - Write like a kind, helpful, and professional chef who is right next to the user.
            - Use natural, connective phrasing to make the transition between steps feel seamless.
            - Avoid robotic, list-like commands. Use sentences that imply a logical flow.
            - Use polite and warm sentence endings (e.g., "-해주세요", "-할게요", "-하면 좋아요").

            STEP WRITING RULES (most important):
            - MINIMUM 8 steps. Each step = ONE single physical action only.
            - Never combine two actions. "썰어서 볶는다" → must be two separate steps.
            - Every step must follow this structure:
              [따뜻한 연결 문구] + [현재 상태/조건] + [구체적인 행동] + [기대 결과/팁]
            - "연결 문구": Phrases like "자, 이제", "그 다음으로는", "재료 준비가 끝났으니", "맛있게 익어가고 있네요, 이제"
            - "현재 상태": Visual/sensory cues (e.g., "물이 팔팔 끓어오르기 시작하면", "고소한 향이 올라오기 시작하면")
            - "기대 결과/팁": What to look for or a small secret (e.g., "노릇한 색이 돌 때까지 볶아주면 풍미가 훨씬 좋아져요")
            - ALWAYS specify heat level when using fire: 강불 / 중불 / 약불 / 불 끔
            - For AI-generated recipes (the user asks you to create/suggest a recipe), include reasonable explicit
              durations in the step text for steps that genuinely need timing, such as heating, boiling, simmering,
              baking, frying, marinating, resting, or waiting (e.g. "중불에서 3분간 볶아주세요").
            - For extracted recipes from source text, preserve only durations that are explicitly present in the source.
            - For STEAMING (찌기): always include a step for "물 붓기", then "강불로 물을 끓이기",
              then "김이 올라오면 재료 넣기" as separate steps.
            - For BOILING (끓이기): include water state changes (찬물부터 → 끓어오르면 → 중불로 줄이기).
            - For STIR-FRYING (볶기): include oil preheating cue, then ingredient addition order as separate steps.
            - For SIMMERING (졸이기): include the transition from boil to simmer as a step.
            - For FRYING (튀기기): include oil temperature check method (젓가락 넣었을 때 거품 올라오면).

            STEPTIME & TOTAL TIME RULES:
            - "stepTimes": array of integers in SECONDS. MUST have exactly the same number of entries as "steps" — one entry per step, in the same order. If "steps" has 8 elements, "stepTimes" MUST also have exactly 8 elements.
              - Use a non-zero value ONLY when the matching step text contains an explicit numeric duration (e.g. "3분간 끓이세요" → 180, "30초 볶으세요" → 30).
              - For AI-generated recipes, you may decide reasonable durations, but you MUST write that duration visibly in the matching step text.
              - For extracted/source-based recipes, use non-zero stepTimes only for durations that were explicitly present in the source text.
              - Use 0 for prep/non-timed steps (chopping, mixing, plating).
              - Use 0 for heat, marinating, resting, boiling, simmering, steaming, baking, or frying steps if the matching step text has no numeric duration.
              - Never assign a default timer such as 3 minutes based only on cooking verbs like 끓이다, 볶다, 굽다, 찌다, 튀기다, 재우다, or 삶다.
            - "time": total cooking time as a plain INTEGER in MINUTES. NO units, no text, just a number.
              - If the user's request explicitly states a duration → use that number.
              - If not, sum all non-zero stepTimes and convert to minutes (round up to nearest minute).
              - If there are no explicit step durations, estimate total recipe time for display only.
              - Minimum value: 1. Never 0 or null. Never a string like "30분".

            WHEN TO RETURN recipe_search:
            - User asks for a recipe by name, by ingredient, or by occasion.
            - User asks to modify the current recipe (더 맵게, 2인분으로, 간장 빼고 등).
            - User asks for an alternative that changes the recipe.

            WHEN TO RETURN chat:
            - Simple cooking Q&A that doesn't need a full recipe (보관법, 팁, 설명 등).
            - Friendly cooking-related conversation.

            DISH SAFETY VALIDATION — applies before returning type "recipe_search":
            Your highest priority is TRUST and DISH CONSISTENCY, not creativity.
            Before returning a recipe, verify the dish name in the user's request is real and recognizable.
            - CONFIDENT (≥99% sure it is a real, known dish) → proceed with type "recipe_search"
            - NOT CONFIDENT / likely typo / ambiguous / unknown → return type "unknown_dish" immediately:
              {
                "type": "unknown_dish",
                "response": "음식 이름이 잘못된 것 같아요. 정확한 이름을 다시 입력해 주시면 바로 도와드릴게요!",
                "hintForUser": "정확한 요리 이름을 다시 입력해 주세요."
              }
            STRICT RULES:
            - NEVER silently auto-correct a misspelling into a different dish.
            - NEVER fabricate a confident recipe for an unknown or incorrect dish name.
            - NEVER map an ambiguous name to a random similar dish.
            - When in doubt → return "unknown_dish". The app shows a safe fallback message instead.
            - Showing the wrong recipe is far worse than returning "unknown_dish".

            RESPONSE FORMAT (always return valid JSON):

            Recipe (new or modified):
            {
              "type": "recipe_search",
              "keyword": "요리명",
              "response": "1~2문장의 친근한 한국어 안내",
              "recipe_data": {
                "title": "요리명",
                "category": "한식/양식/중식/일식/동남아식/디저트/기타 중 하나",
                "ingredients": ["재료1 분량", "재료2 분량", ...],
                "steps": ["1단계 상세 설명", "2단계 상세 설명", ...],
                "stepTimes": [120, 300, 0, 180],
                "difficulty": "쉬움/보통/어려움",
                "time": 35,
                "image_url": ""
              }
            }

            Non-cooking topic or non-recipe content:
            {
              "type": "not_recipe",
              "response": "저는 요리 전문 AI예요! 음식이나 요리 관련해서는 뭐든 도와드릴게요 😊"
            }

            Unknown or unrecognizable dish name (safe-fail):
            {
              "type": "unknown_dish",
              "response": "음식 이름이 잘못된 것 같아요. 정확한 이름을 다시 입력해 주시면 바로 도와드릴게요!",
              "hintForUser": "정확한 요리 이름을 다시 입력해 주세요."
            }

            Cooking Q&A / tips / conversation (no full recipe needed):
            {
              "type": "chat",
              "response": "친근하고 유용한 한국어 요리 조언"
            }
        """.trimIndent()
    }

    fun transcribeAudio(audioFile: File, onResult: (String?) -> Unit) {
        if (!hasApiKey()) {
            android.util.Log.e("OpenAiManager", "transcribeAudio: OPENAI_API_KEY not set")
            onResult(null)
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/m4a".toMediaType()))
            .addFormDataPart("model", OpenAiConfig.STT_MODEL)
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
            override fun onFailure(call: Call, e: IOException) {
                android.util.Log.e("OpenAiManager", "transcribeAudio network failure: ${e.message}", e)
                onResult(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && body != null) {
                        val json = JSONObject(body)
                        onResult(json.optString("text"))
                    } else {
                        android.util.Log.e(
                            "OpenAiManager",
                            "transcribeAudio HTTP ${resp.code} (model=${OpenAiConfig.STT_MODEL}): ${parseApiError(resp, body)}"
                        )
                        onResult(null)
                    }
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
                continuation.resume(result)
            }
        }
    }

    fun processChatRequest(
        userText: String,
        history: List<Pair<String, Boolean>>,
        onResult: (VoiceRequestResult) -> Unit
    ) {
        if (!hasApiKey()) {
            onResult(VoiceRequestResult.Error("OPENAI_API_KEY가 설정되어 있지 않습니다."))
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

        val chosenModel = when {
            isComplexRequest(userText) -> OpenAiConfig.ADVANCED_MODEL
            isIntentRequest(userText) -> OpenAiConfig.INTENT_MODEL
            else -> OpenAiConfig.DEFAULT_TEXT_MODEL
        }

        val requestJson = JSONObject().apply {
            put("model", chosenModel)
            put("messages", messages)
            put("response_format", JSONObject().apply {
                put("type", "json_object")
            })
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(VoiceRequestResult.Error("네트워크 오류: ${e.message ?: "unknown"}"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val responseBody = resp.body?.string()
                    if (!resp.isSuccessful || responseBody == null) {
                        onResult(VoiceRequestResult.Error(parseApiError(resp, responseBody)))
                        return@use
                    }

                    try {
                        val content = JSONObject(responseBody)
                            .getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        val resultJson = JSONObject(content)
                        val type = resultJson.optString("type", "chat")
                        val responseMsg = resultJson.optString("response", "")

                        val normalizedRecipeData = normalizeRecipeData(resultJson.optJSONObject("recipe_data"))

                        onResult(
                            VoiceRequestResult.Success(
                                requestType = type,
                                keyword = resultJson.optString("keyword", ""),
                                responseMessage = responseMsg,
                                recipeData = normalizedRecipeData?.toString()
                            )
                        )
                    } catch (e: Exception) {
                        onResult(VoiceRequestResult.Error("응답 파싱 실패: ${e.message ?: "unknown"}"))
                    }
                }
            }
        })
    }

    fun processVoiceRequest(text: String, onResult: (VoiceRequestResult) -> Unit) {
        processChatRequest(text, emptyList(), onResult)
    }

    suspend fun analyzeRecipeImageSuspend(
        context: Context,
        imageUri: String
    ): VoiceRequestResult = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            if (!hasApiKey()) {
                if (continuation.isActive) {
                    continuation.resume(VoiceRequestResult.Error("OPENAI_API_KEY가 설정되어 있지 않습니다."))
                }
                return@suspendCancellableCoroutine
            }

            try {
                val uri = Uri.parse(imageUri)
                val bitmap = decodeImageForAnalysis(context, uri)
                    ?: throw IllegalStateException("이미지를 불러올 수 없습니다.")

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
                                    put(
                                        "text",
                                        """
                                        Analyze this cooking image and return JSON only.

                                        ABSOLUTE RULE — KOREAN ONLY:
                                        Every single output field MUST be in Korean without exception.
                                        This includes: title, keyword, response, category, ingredients, steps,
                                        difficulty, and time.
                                        If the source image or document is in English, Japanese, Chinese,
                                        or any other language — translate EVERYTHING to Korean.
                                        Transliterate if no Korean equivalent exists (e.g. "파스타", "스테이크").
                                        A response containing ANY non-Korean characters in value fields
                                        (except numbers and units) is considered a failure.

                                        CRITICAL FOOD CHECK — DO THIS FIRST:
                                        Before anything else, ask: "Is there actual food, ingredients, or a recipe visible?"
                                        - Food/ingredients = vegetables, fruits, meat, seafood, grains, cooked dishes, beverages, sauces, spices, packaged food
                                        - NOT food = keyboard, computer, phone, screen, document, text, person, animal, plant (non-edible), furniture, vehicle, clothing, scenery, sky, building, tools, appliances (non-cooking)

                                        RULES:
                                        1. ONLY if the image clearly shows food, edible ingredients, or a cooking recipe/menu → extract/infer the recipe.
                                        2. For ingredient images (raw vegetables, meat, pantry items, fridge contents etc.) → identify what ingredients are visible and suggest a recipe using them.
                                        3. If the image clearly does NOT contain food or edible ingredients (e.g. keyboard, laptop, phone, scenery, people, animals, text documents, electronic devices) → you MUST return:
                                           {
                                             "type": "not_recipe",
                                             "response": "음식이나 식재료 사진이 아닌 것 같아요. 요리나 재료가 잘 보이게 다시 찍어주세요! 😊"
                                           }
                                        4. ABSOLUTE PROHIBITION: Never create a recipe for non-food images. A keyboard is NOT food. A computer is NOT food. Do NOT invent a recipe just because you cannot identify the object.
                                        5. When the image is ambiguous (could be food or not), attempt to identify it and proceed.
                                           Only return "not_recipe" when the image is CLEARLY non-food — not because of low quantity or blur.

                                        DISH IDENTIFICATION RULES (for cooked dishes):
                                        1. Confidence gate: If you cannot identify the specific dish with HIGH confidence (>80%),
                                           use a descriptive generic keyword (e.g. "간장 베이스 고기 조림", "맑은 국물 고기탕")
                                           and STILL return recipe_search — never return not_recipe for a low-confidence dish.
                                           Ask for confirmation in the response field
                                           (e.g. "갈비찜 같아 보여요! 맞나요? 다르다면 말씀해주세요 😊").
                                        2. Korean braised meat dishes — distinguish by visual cues:
                                           - 갈비찜: bone-in short ribs in dark glossy soy sauce, often with carrot/potato/jujube/chestnut
                                           - 찜닭: chicken pieces with glass noodles (당면) visible, dark soy-based sauce
                                           - 장조림: small uniform meat cubes, drier/less saucy, often with quail eggs (메추리알)
                                           - 갈비탕: ribs in CLEAR broth (NOT braised, NOT dark)
                                           - LA갈비: flat cross-cut ribs, grilled appearance, no heavy sauce
                                           - 소불고기: thin sliced beef, lighter sauce, often with onion/scallion
                                        3. Korean soup/stew disambiguation:
                                           - 김치찌개: red, with kimchi visible
                                           - 된장찌개: brown-yellow, with tofu/zucchini, NO kimchi
                                           - 부대찌개: red, with sausage/spam/ramen visible
                                           - 순두부찌개: red, with soft tofu, served in earthenware
                                        4. If two dishes look plausible, pick the more likely one AND invite correction
                                           in the response (e.g. "갈비찜으로 보여요! 혹시 찜닭이라면 말씀해주세요 😊").

                                        INGREDIENT IMAGE RULES (for raw ingredients):
                                        1. Always proceed with whatever ingredients are visible — do NOT refuse due to small quantity.
                                           Even 1 ingredient is enough: suggest a simple recipe featuring it as the main ingredient.
                                        2. Multiple ingredients / fridge contents → list ALL clearly visible ingredients in the
                                           response first (e.g. "양파, 감자, 당근이 보이네요!"), then suggest ONE recipe that uses
                                           the maximum number of them.
                                        3. ALWAYS end the response with an open invitation for follow-up chat, e.g.
                                           "다른 재료도 있으시면 말씀해주세요!", "혹시 더 있는 재료 알려주시면 더 잘 맞춰드릴게요! 😊"
                                        4. Return "not_recipe" ONLY when NO ingredient whatsoever can be identified
                                           (e.g. completely dark image, severe blur with no recognizable object at all).
                                        5. Do NOT invent ingredients that are not clearly visible.

                                        Return exactly this shape for recipes:
                                        {
                                          "type": "recipe_search",
                                          "keyword": "dish name in Korean",
                                          "response": "short Korean message",
                                          "recipe_data": {
                                            "title": "dish name",
                                            "category": "한식/양식/중식/일식/동남아식/디저트/기타 중 하나",
                                            "ingredients": ["ingredient"],
                                            "steps": ["step"],
                                            "stepTimes": [120, 300, 0],
                                            "difficulty": "쉬움/보통/어려움",
                                            "time": 20,
                                            "image_url": "$imageUri"
                                          }
                                        }
                                        TIMER RULES:
                                        - "stepTimes" must have exactly one integer per step, in seconds.
                                        - Put a non-zero value ONLY when that exact step text contains an explicit numeric duration
                                          such as "3분", "30초", or "15~20분".
                                        - For recipe suggestions based on visible raw ingredients or an identified dish photo,
                                          you may choose reasonable durations, but you MUST write those durations visibly
                                          in the matching step text.
                                        - If the image is a recipe text image (cookbook page, menu, recipe card), preserve only
                                          durations that are explicitly present in that text.
                                        - For steps without an explicit numeric duration, put 0 even if the action uses heat,
                                          waiting, boiling, stir-frying, steaming, baking, frying, marinating, or simmering.
                                        - Never infer a default timer from cooking verbs or typical recipe knowledge.
                                        CATEGORY RULES:
                                        - Maratang (마라탕) is CHINESE (중식).
                                        - Tteokbokki (떡볶이) is KOREAN (한식).
                                        - Pizza/Pasta is WESTERN (양식).
                                        - Sushi is JAPANESE (일식).
                                        If the image is a recipe text image (cookbook page, menu, recipe card),
                                        extract the recipe text faithfully — do NOT apply DISH IDENTIFICATION confidence gate
                                        (the dish name comes from the text itself).
                                        """.trimIndent()
                                    )
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put(
                                        "image_url",
                                        JSONObject().apply {
                                            put("url", "data:image/jpeg;base64,$base64Image")
                                            put("detail", "high")
                                        }
                                    )
                                })
                            })
                        })
                    })
                    put("response_format", JSONObject().apply {
                        put("type", "json_object")
                    })
                }

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resume(VoiceRequestResult.Error("네트워크 오류: ${e.message ?: "unknown"}"))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { resp ->
                            val responseBody = resp.body?.string()
                            if (!resp.isSuccessful || responseBody == null) {
                                if (continuation.isActive) {
                                    continuation.resume(VoiceRequestResult.Error(parseApiError(resp, responseBody)))
                                }
                                return@use
                            }

                            try {
                                val content = JSONObject(responseBody)
                                    .getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                                val resultJson = JSONObject(content)
                                val type = resultJson.optString("type", "recipe_search")

                                if (type == "not_recipe") {
                                    if (continuation.isActive) {
                                        continuation.resume(
                                            VoiceRequestResult.Success(
                                                requestType = "not_recipe",
                                                keyword = "",
                                                responseMessage = resultJson.optString(
                                                    "response",
                                                    "음식이나 식재료 사진이 아닌 것 같아요. 요리나 재료가 잘 보이게 다시 찍어주세요! 😊"
                                                ),
                                                recipeData = null
                                            )
                                        )
                                    }
                                    return@use
                                }

                                val recipeJson = normalizeRecipeImageResult(resultJson, imageUri)
                                val keyword = resultJson.optString("keyword")
                                    .ifBlank { recipeJson.optString("title") }
                                    .ifBlank { "이미지 레시피" }

                                if (continuation.isActive) {
                                    continuation.resume(
                                        VoiceRequestResult.Success(
                                            requestType = type,
                                            keyword = keyword,
                                            responseMessage = resultJson.optString(
                                                "response",
                                                "이미지에서 레시피를 분석했어요."
                                            ),
                                            recipeData = recipeJson.toString()
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                if (continuation.isActive) {
                                    continuation.resume(VoiceRequestResult.Error("응답 파싱 실패: ${e.message ?: "unknown"}"))
                                }
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(VoiceRequestResult.Error("이미지 처리 실패: ${e.message ?: "unknown"}"))
                }
            }
        }
    }

    private fun decodeImageForAnalysis(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        // Pass 1: read dimensions only to calculate the down-sample factor
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDimension = 1024
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val rotationDegrees = resolver.openInputStream(uri)?.use { input ->
            try {
                when (ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Exception) { 0f }
        } ?: 0f

        // Pass 3: decode and scale
        val decoded = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: return null

        val scaled = Bitmap.createScaledBitmap(
            decoded,
            if (decoded.width > decoded.height) maxDimension else (decoded.width * maxDimension / decoded.height),
            if (decoded.height > decoded.width) maxDimension else (decoded.height * maxDimension / decoded.width),
            true
        ).also { if (it != decoded) decoded.recycle() }

        return if (rotationDegrees != 0f) {
            val matrix = Matrix().apply { postRotate(rotationDegrees) }
            Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
                .also { scaled.recycle() }
        } else {
            scaled
        }
    }

    private fun normalizeRecipeImageResult(resultJson: JSONObject, imageUri: String): JSONObject {
        val recipeJson = resultJson.optJSONObject("recipe_data") ?: JSONObject().apply {
            put("title", resultJson.optString("title"))
            put("category", resultJson.optString("category"))
            put("ingredients", resultJson.optJSONArray("ingredients") ?: JSONArray())
            put("steps", resultJson.optJSONArray("steps") ?: JSONArray())
            put("difficulty", resultJson.optString("difficulty"))
            put("time", resultJson.optString("time"))
        }

        if (recipeJson.optString("title").isBlank()) {
            recipeJson.put("title", resultJson.optString("keyword", "이미지 레시피"))
        }
        val currentImageUrl = recipeJson.optString("image_url")
        if (currentImageUrl.isBlank() || currentImageUrl == "없음") {
            recipeJson.put("image_url", imageUri)
        }

        return normalizeRecipeData(recipeJson) ?: recipeJson
    }

    private fun normalizeRecipeData(recipeJson: JSONObject?): JSONObject? {
        if (recipeJson == null) return null

        recipeJson.put(
            "category",
            normalizeRecipeCategory(
                rawCategory = recipeJson.optString("category"),
                title = recipeJson.optString("title")
            )
        )
        return recipeJson
    }

    /**
     * Routes to ADVANCED_MODEL when the request is genuinely complex.
     */
    private fun isComplexRequest(text: String): Boolean {
        val lower = text.lowercase()
        val constraintScore = listOf(
            "알레르기", "못 먹", "안 먹", "빼고", "없이", "채식", "비건",
            "글루텐", "유제품", "분 안에", "분 이내", "재료만", "장비",
            "오븐 없이", "냉장고에 있는"
        ).count { lower.contains(it) }

        val reasoningScore = listOf(
            "왜", "실패", "분석", "원인", "이유", "계획", "영양", "칼로리",
            "건강", "다이어트", "대체", "substitut", "improve"
        ).count { lower.contains(it) }

        return constraintScore >= 2 || reasoningScore >= 2 || text.length > 200
    }

    private fun isIntentRequest(text: String): Boolean {
        val lower = text.lowercase()
        val keywords = listOf("다음","이전","시작","정지","멈춰",
            "타이머","재개","처음","끝","완료","보여줘","알려줘")
        return text.length < 30 && keywords.any { lower.contains(it) }
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
