package com.latchi.iptv.utils

import android.content.Context

object RemoteViewConfigPrefs {
    private const val PREFS = "server_sync_prefs"

    data class FilterConfig(
        val hiddenCategories: String,
        val beinKeywords: List<String>,
        val beinMaxKeywords: List<String>,
        val alwanKeywords: List<String>
    )

    fun saveFromValidationResult(context: Context, profileId: String, result: ActivationValidationResult) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("hidden_categories_$profileId", result.hiddenCategories)
            .putString("bein_keywords_$profileId", normalizeCsv(result.beinKeywords))
            .putString("bein_max_keywords_$profileId", normalizeCsv(result.beinMaxKeywords))
            .putString("alwan_keywords_$profileId", normalizeCsv(result.alwanKeywords))
            .apply()
    }

    fun getFilterConfig(context: Context, profileId: String): FilterConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
            )
        )
    }

    private fun normalizeCsv(value: String): String =
        value.split(',').map { it.trim() }.filter { it.isNotBlank() }.joinToString(",")

    private fun parseCsv(value: String, fallback: List<String>): List<String> {
        val normalized = value.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
        return if (normalized.isNotEmpty()) normalized else fallback
    }
}
