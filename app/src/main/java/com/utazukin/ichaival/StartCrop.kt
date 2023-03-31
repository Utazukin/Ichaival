/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2023 Utazukin
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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.TransformationUtils
import java.security.MessageDigest
import kotlin.concurrent.withLock

class StartCrop : BitmapTransformation() {
    override fun updateDiskCacheKey(messageDigest: MessageDigest) = messageDigest.update(ID_BYTES)

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        if (toTransform.width == outWidth && toTransform.height == outHeight)
            return toTransform

        val scale = if (toTransform.width * outHeight > outWidth * toTransform.height) {
            outHeight.toFloat() / toTransform.height
        } else {
            outWidth.toFloat() / toTransform.width
        }

        val result = pool.get(outWidth, outHeight, toTransform.config ?: Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(toTransform.hasAlpha())
        }

        val matrix = Matrix().apply { setScale(scale, scale) }
        TransformationUtils.getBitmapDrawableLock().withLock {
            with(Canvas(result)) {
                drawBitmap(toTransform, matrix, Paint(Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG))
                setBitmap(null)
            }
        }
        return result
    }

    override fun equals(other: Any?) = other is StartCrop
    override fun hashCode() = ID.hashCode()

    companion object {
        private const val ID = "com.utazukin.ichaival.StartCrop"
        private val ID_BYTES = ID.toByteArray()
    }
}