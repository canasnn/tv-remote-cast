package com.ruterika.receiver

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import com.ruterika.protocol.TvAppInfo
import java.io.ByteArrayOutputStream

/**
 * The TV's launcher apps, read with PackageManager — the same list the TV
 * home screen shows. Replaces the old ADB `cmd package query-activities`.
 */
class AppCatalog(private val context: Context) {

    @Volatile
    private var cache: List<TvAppInfo>? = null
    @Volatile
    private var cacheWithIcons: List<TvAppInfo>? = null

    fun invalidate() {
        cache = null
        cacheWithIcons = null
    }

    fun apps(withIcons: Boolean): List<TvAppInfo> {
        (if (withIcons) cacheWithIcons else cache)?.let { return it }
        val list = load(withIcons)
        if (withIcons) cacheWithIcons = list else cache = list
        return list
    }

    private fun load(withIcons: Boolean): List<TvAppInfo> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val activities = pm.queryIntentActivities(query, 0)
        return activities
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .map { info ->
                val activity = info.activityInfo
                TvAppInfo(
                    component = activity.packageName + "/" + activity.name,
                    label = info.loadLabel(pm).toString().trim(),
                    iconPng = if (withIcons) runCatching { iconPng(pm, info.activityInfo) }.getOrNull() else null
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Prefer the 16:9 banner the TV launcher shows; fall back to the icon. */
    private fun iconPng(pm: PackageManager, activity: android.content.pm.ActivityInfo): String? {
        val banner = activity.loadBanner(pm)
        val drawable = banner ?: activity.loadIcon(pm) ?: return null
        val (w, h) = if (banner != null) BANNER_W to BANNER_H else ICON_SIZE to ICON_SIZE
        val bitmap = drawable.toBitmap(w, h)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun Drawable.toBitmap(width: Int, height: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null && bitmap.width == width && bitmap.height == height) {
            return bitmap
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, width, height)
        draw(canvas)
        return bitmap
    }

    private companion object {
        const val BANNER_W = 240
        const val BANNER_H = 135
        const val ICON_SIZE = 96
    }
}
