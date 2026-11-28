package com.pixiv.pixiv.advancesearch

import android.content.Context
import android.content.SharedPreferences

internal object ModulePrefs {
    private const val NAME = "pixiv_advance_search_module"
    private const val KEY_BADGES = "special_badges"
    private const val KEY_BLUR_COVERS = "blur_covers"
    private const val KEY_BLUR_DETAIL = "blur_detail"
    private const val KEY_BLUR_RADIUS = "blur_radius"

    const val MIN_BLUR_RADIUS = 5
    const val MAX_BLUR_RADIUS = 50
    const val DEFAULT_BLUR_RADIUS = 24

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun specialBadges(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BADGES, true)

    fun setSpecialBadges(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BADGES, value).apply()
    }

    fun blurCovers(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLUR_COVERS, true)

    fun setBlurCovers(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLUR_COVERS, value).apply()
    }

    fun blurDetail(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLUR_DETAIL, true)

    fun setBlurDetail(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLUR_DETAIL, value).apply()
    }

    fun blurRadius(context: Context): Int =
        prefs(context)
            .getInt(KEY_BLUR_RADIUS, DEFAULT_BLUR_RADIUS)
            .coerceIn(MIN_BLUR_RADIUS, MAX_BLUR_RADIUS)

    fun setBlurRadius(context: Context, value: Int) {
        prefs(context)
            .edit()
            .putInt(KEY_BLUR_RADIUS, value.coerceIn(MIN_BLUR_RADIUS, MAX_BLUR_RADIUS))
            .apply()
    }
}
