package com.latchi.iptv.utils

import android.content.Context

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
        val preparedSeriesUrl: String
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
            ),
            preparedLiveUrl = prefs.getString("prepared_live_url_$profileId", "") ?: "",
            preparedBeinUrl = prefs.getString("prepared_bein_url_$profileId", "") ?: "",
            preparedMoviesUrl = prefs.getString("prepared_movies_url_$profileId", "") ?: "",
            preparedSeriesUrl = prefs.getString("prepared_series_url_$profileId", "") ?: ""
        )
    }

    private fun normalizeCsv(value: String): String =
        value.split(',').map { it.trim() }.filter { it.isNotBlank() }.joinToString(",")

    private fun parseCsv(value: String, fallback: List<String>): List<String> {
        val normalized = value.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
        return if (normalized.isNotEmpty()) normalized else fallback
    }
}
