package com.canasnn.tvremote.cast.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Disk cache of the phone player, used for progressive
 * http(s) media only. One instance per process; a new size applies the next
 * time the player opens (the open cache is released when the player closes).
 * Call from a background thread: opening the cache reads its index from disk.
 */
@OptIn(UnstableApi::class)
object PhoneCache {

    private var cache: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null

    private fun dir(context: Context) = File(context.applicationContext.cacheDir, "phone_player")

    private fun database(context: Context) =
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }

    /** The cache, opened with [sizeMb] if it isn't open yet; null when [sizeMb] is 0 or it can't be opened. */
    @Synchronized
    fun get(context: Context, sizeMb: Int): Cache? {
        if (sizeMb <= 0) return null
        cache?.let { return it }
        return runCatching {
            SimpleCache(dir(context), LeastRecentlyUsedCacheEvictor(sizeMb * 1024L * 1024L), database(context))
        }.getOrNull()?.also { cache = it }
    }

    /** Closes the cache (the player was released). */
    @Synchronized
    fun release() {
        runCatching { cache?.release() }
        cache = null
    }

    @Synchronized
    fun sizeBytes(context: Context): Long =
        cache?.cacheSpace ?: dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes everything cached; returns the bytes freed. */
    @Synchronized
    fun clear(context: Context): Long {
        val open = cache
        if (open != null) {
            val before = open.cacheSpace
            open.keys.toList().forEach { key -> runCatching { open.removeResource(key) } }
            return before - open.cacheSpace
        }
        val before = sizeBytes(context)
        runCatching { SimpleCache.delete(dir(context), database(context)) }
        return before - sizeBytes(context)
    }
}
