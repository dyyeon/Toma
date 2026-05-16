package com.capstone.toma

object OpenAiConfig {

    // ── Verified models ────────────────────────────────────────────────────────
    /** Cost-efficient default for chat / recipe analysis. */
    const val DEFAULT_TEXT_MODEL = "gpt-5.4-mini-2026-03-17"

    /** Lightweight model for short voice intent parsing only. */
    const val INTENT_MODEL = "gpt-5.4-nano"

    /** Multimodal model for camera-image recipe analysis. */
    const val IMAGE_MODEL = "gpt-5.5"

    // ── Model names for Manager ────────────────────────────────────────────────
    /** Advanced reasoning — Called by OpenAiManager */
    const val ADVANCED_MODEL = "gpt-5.5"
    const val ADVANCED_REASONING_MODEL = "gpt-5.5"

    // ── Realtime & STT ────────────────────────────────────────────────────────
    /** Realtime WebSocket voice model. */
    const val REALTIME_MODEL = "gpt-realtime-2"

    /** Speech-to-text transcription (Whisper endpoint). */
    const val STT_MODEL = "gpt-realtime-whisper"
}