/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2024 Utazukin
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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.hippo.image.BitmapDecoder
import com.hippo.image.BitmapRegionDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder as IImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder as IImageRegionDecoder
import com.awxkee.jxlcoder.JxlCoder

enum class ImageFormat(private val value: Int) {
    JPG(2),
    PNG(3),
    GIF(4),
    JXL(5);

    companion object {
        private val map by lazy { entries.associateBy(ImageFormat::value)}
        fun fromInt(type: Int) = map[type]
        fun fromMimeType(mime: String) : ImageFormat? {
            return when (mime) {
                "image/gif" -> GIF
                "image/png" -> PNG
                "image/jpeg" -> JPG
                "image/jxl" -> JXL
                else -> null
            }
        }
    }
}

class ImageDecoder : IImageDecoder {
    override fun decode(context: Context, uri: Uri): Bitmap {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (isJxl(bytes)) {
                try {
                    JxlCoder.decode(bytes) ?: throw RuntimeException("JxlCoder returned null")
                } catch (e: Exception) {
                    BitmapDecoder.decode(bytes.inputStream())
                }
            } else {
                BitmapDecoder.decode(bytes.inputStream())
            }
        } ?: throw IllegalStateException("Failed to open stream for URI: $uri")
    }
}

class ImageRegionDecoder : IImageRegionDecoder {
    private var decoder: BitmapRegionDecoder? = null
    private var fullBitmap: Bitmap? = null
    private var jxlBytes: ByteArray? = null
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var isJxl = false

    override fun init(context: Context, uri: Uri): Point {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (isJxl(bytes)) {
                isJxl = true
                jxlBytes = bytes
                try {
                    val size = JxlCoder.getSize(bytes)
                    if (size != null) {
                        imageWidth = size.width
                        imageHeight = size.height
                        
                        // For images smaller than 4096x4096, cache the full bitmap to avoid repeated decoding
                        if (imageWidth.toLong() * imageHeight <= 4096 * 4096) {
                            fullBitmap = JxlCoder.decode(bytes)
                        }
                        Point(imageWidth, imageHeight)
                    } else {
                        val temp = JxlCoder.decode(bytes)
                        if (temp != null) {
                            imageWidth = temp.width
                            imageHeight = temp.height
                            fullBitmap = temp
                            Point(imageWidth, imageHeight)
                        } else Point()
                    }
                } catch (e: Exception) {
                    isJxl = false
                    jxlBytes = null
                    decoder = BitmapRegionDecoder.newInstance(bytes.inputStream())
                    decoder?.let { Point(it.width, it.height) } ?: Point()
                }
            } else {
                decoder = BitmapRegionDecoder.newInstance(bytes.inputStream())
                decoder?.let { Point(it.width, it.height) } ?: Point()
            }
        } ?: Point()
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        if (isJxl) {
            val bitmap = fullBitmap
            if (bitmap != null) {
                val left = sRect.left.coerceIn(0, bitmap.width - 1)
                val top = sRect.top.coerceIn(0, bitmap.height - 1)
                val width = sRect.width().coerceAtMost(bitmap.width - left)
                val height = sRect.height().coerceAtMost(bitmap.height - top)
                
                val region = Bitmap.createBitmap(bitmap, left, top, width, height)
                return if (sampleSize > 1) {
                    Bitmap.createScaledBitmap(region, width / sampleSize, height / sampleSize, true).also {
                        if (it != region) region.recycle()
                    }
                } else {
                    region
                }
            } else {
                val bytes = jxlBytes ?: throw IllegalStateException("JXL bytes missing")
                // For very large images, we decode full and crop. 
                // Optimization with JxlCoder.decodeSampled could be added here if memory is an issue.
                val full = JxlCoder.decode(bytes) ?: throw RuntimeException("Failed to decode JXL")
                val left = sRect.left.coerceIn(0, full.width - 1)
                val top = sRect.top.coerceIn(0, full.height - 1)
                val width = sRect.width().coerceAtMost(full.width - left)
                val height = sRect.height().coerceAtMost(full.height - top)
                val region = Bitmap.createBitmap(full, left, top, width, height)
                val result = if (sampleSize > 1) {
                    Bitmap.createScaledBitmap(region, width / sampleSize, height / sampleSize, true).also {
                        if (it != region) region.recycle()
                    }
                } else {
                    region
                }
                full.recycle()
                return result
            }
        } else {
            return decoder?.decodeRegion(sRect, BitmapDecoder.CONFIG_AUTO, sampleSize)
                ?: throw IllegalStateException("Decoder not initialized")
        }
    }

    override fun isReady(): Boolean = decoder != null || fullBitmap != null || jxlBytes != null

    override fun recycle() {
        decoder?.recycle()
        decoder = null
        fullBitmap?.recycle()
        fullBitmap = null
        jxlBytes = null
    }
}

private fun isJxl(bytes: ByteArray): Boolean {
    if (bytes.size < 2) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    
    // Naked codestream: 0xFF, 0x0A
    if (b0 == 0xFF && b1 == 0x0A) return true
    
    // Container format: 0x00, 0x00, 0x00, 0x0C, 0x4A, 0x58, 0x4C, 0x20
    if (bytes.size >= 12 &&
        b0 == 0x00 && bytes[1].toInt() == 0x00 && bytes[2].toInt() == 0x00 && bytes[3].toInt() == 0x0C &&
        bytes[4].toInt() == 0x4A && bytes[5].toInt() == 0x58 && bytes[6].toInt() == 0x4C && bytes[7].toInt() == 0x20) {
        return true
    }
    return false
}
