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
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import coil.size.Size
import coil.size.pxOrElse
import coil.transform.Transformation

class StartCrop : Transformation {
    override val cacheKey: String = javaClass.name

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val outWidth = size.width.pxOrElse { 0 }
        val outHeight = size.height.pxOrElse { 0 }
        if (input.width == outWidth && input.height == outHeight)
            return input

        val scale = if (input.width * outHeight > outWidth * input.height) {
            outHeight.toFloat() / input.height
        } else {
            outWidth.toFloat() / input.width
        }

        val result = createBitmap(outWidth, outHeight, input.config ?: Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(input.hasAlpha())
        }

        val matrix = Matrix().apply { setScale(scale, scale) }
        result.applyCanvas { drawBitmap(input, matrix, Paint(Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        return result
    }

    override fun equals(other: Any?) = other is StartCrop
    override fun hashCode() = javaClass.hashCode()
}