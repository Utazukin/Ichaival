/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2025 Utazukin
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.utazukin.ichaival

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Size
import androidx.preference.PreferenceManager
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.hippo.image.BitmapDecoder
import com.hippo.image.ImageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Stack
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import kotlin.math.max
import kotlin.math.min

fun getDpWidth(pxWidth: Int) : Int {
    val metrics = Resources.getSystem().displayMetrics
    return (pxWidth / metrics.density).toInt()
}

fun getDpAdjusted(pxSize: Int) : Int {
    val metrics = Resources.getSystem().displayMetrics
    return (pxSize * metrics.density).toInt()
}

@Suppress("DEPRECATION")
fun Activity.getWindowWidth() : Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        windowManager.currentWindowMetrics.bounds.width()
    else {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        metrics.widthPixels
    }
}

data class TermInfo(val term: String, val exact: Boolean, val negative: Boolean)

fun parseTermsInfo(query: CharSequence) : List<TermInfo> {
    val terms = mutableListOf<TermInfo>()
    val term = StringBuilder()
    val stack = Stack<Char>()
    var exact = false
    var negative = false
    for (c in query) {
        when {
            c == '\\' && stack.peekOrNull() != c -> stack.push(c)
            c == '\'' && stack.peekOrNull() == '"' -> term.append(c)
            c == '"' && stack.peekOrNull() == '\'' -> term.append(c)
            c == '\'' && stack.peekOrNull() == '\\' -> {
                stack.pop()
                term.append(c)
            }
            c == '"' && stack.peekOrNull() == '\\' -> {
                stack.pop()
                term.append(c)
            }
            (c == '\'' || c == '"') && stack.peekOrNull() == c -> {
                stack.pop()
                exact = stack.empty()
            }
            c == '\'' || c== '"' -> stack.push(c)
            c == ' ' && stack.empty() -> {
                terms.add(TermInfo(term.toString(), exact, negative))
                term.clear()
                exact = false
                negative = false
            }
            c == '_' && stack.empty() -> term.append(' ')
            c == '-' && stack.empty() && term.isEmpty() -> negative = true
            else -> term.append(c)
        }
    }

    while (!stack.empty())
        term.append(stack.pop())

    if (term.isNotEmpty())
        terms.add(TermInfo(term.toString(), exact, negative))

    return terms
}

fun parseTerms(query: CharSequence) = parseTermsInfo(query).map { it.term }

private fun <T> Stack<T>.peekOrNull() = if (empty()) null else peek()

fun SharedPreferences.castStringPrefToInt(pref: String, defaultValue: Int = 0) : Int {
    val stringPref = getString(pref, null)
    return if (stringPref.isNullOrBlank()) defaultValue else stringPref.toInt()
}

fun SharedPreferences.castStringPrefToLong(pref: String, defaultValue: Long = 0) : Long {
    val stringPref = getString(pref, null)
    return if (stringPref.isNullOrBlank()) defaultValue else stringPref.toLong()
}

fun SharedPreferences.castStringPrefToFloat(pref: String, defaultValue: Float = 0f) : Float {
    val stringPref = getString(pref, null)
    return if (stringPref.isNullOrBlank()) defaultValue else stringPref.toFloat()
}

fun Context.getCustomTheme() : String {
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    return prefs.getString(getString(R.string.theme_pref), getString(R.string.dark_theme)).toString()
}

fun JsonObject.getOrNull(memberName: String) : JsonElement? {
    val member = get(memberName)
    return if (member?.isJsonNull != false) null else member
}

fun <T> MutableList<T>.removeRange(start: Int, count: Int) {
    var i = min(start + count, size - 1)
    while (i >= 0 && i >= start)
        removeAt(i--)
}

val BitmapFactory.Options.outSize: Size
    get() = Size(outWidth, outHeight)

fun getImageFormat(imageFile: File) : ImageFormat? {
    val info = ImageInfo()
    return imageFile.inputStream().use {
        if (BitmapDecoder.decode(it, info))
            ImageFormat.fromInt(info.format)
        else
            null
    }
}

private fun ByteArray.isAscii(offset: Int, value: String) : Boolean {
    if (size < offset + value.length)
        return false

    for (index in value.indices) {
        if (this[offset + index].toInt().toChar() != value[index])
            return false
    }
    return true
}

fun isAnimatedWebp(imageFile: File) : Boolean {
    val header = ByteArray(32)
    val bytesRead = imageFile.inputStream().use { it.read(header) }
    if (bytesRead < header.size)
        return false

    if (!header.isAscii(0, "RIFF") || !header.isAscii(8, "WEBP") || !header.isAscii(12, "VP8X"))
        return false

    return (header[20].toInt() and 0x02) != 0
}

fun isAnimatedImage(imageFile: File) : Boolean {
    return getImageFormat(imageFile) == ImageFormat.GIF || isAnimatedWebp(imageFile)
}

fun SubsamplingScaleImageView.setDefaultScale() {
    if (!isReady)
        return

    val avgScale = (width + height) / 2f
    val imgScale = (sWidth + sHeight) / 2f
    val ratio = avgScale / imgScale
    if (maxScale < ratio) {
        maxScale = ratio
        setMinimumScaleType(SubsamplingScaleImageView.SCALE_TYPE_CUSTOM)
        resetScaleAndCenter()
    }
}

fun isLocalFile(path: String) = path.startsWith("/data")

fun ImageLoader.createGifLoader() : ImageLoader {
    return newBuilder().components {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            add(AnimatedImageDecoder.Factory())
        else
            add(GifDecoder.Factory())
    }.build()
}

val ImageLoader.diskCacheSize get() = diskCache?.size ?: 0

fun ImageLoader.clearDiskCache() = diskCache?.clear()

suspend fun ImageLoader.cacheOrGet(request: ImageRequest) : File? {
    return withContext(Dispatchers.IO) {
        val cache = diskCache?.openSnapshot(request.data as String)?.use { it.data.toFile() }
        if (cache != null)
            cache
        else {
            execute(request)
            diskCache?.openSnapshot(request.data as String)?.use { it.data.toFile() }
        }
    }
}

fun downloadCoilImageWithProgress(context: Context, imagePath: String, uiProgressListener: (Int) -> Unit) : ImageRequest {
    return downloadCoilImageWithProgress(context, imagePath, object: UIProgressListener {
        override fun update(progress: Int) {
            uiProgressListener(progress)
        }
    })
}

fun ImageRequest.Builder.addAuthHeader() : ImageRequest.Builder {
    val headers = NetworkHeaders.Builder()
    if (WebHandler.apiKey.isNotEmpty())
        headers["Authorization"] = WebHandler.apiKey

    for ((name, value) in WebHandler.customHeaders) {
        headers[name] = value
    }

    return this.httpHeaders(headers.build())
}

private fun downloadCoilImageWithProgress(context: Context, imagePath: String, uiProgressListener: UIProgressListener) : ImageRequest {
    return ImageRequest.Builder(context).apply {
        addAuthHeader()
        data(imagePath)
        memoryCachePolicy(CachePolicy.DISABLED)
        listener(
                onStart = { ResponseProgressListener.expect(imagePath, uiProgressListener) },
                onCancel = { ResponseProgressListener.forget(imagePath) },
                onError = { _, _ -> ResponseProgressListener.forget(imagePath) }
        )
    }.build()
}

fun Size.toRect() = Rect(0, 0, width, height)

inline fun <T> tryOrNull(body: () -> T) : T? {
    return try {
        body()
    }
    catch (e: Exception) {
        null
    }
}

private var mMaxTextureSize = -1
//Converted to Kotlin from
//https://stackoverflow.com/questions/7428996/hw-accelerated-activity-how-to-get-opengl-texture-size-limit/26823288#26823288
fun getMaxTextureSize() : Int {
    if (mMaxTextureSize > 0)
        return mMaxTextureSize

    val IMAGE_MAX_BITMAP_DIMENSION = 2048
    val egl: EGL10 = EGLContext.getEGL() as EGL10
    val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)

    val version = IntArray(2)
    egl.eglInitialize(display, version)

    val totalConfigurations = IntArray(1)
    egl.eglGetConfigs(display, null, 0, totalConfigurations)

    val configurationsList = Array<EGLConfig?>(totalConfigurations[0]) { null }
    egl.eglGetConfigs(display, configurationsList, totalConfigurations[0], totalConfigurations)

    val textureSize = IntArray(1)
    var maxTextureSize = 0

    for (configuration in configurationsList) {
        egl.eglGetConfigAttrib(display, configuration, EGL10.EGL_MAX_PBUFFER_WIDTH, textureSize)

        if (maxTextureSize < textureSize[0])
            maxTextureSize = textureSize[0]
    }

    egl.eglTerminate(display)
    mMaxTextureSize = max(maxTextureSize, IMAGE_MAX_BITMAP_DIMENSION)
    return mMaxTextureSize
}