package com.capstone.toma.storage

import android.content.Context
import com.capstone.toma.model.RecipeSourceType
import com.capstone.toma.ui.screen.RecentRecipeItem
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentHistoryStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getRecentItems(): List<RecentRecipeItem> {
        return loadEntries().map(::toRecentRecipeItem)
    }

    fun saveRecentRecipe(
        keyword: String,
        recipeDataJson: String?,
        sourceType: RecipeSourceType = RecipeSourceType.TEXT
    ): List<RecentRecipeItem> {
        val title = extractTitle(recipeDataJson).ifBlank { keyword.trim() }
        if (title.isBlank()) {
            return getRecentItems()
        }

        // Automatic Source Type mapping
        val finalSourceType = if (keyword.startsWith("http")) {
            if (keyword.contains("youtube.com") || keyword.contains("youtu.be")) {
                RecipeSourceType.YOUTUBE
            } else {
                RecipeSourceType.WEB
            }
        } else if (sourceType == RecipeSourceType.IMAGE) {
            RecipeSourceType.IMAGE
        } else {
            RecipeSourceType.TEXT
        }

        val savedAt = System.currentTimeMillis()
        val entry = RecentHistoryEntry(
            id = buildRecordId(title),
            title = title,
            sourceType = finalSourceType,
            recipeDataJson = recipeDataJson?.takeIf { it.isNotBlank() },
            savedAt = savedAt
        )

        val updatedEntries = buildList {
            add(entry)
            addAll(
                loadEntries().filterNot { existing ->
                    existing.id == entry.id
                }
            )
        }.take(MAX_ITEMS)

        persistEntries(updatedEntries)
        return updatedEntries.map(::toRecentRecipeItem)
    }

    private fun loadEntries(): List<RecentHistoryEntry> {
        val storedJson = preferences.getString(KEY_RECENT_HISTORY, null).orEmpty()
        if (storedJson.isBlank()) {
            return emptyList()
        }

        return runCatching {
            val jsonArray = JSONArray(storedJson)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    jsonArray.optJSONObject(index)?.toEntry()?.let(::add)
                }
            }.sortedByDescending { it.savedAt }
        }.getOrElse {
            emptyList()
        }
    }

    private fun persistEntries(entries: List<RecentHistoryEntry>) {
        val jsonArray = JSONArray()
        entries.forEach { entry ->
            jsonArray.put(entry.toJson())
        }
        preferences.edit()
            .putString(KEY_RECENT_HISTORY, jsonArray.toString())
            .apply()
    }

    private fun toRecentRecipeItem(entry: RecentHistoryEntry): RecentRecipeItem {
        return RecentRecipeItem(
            id = entry.id,
            title = entry.title,
            timeText = formatTimeText(entry.savedAt),
            sourceType = entry.sourceType,
            recipeDataJson = entry.recipeDataJson
        )
    }

    private fun extractTitle(recipeDataJson: String?): String {
        if (recipeDataJson.isNullOrBlank()) {
            return ""
        }

        return runCatching {
            JSONObject(recipeDataJson).optString("title").trim()
        }.getOrDefault("")
    }

    private fun buildRecordId(title: String): String {
        return title.trim().lowercase(Locale.ROOT).hashCode().toUInt().toString()
    }

    private fun formatTimeText(savedAt: Long): String {
        val elapsedMillis = (System.currentTimeMillis() - savedAt).coerceAtLeast(0L)
        val minuteMillis = 60_000L
        val hourMillis = 60 * minuteMillis
        val dayMillis = 24 * hourMillis

        return when {
            elapsedMillis < minuteMillis -> "\uBC29\uAE08 \uBD84\uC11D"
            elapsedMillis < hourMillis -> "${elapsedMillis / minuteMillis}\uBD84 \uC804 \uBD84\uC11D"
            elapsedMillis < dayMillis -> "${elapsedMillis / hourMillis}\uC2DC\uAC04 \uC804 \uBD84\uC11D"
            elapsedMillis < 7 * dayMillis -> "${elapsedMillis / dayMillis}\uC77C \uC804 \uBD84\uC11D"
            else -> {
                val formatter = SimpleDateFormat("M/d \uBD84\uC11D", Locale.KOREAN)
                formatter.format(Date(savedAt))
            }
        }
    }

    private fun JSONObject.toEntry(): RecentHistoryEntry {
        return RecentHistoryEntry(
            id = optString("id"),
            title = optString("title"),
            sourceType = RecipeSourceType.valueOf(
                optString("sourceType", RecipeSourceType.TEXT.name)
            ),
            recipeDataJson = optString("recipeDataJson").takeIf { it.isNotBlank() },
            savedAt = optLong("savedAt", 0L)
        )
    }

    private fun RecentHistoryEntry.toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("sourceType", sourceType.name)
            put("recipeDataJson", recipeDataJson ?: "")
            put("savedAt", savedAt)
        }
    }

    private data class RecentHistoryEntry(
        val id: String,
        val title: String,
        val sourceType: RecipeSourceType,
        val recipeDataJson: String?,
        val savedAt: Long
    )

    companion object {
        private const val PREFS_NAME = "recent_history_store"
        private const val KEY_RECENT_HISTORY = "recent_history_items"
        private const val MAX_ITEMS = 5
    }
}
