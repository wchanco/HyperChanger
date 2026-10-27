// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val KEY_WALLPAPER_DIM_ENABLED = "wallpaper_dim_enabled"
internal const val KEY_WALLPAPER_DIM_PERCENT = "wallpaper_dim_percent"
internal const val WALLPAPER_DIM_MIN = 0
internal const val WALLPAPER_DIM_MAX = 60

/**
 * Dark-mode wallpaper dimming. The desktop and the lock screen share one wallpaper texture, so
 * there is one strength rather than one per surface.
 */
internal data class WallpaperDimSettings(
    val enabled: Boolean = false,
    val percent: Int = 40,
)

internal fun readWallpaperDimSettings(preferences: SharedPreferences) = WallpaperDimSettings(
    enabled = preferences.getBoolean(KEY_WALLPAPER_DIM_ENABLED, false),
    percent = preferences.getInt(KEY_WALLPAPER_DIM_PERCENT, 40).coerceIn(WALLPAPER_DIM_MIN, WALLPAPER_DIM_MAX),
)

/** Mirrors the switch and the slider into the remote preferences the wallpaper process reads. */
internal class WallpaperDimSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var settings = readWallpaperDimSettings(local)
        private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        if (remote.contains(KEY_WALLPAPER_DIM_ENABLED) || remote.contains(KEY_WALLPAPER_DIM_PERCENT)) {
            settings = readWallpaperDimSettings(remote)
        } else {
            write(remote)
        }
        write(local)
    }

    fun update(service: XposedService?, transform: (WallpaperDimSettings) -> WallpaperDimSettings): Boolean {
        val before = settings
        settings = sanitize(transform(settings))
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
        return before != settings
    }

    private fun sanitize(value: WallpaperDimSettings) =
        value.copy(percent = value.percent.coerceIn(WALLPAPER_DIM_MIN, WALLPAPER_DIM_MAX))

    private fun write(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean(KEY_WALLPAPER_DIM_ENABLED, settings.enabled)
            .putInt(KEY_WALLPAPER_DIM_PERCENT, settings.percent)
            .apply()
    }
}
