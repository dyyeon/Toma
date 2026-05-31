package com.capstone.toma

/**
 * 한국어 요리명 오타 교정 + 이미지-레시피 일관성 검증 유틸리티.
 *
 * 수정 방향:
 * - 사용자 텍스트에 포함된 알려진 오타를 GPT 전송 전에 교정
 * - 이미지 검색에 쓸 정규 키워드를 결정
 * - 검색된 이미지가 레시피 제목과 충분히 일치하는지 확인
 */
object DishNameHelper {

    /**
     * 오타 → 정규 요리명 교정 맵.
     * 오인식 위험이 낮은 명확한 케이스만 포함.
     * 접미사 포함 표기(예: "팔보재볶음")는 별도 항목으로 처리.
     */
    private val typoMap: Map<String, String> = mapOf(
        // ─── 팔보채 ───────────────────────────────────────
        "팔보재" to "팔보채",
        "팔보재볶음" to "팔보채",

        // ─── 잡채 ────────────────────────────────────────
        "잡재" to "잡채",
        "잡체" to "잡채",
        "잡차" to "잡채",

        // ─── 볶음밥 ──────────────────────────────────────
        "복음밥" to "볶음밥",
        "볶은밥" to "볶음밥",

        // ─── 제육볶음 ────────────────────────────────────
        "재육볶음" to "제육볶음",
        "제육볶움" to "제육볶음",
        "재육복음" to "제육볶음",
        "제육복음" to "제육볶음",

        // ─── 낙지볶음 ────────────────────────────────────
        "낙지복음" to "낙지볶음",
        "낚지볶음" to "낙지볶음",

        // ─── 오징어볶음 ──────────────────────────────────
        "오징어복음" to "오징어볶음",

        // ─── 순대볶음 ────────────────────────────────────
        "순대복음" to "순대볶음",

        // ─── 된장찌개 ────────────────────────────────────
        "된장찌게" to "된장찌개",
        "대장찌개" to "된장찌개",
        "된짱찌개" to "된장찌개",

        // ─── 김치찌개 ────────────────────────────────────
        "김치찌게" to "김치찌개",

        // ─── 순두부찌개 ──────────────────────────────────
        "순두부찌게" to "순두부찌개",
        "순두부찌게" to "순두부찌개",

        // ─── 부대찌개 ────────────────────────────────────
        "부대찌게" to "부대찌개",

        // ─── 떡볶이 ──────────────────────────────────────
        "떡복이" to "떡볶이",
        "떡볶기" to "떡볶이",
        "떡뽁이" to "떡볶이",
        "떡뽂이" to "떡볶이",

        // ─── 비빔밥 ──────────────────────────────────────
        "비빔법" to "비빔밥",

        // ─── 삼겹살 ──────────────────────────────────────
        "삼겹사" to "삼겹살",
        "삼겹쌀" to "삼겹살",

        // ─── 닭갈비 ──────────────────────────────────────
        "닥갈비" to "닭갈비",

        // ─── 깍두기 ──────────────────────────────────────
        "깍두지" to "깍두기",
        "깍뚜기" to "깍두기",

        // ─── 간장게장 ────────────────────────────────────
        "간장계장" to "간장게장",
        "간장게앙" to "간장게장",

        // ─── 닭볶음탕 ────────────────────────────────────
        "닭볶음당" to "닭볶음탕",
        "닭복음탕" to "닭볶음탕",

        // ─── 마라탕 ──────────────────────────────────────
        "마라당" to "마라탕",

        // ─── 잔치국수 ────────────────────────────────────
        "잔치국시" to "잔치국수",

        // ─── 갈비찜 ──────────────────────────────────────
        "갈비침" to "갈비찜",
        "갈비짐" to "갈비찜",
    )

    /**
     * [text] 안에 있는 알려진 오타를 정규 표기로 교정한다.
     * 더 긴 표현을 먼저 처리해 부분 치환 충돌을 방지한다.
     */
    fun correctDishNameInText(text: String): String {
        if (text.isBlank()) return text
        var result = text
        typoMap.entries
            .sortedByDescending { it.key.length }
            .forEach { (typo, canonical) ->
                if (result.contains(typo)) {
                    result = result.replace(typo, canonical)
                }
            }
        return result
    }

    /**
     * 이미지 검색에 사용할 정규 키워드를 결정한다.
     *
     * - recipeTitle (GPT recipe_data.title)이 비어 있지 않으면 우선 사용.
     *   title은 keyword보다 더 신중하게 생성되므로 신뢰도가 높다.
     * - 두 값 모두 오타 교정을 거친 뒤 반환한다.
     */
    fun canonicalSearchKeyword(gptKeyword: String, recipeTitle: String): String {
        val correctedTitle = correctDishNameInText(recipeTitle.trim())
        val correctedKeyword = correctDishNameInText(gptKeyword.trim())
        return when {
            correctedTitle.isNotBlank() -> correctedTitle
            correctedKeyword.isNotBlank() -> correctedKeyword
            else -> gptKeyword.trim()
        }
    }

    /**
     * 이미지 검색 키워드와 레시피 제목이 충분히 일치하는지 확인한다.
     *
     * 일치 조건(OR):
     *  - 동일 문자열
     *  - 한쪽이 다른 쪽을 포함
     *  - 레벤슈타인 유사도 ≥ 0.6
     *
     * false 반환 시 이미지를 사용하지 않는다 (잘못된 이미지 방지).
     */
    fun isImageConsistentWithRecipe(imageSearchKeyword: String, recipeTitle: String): Boolean {
        if (imageSearchKeyword.isBlank() || recipeTitle.isBlank()) return false
        val k = imageSearchKeyword.trim()
        val t = recipeTitle.trim()
        if (k == t) return true
        if (k.contains(t) || t.contains(k)) return true
        return levenshteinSimilarity(k, t) >= 0.6
    }

    private fun levenshteinSimilarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length).toDouble()
        if (maxLen == 0.0) return 1.0
        return 1.0 - levenshteinDistance(a, b) / maxLen
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[m][n]
    }
}
