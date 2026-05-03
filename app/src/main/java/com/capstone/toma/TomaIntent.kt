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
    data class RECIPE_SEARCH(val keyword: String) : TomaIntent() // 추가됨
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
        val lowerText = text.lowercase()
        return when {
            lowerText.contains("다음") || lowerText.contains("넘어가") -> TomaIntent.NEXT_STEP
            lowerText.contains("이전") || lowerText.contains("뒤로") -> TomaIntent.PREVIOUS_STEP
            lowerText.contains("다시") || lowerText.contains("한 번 더") -> TomaIntent.REPEAT_STEP
            lowerText.contains("타이머") -> {
                val digits = "\\d+".toRegex().find(text)?.value?.toInt() ?: 3
                TomaIntent.SET_TIMER(digits)
            }
            lowerText.contains("재료") -> TomaIntent.INGREDIENT_CHECK
            lowerText.contains("취소") || lowerText.contains("그만") -> TomaIntent.CANCEL
            else -> TomaIntent.UNKNOWN
        }
    }
}
