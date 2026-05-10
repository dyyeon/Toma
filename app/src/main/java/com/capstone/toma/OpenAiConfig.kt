package com.capstone.toma

object OpenAiConfig {

    // ── Verified models ────────────────────────────────────────────────────────
    /** Cost-efficient default for chat / intent / recipe analysis. */
    const val DEFAULT_TEXT_MODEL = "gpt-5.4-mini-2026-03-17"

    /** Multimodal model used exclusively for camera-image recipe analysis. */
    const val IMAGE_MODEL = "gpt-5.4-2026-03-17"

    // ── Pending upgrade — swap string once confirmed on OpenAI Platform ────────
    /**
     * Realtime WebSocket voice model.
     * Upgraded from gpt-4o-realtime-preview → gpt-realtime-2 (released May 7, 2026).
     * GPT-5-level reasoning in real-time voice.
     */
    const val REALTIME_MODEL = "gpt-realtime-2"

    /**
     * Speech-to-text transcription (Whisper endpoint).
     * Upgraded from whisper-1 → gpt-realtime-whisper (released May 7, 2026).
     * Real-time streaming STT with improved Korean accuracy.
     */
    const val STT_MODEL = "gpt-realtime-whisper"

    /**
     * Advanced reasoning — activated only for multi-constraint / failure-analysis requests.
     * Upgraded from gpt-5.4 → gpt-5.5 (released April 23, 2026).
     * Use sparingly: isComplexRequest() gate in OpenAiManager limits calls to 3+ constraint requests.
     */
    const val ADVANCED_REASONING_MODEL = "gpt-5.5"
}
