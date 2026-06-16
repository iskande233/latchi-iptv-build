package com.latchi.iptv.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread
import com.latchi.iptv.utils.FavoritesPrefs

data class ServerSyncResult(
    val changed: Boolean,
    val message: String = "",
    val oldUrl: String = "",
    val newUrl: String = "",
    val profileId: String = "",
    val serverRevision: Long = 0L
)

object ServerSyncManager {
    private const val PREFS = "server_sync_prefs"
    private const val KEY_LAST_SYNC_AT = "last_sync_at"
    private const val KEY_LAST_STATUS = "last_status"
    private const val MIN_SYNC_INTERVAL_MS = 60_000L

    @Volatile
    private var running = false

    fun checkForServerUpdate(
        context: Context,
        force: Boolean = false,
        onResult: (ServerSyncResult) -> Unit
    ) {
        val appContext = context.applicationContext
        val active = SourcePrefs.getActiveProfile(appContext)
        if (active == null || active.activationCode == "MANUAL") {
            onMain { onResult(ServerSyncResult(false, "no_active_code")) }
            return
        }

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        if (!force && now - last < MIN_SYNC_INTERVAL_MS) {
            onMain { onResult(ServerSyncResult(false, "recently_checked", profileId = active.id)) }
            return
        }
        if (running) {
            onMain { onResult(ServerSyncResult(false, "already_running", profileId = active.id)) }
            return
        }

        running = true
        thread(name = "LatchiServerSync") {
            try {
                prefs.edit().putLong(KEY_LAST_SYNC_AT, now).apply()
                val result = ActivationValidator.validate(appContext, active)
                if (!result.success) {
                    onMain { onResult(ServerSyncResult(false, result.message, profileId = active.id)) }
                    return@thread
                }

                val oldUrl = normalizeUrl(active.m3uUrl)
                val newUrl = normalizeUrl(result.playlistUrl.ifBlank { active.m3uUrl })
                val oldRevision = active.serverRevision
                val newRevision = result.serverRevision
                val updatedName = result.name.ifBlank { active.name }
                val updatedExpiry = result.expiresAt.ifBlank { active.expiresAt }
                val updatedMax = result.maxDevices.takeIf { it > 0 } ?: active.maxDevices

                val urlChanged = newUrl.isNotBlank() && newUrl != oldUrl
                val revisionChanged = newRevision > oldRevision

                if (urlChanged || revisionChanged) {
                    if (newUrl.isNotBlank()) {
                        val health = ServerHealthChecker.check(newUrl)
                        prefs.edit().putString(KEY_LAST_STATUS, if (health.online) "online:${health.responseMs}" else "offline:${health.message}").apply()
                        if (!health.online) {
                            onMain { onResult(ServerSyncResult(false, "server_offline:${health.message}", profileId = active.id)) }
                            return@thread
                        }
                    }
                    SourcePrefs.saveActivatedProfile(
                        context = appContext,
                        code = active.activationCode,
                        name = updatedName,
                        playlistUrl = newUrl,
                        expiresAt = updatedExpiry,
                        maxDevices = updatedMax,
                        serverRevision = newRevision
                    )
                    ChannelCache.clear(appContext, active.id)
                    FavoritesPrefs.clearProfile(appContext, active.id)
                    prefs.edit()
                        .putString("last_applied_url_${active.id}", newUrl)
                        .putLong("last_applied_revision_${active.id}", newRevision)
                        .putLong("last_changed_at_${active.id}", System.currentTimeMillis())
                        .apply()
                    onMain {
                        onResult(
                            ServerSyncResult(
                                changed = true,
                                message = if (revisionChanged) "revision_changed" else "server_changed",
                                oldUrl = oldUrl,
                                newUrl = newUrl,
                                profileId = active.id,
                                serverRevision = newRevision
                            )
                        )
                    }
                } else {
                    // Keep account metadata fresh without clearing channel cache.
                    if (updatedName != active.name || updatedExpiry != active.expiresAt || updatedMax != active.maxDevices || newRevision != active.serverRevision) {
                        SourcePrefs.saveActivatedProfile(
                            context = appContext,
                            code = active.activationCode,
                            name = updatedName,
                            playlistUrl = active.m3uUrl,
                            expiresAt = updatedExpiry,
                            maxDevices = updatedMax,
                            serverRevision = newRevision
                        )
                    }
                    onMain { onResult(ServerSyncResult(false, "no_change", profileId = active.id)) }
                }
            } catch (e: Exception) {
                onMain { onResult(ServerSyncResult(false, e.localizedMessage ?: "sync_error", profileId = active.id)) }
            } finally {
                running = false
            }
        }
    }

    fun lastSyncAt(context: Context): Long {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SYNC_AT, 0L)
    }

    fun lastStatus(context: Context): String {
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_STATUS, "") ?: ""
    }

    private fun normalizeUrl(value: String): String = value.trim().replace("&amp;", "&")

    private fun onMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
