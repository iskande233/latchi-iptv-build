package com.latchi.iptv.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object DailyWallpaperManager {
    private const val TAG = "DailyWallpaperManager"
    private const val PREFS = "latchi_daily_wallpaper_prefs"
    private const val KEY_LAST_SYNC_DAY = "last_sync_day_number"
    private const val KEY_ACTIVE_WALLPAPER = "active_wallpaper_path"

    // Remote dynamic JSON endpoint or high-end fallback URLs
    private const val REMOTE_WALLPAPERS_JSON = "https://raw.githubusercontent.com/iskande233/latchi-iptv-build/main/wallpapers.json"

    private val fallbackPool = listOf(
        // Royal Deep Space
        "https://images.unsplash.com/photo-1506703719100-a0f3a48c0f86?q=80&w=1920&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1451187580459-43490279c0fa?q=80&w=1920&auto=format&fit=crop",
        // Midnight Cyberpunk
        "https://images.unsplash.com/photo-1518709268805-4e9042af9f23?q=80&w=1920&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1509198397868-475647b2a1e5?q=80&w=1920&auto=format&fit=crop",
        // Emerald Aurora
        "https://images.unsplash.com/photo-1531366936337-7c912a4589a7?q=80&w=1920&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1579033461387-adb47c3eb94c?q=80&w=1920&auto=format&fit=crop",
        // Luxury Golden
        "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?q=80&w=1920&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1635070041078-e363dbe005cb?q=80&w=1920&auto=format&fit=crop",
        // Glowing Arabesque
        "https://images.unsplash.com/photo-1579783900882-c0d3dad7b119?q=80&w=1920&auto=format&fit=crop",
        "https://images.unsplash.com/photo-1582561228098-0f006b5d385b?q=80&w=1920&auto=format&fit=crop"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())

    fun getWallpaperDirectory(context: Context): File {
        return File(context.filesDir, "latchi_daily_wallpapers").apply { mkdirs() }
    }

    /**
     * Actively syncs exactly 5 daily wallpapers.
     * Deletes any leftovers to consume zero unnecessary space.
     */
    fun syncDailyWallpapers(context: Context, force: Boolean = false, onComplete: (List<File>) -> Unit) {
        val appContext = context.applicationContext
        thread(name = "LatchiWallpaperSync") {
            try {
                val dir = getWallpaperDirectory(appContext)
                val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                
                val todayDay = (System.currentTimeMillis() / (1000 * 60 * 60 * 24)).toLong()
                val lastDay = prefs.getLong(KEY_LAST_SYNC_DAY, 0L)

                val existingFiles = dir.listFiles()?.filter { it.extension.equals("jpg", true) || it.extension.equals("png", true) } ?: emptyList()

                // If already synced today and we have exactly 5 files, return them immediately
                if (!force && lastDay == todayDay && existingFiles.size == 5) {
                    Log.d(TAG, "Already synced today. Returning 5 active wallpapers.")
                    onMain { onComplete(existingFiles.sortedBy { it.name }) }
                    return@thread
                }

                Log.d(TAG, "Starting new daily wallpaper sync...")
                val dayOffset = (todayDay % 5).toInt()
                val targetUrls = mutableListOf<String>()

                // Try fetching remote live JSON first
                try {
                    val req = Request.Builder().url(REMOTE_WALLPAPERS_JSON).get().build()
                    client.newCall(req).execute().use { res ->
                        if (res.isSuccessful) {
                            res.body?.string()?.let { txt ->
                                val arr = JSONObject(txt).optJSONArray("wallpapers") ?: JSONArray(txt)
                                for (i in 0 until arr.length()) {
                                    val u = arr.optString(i, "")
                                    if (u.isNotBlank()) targetUrls.add(u)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

                if (targetUrls.isEmpty()) {
                    targetUrls.addAll(fallbackPool)
                }

                // Pick exactly 5 urls based on today's rotation
                val pickedUrls = (0 until 5).map { i ->
                    targetUrls[(dayOffset + i) % targetUrls.size]
                }

                val newFiles = mutableListOf<File>()

                pickedUrls.forEachIndexed { index, url ->
                    try {
                        val fileName = "wallpaper_day_${todayDay}_item_$index.jpg"
                        val file = File(dir, fileName)
                        if (!file.exists()) {
                            val req = Request.Builder().url(url).get().build()
                            client.newCall(req).execute().use { res ->
                                if (res.isSuccessful) {
                                    res.body?.byteStream()?.use { input ->
                                        file.outputStream().use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                }
                            }
                        }
                        if (file.exists() && file.length() > 1024L) {
                            newFiles.add(file)
                        }
                    } catch (_: Exception) {}
                }

                // ⚡ ZERO SPACE LEFTOVER RULE: Scan folder and delete any old wallpapers from yesterday
                dir.listFiles()?.forEach { f ->
                    if (!newFiles.contains(f)) {
                        f.delete()
                    }
                }

                prefs.edit().putLong(KEY_LAST_SYNC_DAY, todayDay).apply()
                if (newFiles.isNotEmpty() && getActiveWallpaper(appContext).isBlank()) {
                    setActiveWallpaper(appContext, newFiles.first().absolutePath)
                }

                onMain { onComplete(newFiles.sortedBy { it.name }) }
            } catch (e: Exception) {
                Log.e(TAG, "Wallpaper Sync Error: ${e.message}")
                onMain { onComplete(emptyList()) }
            }
        }
    }

    fun getActiveWallpaper(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_WALLPAPER, "") ?: ""
    }

    fun setActiveWallpaper(context: Context, path: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_WALLPAPER, path)
            .apply()
    }

    fun loadActiveWallpaperDrawable(context: Context): Drawable? {
        val path = getActiveWallpaper(context)
        if (path.isBlank()) return null
        return try {
            val file = File(path)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                BitmapDrawable(context.resources, bitmap)
            } else null
        } catch (_: Exception) { null }
    }

    private fun onMain(block: () -> Unit) {
        handler.post(block)
    }
}
