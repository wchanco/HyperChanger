// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Service
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Dark-mode wallpaper dimming inside com.miui.miwallpaper.
 *
 * HyperOS 4's launcher is a native process without an ART runtime, so the dim cannot be done from
 * the launcher side. It is done on the wallpaper *content* instead of on a surface alpha: the
 * bitmap is darkened once, right where it is uploaded to the GL texture
 * (ImageWallpaperRenderer.onSurfaceCreated -> lambda$onSurfaceCreated$0 -> mTexture.use()).
 *
 * Why not the surface: WindowManager and MIUI both animate the wallpaper window's alpha, and the
 * lock-screen and app transitions snapshot the layer's buffer into a leash. A snapshot carries the
 * pixels, not the layer alpha, so any alpha-based dim flashes the undimmed picture for the length
 * of the animation and vanishes the moment the window alpha is written. Darkened pixels survive
 * both, and they also cover the lock screen's own copy of the picture, which is drawn from this
 * same texture by a different window.
 *
 * Failure policy: this process owns the wallpaper, and a broken hook leaves the phone without one.
 * The hook is installed defensively, every bitmap operation is guarded, and a rejected upload
 * simply keeps whatever the OEM was going to draw.
 */
internal object WallpaperDimHooks {
    private const val TAG = "HyperChangerWpDim"
    private const val SERVICE_CLASS = "android.service.wallpaper.WallpaperService"
    private const val RENDERER_CLASS = "com.miui.miwallpaper.opengl.ImageWallpaperRenderer"

    /**
     * The one place the wallpaper bitmap reaches the GL upload. Which form an OEM build carries is
     * not knowable - OS4 has the plain name, a HyperOS 3 report had the class-mangled one - so both
     * are looked for, the same way the music-cover probe does.
     */
    private const val UPLOAD_LAMBDA = "lambda\$onSurfaceCreated\$0"

    @Volatile
    private var context: Context? = null

    @Volatile
    private var installed = false

    /** The last upload that was darkened, so a texture re-upload does not dim twice. */
    @Volatile
    private var lastSource: Bitmap? = null

    @Volatile
    private var lastPercent = -1

    @Volatile
    private var lastDimmed: Bitmap? = null

    /** Bitmaps this module produced, so a second pass over the same picture cannot dim it twice. */
    private val produced: MutableSet<Bitmap> = Collections.newSetFromMap(WeakHashMap<Bitmap, Boolean>())

    fun install(module: XposedModule, classLoader: ClassLoader, preferences: SharedPreferences) {
        if (installed) return
        val serviceClass = runCatching { classLoader.loadClass(SERVICE_CLASS) }.getOrNull()
        if (serviceClass != null) {
            // The service is the only place a Context is guaranteed, and it is also where a
            // dark-mode toggle arrives: onConfigurationChanged runs for every engine.
            hookQuietly(module, serviceClass, "onCreate", emptyArray<Class<*>>()) { chain ->
                val result = chain.proceed()
                (chain.getThisObject() as? Service)?.let { context = it.applicationContext }
                result
            }
            hookQuietly(module, serviceClass, "onConfigurationChanged", arrayOf<Class<*>>(Configuration::class.java)) { chain ->
                val result = chain.proceed()
                (chain.getThisObject() as? Service)?.let { context = it.applicationContext }
                result
            }
        }

        val renderer = runCatching { classLoader.loadClass(RENDERER_CLASS) }.getOrNull()
        if (renderer == null) {
            ModuleLog.w(TAG, "$RENDERER_CLASS missing; wallpaper dim stays off")
            return
        }
        val uploads = renderer.declaredMethods.filter {
            it.name == UPLOAD_LAMBDA || it.name.startsWith("$UPLOAD_LAMBDA\$")
        }
        if (uploads.isEmpty()) {
            ModuleLog.w(TAG, "$UPLOAD_LAMBDA missing; wallpaper dim stays off")
            return
        }
        uploads.forEach { method ->
            runCatching {
                module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain -> dimUpload(chain, preferences) }
            }.onFailure { ModuleLog.w(TAG, "hook ${method.name} failed", it) }
        }
        installed = true
        ModuleLog.i(TAG, "wallpaper dim hooks installed on ${uploads.size} upload path(s)")
    }

    /**
     * Replaces the bitmap on its way into the texture with a darkened copy. Runs on the GL thread
     * once per surface creation, so the copy is cached against its source.
     */
    private fun dimUpload(chain: XposedInterface.Chain, preferences: SharedPreferences): Any? {
        val source = chain.getArg(0) as? Bitmap ?: return chain.proceed()
        // A texture that already carries our darkening must not be darkened again.
        if (source in produced) return chain.proceed()
        val percent = dimPercent(preferences) ?: return chain.proceed()
        val dimmed = dimmed(source, percent) ?: return chain.proceed()
        return chain.proceed(arrayOf<Any>(dimmed))
    }

    /** The strength this module insists on as a percentage, or null when the OEM bitmap stands. */
    private fun dimPercent(preferences: SharedPreferences): Int? {
        val settings = readWallpaperDimSettings(preferences)
        if (!settings.enabled || !isNightMode()) return null
        return settings.percent.coerceIn(WALLPAPER_DIM_MIN, WALLPAPER_DIM_MAX)
    }

    private fun dimmed(source: Bitmap, percent: Int): Bitmap? {
        if (percent <= 0) return null
        if (source === lastSource && percent == lastPercent) return lastDimmed
        val result = runCatching {
            val copy = if (source.config == Bitmap.Config.ARGB_8888) {
                source.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                source.copy(Bitmap.Config.ARGB_8888, true)
            } ?: return@runCatching null
            val alpha = (percent.coerceIn(0, 100) * 255 / 100)
            Canvas(copy).drawColor(Color.argb(alpha, 0, 0, 0), PorterDuff.Mode.SRC_OVER)
            copy
        }.onFailure { ModuleLog.w(TAG, "could not darken the wallpaper bitmap", it) }
            .getOrNull()
        if (result != null) {
            produced.add(result)
            lastSource = source
            lastPercent = percent
            lastDimmed = result
            ModuleLog.i(TAG, "wallpaper texture darkened to ${100 - percent}% of its brightness")
        }
        return result
    }

    private fun isNightMode(): Boolean = runCatching {
        val configuration = context?.resources?.configuration ?: return@runCatching false
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }.getOrDefault(false)

    private fun hookQuietly(
        module: XposedModule,
        type: Class<*>,
        name: String,
        parameterTypes: Array<Class<*>>,
        body: (XposedInterface.Chain) -> Any?,
    ) {
        runCatching {
            val method = findDeclaredMethod(type, name, parameterTypes) ?: error("$name not declared")
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> body(chain) }
        }.onFailure { ModuleLog.w(TAG, "hook $name on ${type.simpleName} failed", it) }
    }

    /**
     * Finds [name] in [type] or its superclasses. WallpaperService does not override every
     * lifecycle it inherits from Service, so a declared-only lookup would silently skip the
     * configuration callback that carries the dark-mode toggle.
     */
    private fun findDeclaredMethod(type: Class<*>, name: String, parameterTypes: Array<Class<*>>): Method? {
        var current: Class<*>? = type
        while (true) {
            val target = current ?: return null
            if (target == Any::class.java) return null
            runCatching { target.getDeclaredMethod(name, *parameterTypes) }.getOrNull()?.let { return it }
            current = target.superclass
        }
    }
}
