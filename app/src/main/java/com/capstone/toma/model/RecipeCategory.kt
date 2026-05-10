package com.capstone.toma.model

import java.util.Locale

// Hard-coded overrides applied BEFORE keyword heuristics.
// Prevents false positives like "마라탕" matching "탕" in koreanKeywords.
private val titleCorrectionMap = mapOf(
    "마라탕" to "중식", "마라샹궈" to "중식",
    "짜장면" to "중식", "짬뽕" to "중식",
    "탕수육" to "중식", "훠궈" to "중식",
    "초밥" to "일식", "사시미" to "일식",
    "라멘" to "일식", "우동" to "일식",
    "돈카츠" to "일식",
    "피자" to "양식", "파스타" to "양식",
    "스테이크" to "양식", "햄버거" to "양식",
    "팟타이" to "동남아식", "쌀국수" to "동남아식"
)

private val dessertKeywords = listOf(
    "디저트", "dessert", "cake", "케이크", "cookie", "쿠키", "pudding", "푸딩",
    "waffle", "와플", "pancake", "팬케이크", "빙수", "ice cream", "아이스크림",
    "tiramisu", "티라미수", "macaron", "마카롱", "muffin", "머핀", "scone", "스콘",
    "tart", "타르트", "pie", "파이", "brownie", "브라우니"
)

private val japaneseKeywords = listOf(
    "일식", "japanese", "일본", "라멘", "ramen", "우동", "udon", "소바", "soba",
    "초밥", "스시", "sushi", "사시미", "돈카츠", "돈까스", "tonkatsu", "규동",
    "가츠동", "가라아게", "오코노미야키", "타코야키", "나베", "스키야키", "일본식"
)

private val chineseKeywords = listOf(
    "중식", "chinese", "중국", "짜장", "짜장면", "짬뽕", "마파두부", "탕수육",
    "깐풍기", "유산슬", "딤섬", "볶음면", "중화", "중국식", "마라탕", "마라", "양꼬치"
)

private val koreanKeywords = listOf(
    "한식", "korean", "한국", "국", "찌개", "탕", "전골", "비빔밥", "볶음밥",
    "김치", "불고기", "갈비", "잡채", "떡볶이", "김밥", "칼국수", "수제비",
    "된장", "청국장", "순두부", "제육", "나물", "분식", "한식", "무침", "조림"
)

private val westernKeywords = listOf(
    "양식", "western", "브런치", "brunch", "파스타", "pasta", "리조또", "risotto",
    "스테이크", "steak", "샌드위치", "sandwich", "토스트", "toast", "샐러드",
    "salad", "피자", "pizza", "버거", "burger", "햄버거", "오믈렛", "omelette",
    "그라탱", "gratin", "라자냐", "lasagna", "뇨끼", "gnocchi", "수프", "soup"
)

private val southeastKeywords = listOf(
    "동남아", "베트남", "태국", "타이", "팟타이", "쌀국수", "나시고렝", "카오팟",
    "푸팟퐁커리", "반미", "똠양꿍", "분짜", "동남아식"
)

fun normalizeRecipeCategory(
    rawCategory: String?,
    title: String = ""
): String {
    // 0순위: 제목 기반 정정 맵 확인 (가장 높은 우선순위)
    // "마라탕"이 "탕" 키워드로 인해 한식으로 오분류되는 것을 방지합니다.
    titleCorrectionMap.forEach { (keyword, category) ->
        if (title.contains(keyword, ignoreCase = true)) {
            return category
        }
    }

    val source = buildString {
        append(rawCategory.orEmpty())
        append(' ')
        append(title)
    }.trim().lowercase(Locale.ROOT)

    return when {
        source.isBlank() -> "기타"
        // 1순위: 한식 (민감한 분류 문제 해결을 위해 한국어 서비스 기준 한식 우선 순위 부여)
        koreanKeywords.any(source::contains) -> "한식"
        // 2순위: 기타 명확한 카테고리들
        dessertKeywords.any(source::contains) -> "디저트"
        japaneseKeywords.any(source::contains) -> "일식"
        chineseKeywords.any(source::contains) -> "중식"
        westernKeywords.any(source::contains) -> "양식"
        southeastKeywords.any(source::contains) -> "동남아식"
        else -> when (rawCategory?.trim()) {
            "한식", "양식", "중식", "일식", "동남아식", "디저트", "기타" -> rawCategory.trim()
            else -> "기타"
        }
    }
}
