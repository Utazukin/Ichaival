/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2022 Utazukin
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
import java.security.MessageDigest

private const val ID = "com.utazukin.ichaival.StartCrop"

class StartCrop : BitmapTransformation() {
    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
    }

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val matrix = Matrix()
        val scale: Float

        if (toTransform.width * outHeight > outWidth * toTransform.height) {
            scale = outHeight.toFloat() / toTransform.height
        } else {
            scale = outWidth.toFloat() / toTransform.width
        }

        matrix.setScale(scale, scale)
        val result = pool.get(outWidth, outHeight, toTransform.config ?: Bitmap.Config.ARGB_8888)
        result.setHasAlpha(toTransform.hasAlpha())

        val canvas = Canvas(result)
        canvas.drawBitmap(toTransform, matrix, Paint(Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.setBitmap(null)
        return result
    }

    override fun equals(other: Any?) = other is StartCrop
    override fun hashCode() = ID.hashCode()

    companion object {
        private val ID_BYTES = ID.toByteArray()
    }
}