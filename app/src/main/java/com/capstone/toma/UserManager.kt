package com.capstone.toma

import android.content.Context
import java.util.UUID

object UserManager {
    private const val PREF_NAME = "toma_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_IS_ENROLLED = "is_enrolled"   // true = completed 30 recordings + uploaded
    private const val KEY_HAS_UPLOADED = "has_uploaded"
    private const val KEY_HAS_SKIPPED = "has_skipped"   // true = user tapped skip

    private fun getPrefs(context: Context) = 
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getUserId(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_USER_ID, null) ?: run {
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_USER_ID, newId).apply()
            newId
        }
    }

    // Called only after all 30 wavs uploaded successfully
    fun setEnrolled(context: Context, enrolled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_ENROLLED, enrolled).apply()
    }

    fun isEnrolled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_ENROLLED, false)
    }

    // Called when user taps "나중에 하기"
    fun setSkipped(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_HAS_SKIPPED, true).apply()
    }

    fun hasSkipped(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAS_SKIPPED, false)
    }

    fun hasUploaded(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAS_UPLOADED, false)
    }

    fun setHasUploaded(context: Context, uploaded: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HAS_UPLOADED, uploaded).apply()
    }

    // Reset for testing
    fun reset(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
