/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2021 Utazukin
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

enum class ImageFormat(val value: Int) {
    JPG(2),
    PNG(3),
    GIF(4);

    companion object {
        private val map by lazy { values().associateBy(ImageFormat::value)}
        fun fromInt(type: Int) = map[type]
    }
}

class ImageDecoder : IImageDecoder {
    override fun decode(context: Context?, uri: Uri): Bitmap {
        return BitmapDecoder.decode(context?.contentResolver?.openInputStream(uri))!!
    }
}

class ImageRegionDecoder : IImageRegionDecoder {
    private var decoder: BitmapRegionDecoder? = null

    override fun init(context: Context?, uri: Uri): Point {
        decoder = BitmapRegionDecoder.newInstance(context?.contentResolver?.openInputStream(uri))
        return decoder?.let { Point(it.width, it.height) } ?: Point()
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        return decoder?.decodeRegion(sRect, BitmapDecoder.CONFIG_AUTO, sampleSize)!!
    }

    override fun isReady() = decoder?.isRecycled == false

    override fun recycle() {
        decoder?.recycle()
    }
}