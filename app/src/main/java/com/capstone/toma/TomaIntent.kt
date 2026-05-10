package com.capstone.toma

import android.util.Log
import org.json.JSONObject

/**
 * CHANGED: openWakeWord migration - Robust Intent System
 */
sealed class TomaIntent {
    object NEXT_STEP : TomaIntent()
    object PREVIOUS_STEP : TomaIntent()
    object REPEAT_STEP : TomaIntent()
    data class SET_TIMER(val durationMin: Int) : TomaIntent()
    object RECOMMENDED_TIMER : TomaIntent()  // "추천으로 맞춰줘" — time extracted from step text
    data class RECIPE_SEARCH(val keyword: String) : TomaIntent()
    object INGREDIENT_CHECK : TomaIntent()
    object HELP : TomaIntent()
    object CANCEL : TomaIntent()
    object UNKNOWN : TomaIntent()
}

object TomaIntentParser {
    private const val TAG = "TomaParser"

    fun parse(text: String): TomaIntent {
        try {
            val cleanText = extractJson(text)
            val json = JSONObject(cleanText)
            val intentStr = json.optString("intent", "")
            val args = json.optJSONObject("arguments")
            
            return when (intentStr) {
                "NEXT_STEP" -> TomaIntent.NEXT_STEP
                "PREVIOUS_STEP" -> TomaIntent.PREVIOUS_STEP
                "REPEAT_STEP" -> TomaIntent.REPEAT_STEP
                "SET_TIMER" -> TomaIntent.SET_TIMER(args?.optInt("duration_min") ?: 3)
                "RECOMMENDED_TIMER" -> TomaIntent.RECOMMENDED_TIMER
                "RECIPE_SEARCH" -> TomaIntent.RECIPE_SEARCH(args?.optString("keyword") ?: "")
                "INGREDIENT_CHECK" -> TomaIntent.INGREDIENT_CHECK
                "HELP" -> TomaIntent.HELP
                "CANCEL" -> TomaIntent.CANCEL
                else -> fallbackParse(text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "JSON 파싱 실패, Fallback 시도: ${e.message}")
            return fallbackParse(text)
        }
    }

    private fun extractJson(text: String): String {
        val regex = "(\\{.*\\})".toRegex(RegexOption.DOT_MATCHES_ALL)
        return regex.find(text)?.value ?: text
    }

    private fun fallbackParse(text: String): TomaIntent {
        val lower = text.lowercase().trim()

        // 1. RECOMMENDED_TIMER — must precede generic timer check
        if (lower.contains("추천으로") ||
            (lower.contains("추천") && (lower.contains("타이머") || lower.contains("시간")))) {
            return TomaIntent.RECOMMENDED_TIMER
        }

        // 2. SET_TIMER — "숫자 분" anywhere in the utterance + a timer-action keyword
        //    Catches: "3분 맞춰줘", "어.. 5분 타이머 시작해줘", "10분으로 설정해줘"
        val minuteMatch = "(\\d+)\\s*분".toRegex().find(lower)
        if (minuteMatch != null) {
            val minutes = minuteMatch.groupValues[1].toIntOrNull() ?: 3
            val timerKeywords = listOf("타이머", "맞춰", "설정", "시작", "재줘", "맞추", "알람")
            if (timerKeywords.any { lower.contains(it) }) {
                return TomaIntent.SET_TIMER(minutes)
            }
        }

        // 3. Navigation — filler-tolerant: matches even with hesitation words around them
        if ("다음|넘겨|넘어가|다음\\s*단계|다음으로".toRegex().containsMatchIn(lower)) {
            return TomaIntent.NEXT_STEP
        }
        if ("이전|뒤로|전\\s*단계|이전\\s*단계|돌아가".toRegex().containsMatchIn(lower)) {
            return TomaIntent.PREVIOUS_STEP
        }
        if ("다시|한\\s*번\\s*더|반복|다시\\s*읽어|다시\\s*말해".toRegex().containsMatchIn(lower)) {
            return TomaIntent.REPEAT_STEP
        }

        // 4. SET_TIMER without explicit action word — bare "N분" in cooking context
        if (minuteMatch != null) {
            return TomaIntent.SET_TIMER(minuteMatch.groupValues[1].toIntOrNull() ?: 3)
        }

        // 5. Remaining keywords
        if (lower.contains("재료")) return TomaIntent.INGREDIENT_CHECK
        if (lower.contains("취소") || lower.contains("그만")) return TomaIntent.CANCEL

        return TomaIntent.UNKNOWN
    }
}
