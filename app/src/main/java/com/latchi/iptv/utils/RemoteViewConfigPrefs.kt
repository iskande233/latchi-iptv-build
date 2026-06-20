package com.latchi.iptv.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object RemoteViewConfigPrefs {
    private const val PREFS = "server_sync_prefs"

    data class FilterConfig(
        val hiddenCategories: String,
        val beinKeywords: List<String>,
        val beinMaxKeywords: List<String>,
        val alwanKeywords: List<String>,
        val preparedLiveUrl: String,
        val preparedBeinUrl: String,
        val preparedMoviesUrl: String,
        val preparedSeriesUrl: String,
        // 🛡️ v5.2: Category Organizer overrides
        val customNames: Map<String, String>,
        val customOrder: List<String>,
        val hasCategoryOverrides: Boolean
    )

    fun saveFromValidationResult(context: Context, profileId: String, result: ActivationValidationResult) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("hidden_categories_$profileId", result.hiddenCategories)
            .putString("bein_keywords_$profileId", normalizeCsv(result.beinKeywords))
            .putString("bein_max_keywords_$profileId", normalizeCsv(result.beinMaxKeywords))
            .putString("alwan_keywords_$profileId", normalizeCsv(result.alwanKeywords))
            .putString("prepared_live_url_$profileId", result.preparedLiveUrl.trim())
            .putString("prepared_bein_url_$profileId", result.preparedBeinUrl.trim())
            .putString("prepared_movies_url_$profileId", result.preparedMoviesUrl.trim())
            .putString("prepared_series_url_$profileId", result.preparedSeriesUrl.trim())
            .putString("custom_names_$profileId", result.customNamesJson.trim())
            .putString("custom_order_$profileId", result.customOrderJson.trim())
            .apply()
    }

    fun getFilterConfig(context: Context, profileId: String): FilterConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val customNamesJson = prefs.getString("custom_names_$profileId", "") ?: ""
        val customOrderJson = prefs.getString("custom_order_$profileId", "") ?: ""
        val customNames = parseCustomNamesJson(customNamesJson)
        val customOrder = parseCustomOrderJson(customOrderJson)
        return FilterConfig(
            hiddenCategories = prefs.getString("hidden_categories_$profileId", "") ?: "",
            beinKeywords = parseCsv(
                prefs.getString("bein_keywords_$profileId", "") ?: "",
                listOf("bein", "be in", "beinsport", "bein sport", "bein sports", "بي ان", "بي إن")
            ),
            beinMaxKeywords = parseCsv(
                prefs.getString("bein_max_keywords_$profileId", "") ?: "",
                listOf("max", "max 1", "max1", "max 2", "max2", "bein max")
            ),
            alwanKeywords = parseCsv(
                prefs.getString("alwan_keywords_$profileId", "") ?: "",
                listOf("alwan", "alwan sport", "alwan sports", "الوان")
            ),
            preparedLiveUrl = prefs.getString("prepared_live_url_$profileId", "") ?: "",
            preparedBeinUrl = prefs.getString("prepared_bein_url_$profileId", "") ?: "",
            preparedMoviesUrl = prefs.getString("prepared_movies_url_$profileId", "") ?: "",
            preparedSeriesUrl = prefs.getString("prepared_series_url_$profileId", "") ?: "",
            customNames = customNames,
            customOrder = customOrder,
            hasCategoryOverrides = customNames.isNotEmpty() || customOrder.isNotEmpty()
        )
    }

    /**
     * يُرجع الاسم المخصص إذا موجود، وإلا الاسم الأصلي.
     */
    fun getDisplayName(config: FilterConfig, originalName: String): String {
        return config.customNames[originalName] ?: originalName
    }

    /**
     * يُرجع قائمة الفئات مرتبة (مخصصة أو أصلية).
     */
    fun getOrderedCategories(config: FilterConfig, originalCategories: List<String>): List<String> {
        if (!config.hasCategoryOverrides || config.customOrder.isEmpty()) {
            return originalCategories.sortedBy { getDisplayName(config, it).lowercase() }
        }
        val nameMap = config.customNames
        val ordered = mutableListOf<String>()
        for (displayName in config.customOrder) {
            val original = nameMap.entries.firstOrNull { it.value == displayName }?.key ?: displayName
            if (originalCategories.contains(original)) {
                ordered.add(displayName)
            }
        }
        for (cat in originalCategories) {
            val displayName = nameMap[cat] ?: cat
            if (!ordered.contains(displayName)) {
                ordered.add(displayName)
            }
        }
        return ordered
    }

    private fun parseCustomNamesJson(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val out = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                val value = obj.optString(key, "")
                if (value.isNotBlank() && value != key) {
                    out[key] = value
                }
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun parseCustomOrderJson(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val name = arr.optString(i, "")
                if (name.isNotBlank()) out.add(name)
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun normalizeCsv(value: String): String =
        value.split(',').map { it.trim() }.filter { it.isNotBlank() }.joinToString(",")

    private fun parseCsv(value: String, fallback: List<String>): List<String> {
        val normalized = value.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
        return if (normalized.isNotEmpty()) normalized else fallback
    }
}
