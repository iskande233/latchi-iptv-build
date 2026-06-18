package com.latchi.iptv.utils

import android.content.Context
import com.latchi.iptv.model.Channel
import org.json.JSONArray
import org.json.JSONObject

/**
 * 👑 FavoriteManager — أداة الإدارة الملكية للمفضلة والمشاهدة مؤخراً
 *
 * تتيح للمشاهد:
 * 1. حفظ وجلب وإزالة القنوات المفضلة (Favorite Channels).
 * 2. حفظ وجلب وإزالة الفئات المفضلة (Favorite Categories).
 * 3. حفظ وجلب القنوات المشاهدة مؤخراً (Recently Watched Channels).
 *
 * كل البيانات محفوظة محلياً بأمان ومفصولة حسب البروفايل النشط.
 */
object FavoriteManager {
    private const val PREFS_NAME = "latchi_royal_favorites_v2"
    private const val KEY_FAV_CHANNELS = "fav_channels_"
    private const val KEY_FAV_CATEGORIES = "fav_categories_"
    private const val KEY_RECENT_CHANNELS = "recent_channels_"

    private fun getPrefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─────────────────────────────────────────────────────────────
    // 1. القنوات المفضلة (Favorite Channels)
    // ─────────────────────────────────────────────────────────────
    fun getFavoriteChannels(ctx: Context, profileId: String): List<Channel> {
        val prefs = getPrefs(ctx)
        val jsonStr = prefs.getString(KEY_FAV_CHANNELS + profileId, "[]") ?: "[]"
        val list = mutableListOf<Channel>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Channel.fromJson(obj))
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * يضيف أو يزيل القناة من المفضلة (Toggle).
     * يُرجع true إذا تمت إضافتها، و false إذا تمت إزالتها.
     */
    fun toggleFavoriteChannel(ctx: Context, profileId: String, channel: Channel): Boolean {
        val current = getFavoriteChannels(ctx, profileId).toMutableList()
        val index = current.indexOfFirst { it.streamUrl == channel.streamUrl }
        val isAdded: Boolean

        if (index >= 0) {
            current.removeAt(index)
            isAdded = false
        } else {
            current.add(0, channel) // أحدث إضافة تكون في الصدارة
            isAdded = true
        }

        saveChannelsList(ctx, KEY_FAV_CHANNELS + profileId, current)
        return isAdded
    }

    fun isFavoriteChannel(ctx: Context, profileId: String, streamUrl: String): Boolean {
        val current = getFavoriteChannels(ctx, profileId)
        return current.any { it.streamUrl == streamUrl }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. الفئات المفضلة (Favorite Categories)
    // ─────────────────────────────────────────────────────────────
    fun getFavoriteCategories(ctx: Context, profileId: String): List<String> {
        val prefs = getPrefs(ctx)
        val jsonStr = prefs.getString(KEY_FAV_CATEGORIES + profileId, "[]") ?: "[]"
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        } catch (_: Exception) {}
        return list
    }

    fun toggleFavoriteCategory(ctx: Context, profileId: String, categoryName: String): Boolean {
        val cleanCat = categoryName.trim()
        if (cleanCat.isBlank()) return false
        val current = getFavoriteCategories(ctx, profileId).toMutableList()
        val index = current.indexOfFirst { it.equals(cleanCat, ignoreCase = true) }
        val isAdded: Boolean

        if (index >= 0) {
            current.removeAt(index)
            isAdded = false
        } else {
            current.add(0, cleanCat)
            isAdded = true
        }

        saveCategoriesList(ctx, KEY_FAV_CATEGORIES + profileId, current)
        return isAdded
    }

    fun isFavoriteCategory(ctx: Context, profileId: String, categoryName: String): Boolean {
        val current = getFavoriteCategories(ctx, profileId)
        return current.any { it.equals(categoryName.trim(), ignoreCase = true) }
    }

    // ─────────────────────────────────────────────────────────────
    // 3. القنوات المشاهدة مؤخراً (Recently Watched Channels)
    // ─────────────────────────────────────────────────────────────
    fun getRecentChannels(ctx: Context, profileId: String): List<Channel> {
        val prefs = getPrefs(ctx)
        val jsonStr = prefs.getString(KEY_RECENT_CHANNELS + profileId, "[]") ?: "[]"
        val list = mutableListOf<Channel>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Channel.fromJson(obj))
            }
        } catch (_: Exception) {}
        return list
    }

    fun addRecentChannel(ctx: Context, profileId: String, channel: Channel) {
        val current = getRecentChannels(ctx, profileId).toMutableList()
        // إزالة القديم إذا كان موجوداً
        current.removeAll { it.streamUrl == channel.streamUrl }
        // وضعه في الصدارة
        current.add(0, channel)
        // الحفاظ على أحدث 50 قناة فقط لعدم إثقال الذاكرة
        val trimmed = current.take(50)

        saveChannelsList(ctx, KEY_RECENT_CHANNELS + profileId, trimmed)
        LastWatchedPrefs.save(ctx, profileId, channel) // مزامنة مع الأداة القديمة للأمان
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────
    private fun saveChannelsList(ctx: Context, key: String, list: List<Channel>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        getPrefs(ctx).edit().putString(key, arr.toString()).apply()
    }

    private fun saveCategoriesList(ctx: Context, key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        getPrefs(ctx).edit().putString(key, arr.toString()).apply()
    }
}
