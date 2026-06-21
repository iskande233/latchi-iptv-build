package com.latchi.iptv.utils

import android.content.Context
import com.latchi.iptv.model.Channel
import java.io.File
import java.security.MessageDigest

/**
 * ⚡ Disk channel cache with strict per-type metadata.
 *
 * كل كاش مربوط بـ:
 * - profileId
 * - serverRevision
 * - sourceUrl hash
 * - contentType
 * - lastUpdated
 *
 * هكذا TV/Phone يستعملو نفس البيانات، وإذا تبدّل السيرفر أو revision ما نستعملوش كاش قديم.
 */
object ChannelCache {
    private const val PREFS_NAME = "iptv_channel_enterprise_cache"

    data class TypeMeta(
        val profileId: String,
        val contentType: String,
        val serverRevision: Long,
        val sourceHash: String,
        val lastUpdated: Long
    )

    private fun getDiskFile(context: Context, profileId: String, contentType: String): File {
        val suffix = when (contentType) {
            "movie", "movies", "vod" -> "vod"
            "series" -> "series"
            else -> "live"
        }
        return File(context.filesDir, "latchi_db_${suffix}_${profileId}.db")
    }

    private fun getLiveDiskFile(context: Context, profileId: String): File = getDiskFile(context, profileId, "live")
    private fun getVodDiskFile(context: Context, profileId: String): File = getDiskFile(context, profileId, "movie")
    private fun getSeriesDiskFile(context: Context, profileId: String): File = getDiskFile(context, profileId, "series")

    @Synchronized
    fun save(context: Context, profileId: String, channels: List<Channel>) {
        try {
            val appContext = context.applicationContext
            val active = runCatching { SourcePrefs.getActiveProfile(appContext)?.takeIf { it.id == profileId } }.getOrNull()
            val revision = active?.serverRevision ?: 0L
            val sourceHash = hashSource(active?.m3uUrl.orEmpty())

            writeChannelsToFile(getLiveDiskFile(appContext, profileId), channels.filter { it.contentType == "live" }, "live")
            writeChannelsToFile(getVodDiskFile(appContext, profileId), channels.filter { it.contentType == "movie" }, "movie")
            writeChannelsToFile(getSeriesDiskFile(appContext, profileId), channels.filter { it.contentType == "series" }, "series")

            updateMeta(appContext, profileId, revision, sourceHash)
            updateTypeMeta(appContext, profileId, "live", revision, sourceHash)
            updateTypeMeta(appContext, profileId, "movie", revision, sourceHash)
            updateTypeMeta(appContext, profileId, "series", revision, sourceHash)
        } catch (e: Exception) {
            android.util.Log.e("ChannelCache", "Master DB Save Crash: ${e.message}")
        }
    }

    @Synchronized
    fun saveLiveOnly(context: Context, profileId: String, liveChannels: List<Channel>) {
        saveType(context, profileId, "live", liveChannels)
    }

    @Synchronized
    fun saveType(context: Context, profileId: String, contentType: String, channels: List<Channel>) {
        try {
            val appContext = context.applicationContext
            val normalizedType = normalizeType(contentType)
            val active = runCatching { SourcePrefs.getActiveProfile(appContext)?.takeIf { it.id == profileId } }.getOrNull()
            val revision = active?.serverRevision ?: 0L
            val sourceHash = hashSource(active?.m3uUrl.orEmpty())
            writeChannelsToFile(getDiskFile(appContext, profileId, normalizedType), channels.filter { it.contentType == normalizedType }, normalizedType)
            updateMeta(appContext, profileId, revision, sourceHash)
            updateTypeMeta(appContext, profileId, normalizedType, revision, sourceHash)
        } catch (e: Exception) {
            android.util.Log.e("ChannelCache", "Type Save Crash: ${e.message}")
        }
    }

    @Synchronized
    fun load(context: Context, profileId: String): List<Channel> {
        return try {
            val list = mutableListOf<Channel>()
            list.addAll(loadByType(context, profileId, "live"))
            list.addAll(loadByType(context, profileId, "movie"))
            list.addAll(loadByType(context, profileId, "series"))
            list
        } catch (e: Exception) {
            android.util.Log.e("ChannelCache", "Master DB Load Crash: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    fun loadByType(context: Context, profileId: String, contentType: String): List<Channel> {
        return try {
            val type = normalizeType(contentType)
            val file = getDiskFile(context.applicationContext, profileId, type)
            if (!file.exists()) return emptyList()
            val list = mutableListOf<Channel>()
            file.useLines(Charsets.UTF_8) { lines ->
                lines.forEach { line ->
                    val parts = line.split("\t", limit = 5)
                    if (parts.size >= 5) list.add(Channel(parts[0], parts[1], parts[2], parts[3], parts[4]))
                }
            }
            list
        } catch (e: Exception) {
            android.util.Log.e("ChannelCache", "Type DB Load Crash: ${e.message}")
            emptyList()
        }
    }

    private fun writeChannelsToFile(file: File, channels: List<Channel>, contentType: String) {
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            channels.forEach { channel ->
                writer.write(
                    "${channel.name.replace("\t", " ")}\t${channel.logoUrl.replace("\t", " ")}\t${channel.streamUrl.replace("\t", " ")}\t${channel.category.replace("\t", " ")}\t$contentType\n"
                )
            }
        }
    }

    private fun updateMeta(context: Context, profileId: String, revision: Long, sourceHash: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("updated_$profileId", System.currentTimeMillis())
            .putLong("revision_$profileId", revision)
            .putString("source_hash_$profileId", sourceHash)
            .apply()
    }

    private fun updateTypeMeta(context: Context, profileId: String, contentType: String, revision: Long, sourceHash: String) {
        val type = normalizeType(contentType)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("updated_${profileId}_$type", System.currentTimeMillis())
            .putLong("revision_${profileId}_$type", revision)
            .putString("source_hash_${profileId}_$type", sourceHash)
            .apply()
    }

    fun updatedAt(context: Context, profileId: String): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("updated_$profileId", 0L)

    fun updatedAt(context: Context, profileId: String, contentType: String): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("updated_${profileId}_${normalizeType(contentType)}", 0L)

    fun revision(context: Context, profileId: String): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("revision_$profileId", 0L)

    fun revision(context: Context, profileId: String, contentType: String): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong("revision_${profileId}_${normalizeType(contentType)}", 0L)

    fun sourceHash(context: Context, profileId: String, contentType: String): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("source_hash_${profileId}_${normalizeType(contentType)}", "") ?: ""

    fun typeMeta(context: Context, profileId: String, contentType: String): TypeMeta = TypeMeta(
        profileId = profileId,
        contentType = normalizeType(contentType),
        serverRevision = revision(context, profileId, contentType),
        sourceHash = sourceHash(context, profileId, contentType),
        lastUpdated = updatedAt(context, profileId, contentType)
    )

    fun markRevision(context: Context, profileId: String, revision: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("revision_$profileId", revision)
            .putLong("revision_${profileId}_live", revision)
            .putLong("revision_${profileId}_movie", revision)
            .putLong("revision_${profileId}_series", revision)
            .apply()
    }

    fun isTypeCacheFresh(context: Context, profile: IptvProfile, contentType: String): Boolean {
        val type = normalizeType(contentType)
        val file = getDiskFile(context.applicationContext, profile.id, type)
        if (!file.exists() || file.length() <= 0L) return false
        val meta = typeMeta(context, profile.id, type)
        val expectedHash = hashSource(profile.m3uUrl)
        val revisionOk = profile.serverRevision <= 0L || meta.serverRevision == profile.serverRevision
        val sourceOk = expectedHash.isBlank() || meta.sourceHash == expectedHash
        return meta.lastUpdated > 0L && revisionOk && sourceOk
    }

    @Synchronized
    fun clear(context: Context, profileId: String) {
        try {
            getLiveDiskFile(context, profileId).delete()
            getVodDiskFile(context, profileId).delete()
            getSeriesDiskFile(context, profileId).delete()
            val edit = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove("updated_$profileId")
                .remove("revision_$profileId")
                .remove("source_hash_$profileId")
            listOf("live", "movie", "series").forEach { type ->
                edit.remove("updated_${profileId}_$type")
                    .remove("revision_${profileId}_$type")
                    .remove("source_hash_${profileId}_$type")
            }
            edit.apply()
        } catch (_: Exception) {}
    }

    private fun normalizeType(type: String): String = when (type.lowercase()) {
        "movies", "vod" -> "movie"
        else -> type.lowercase().ifBlank { "live" }
    }

    fun hashSource(value: String): String {
        val normalized = value.trim().replace("&amp;", "&")
        if (normalized.isBlank()) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(16)
        } catch (_: Exception) {
            normalized.hashCode().toString()
        }
    }
}
