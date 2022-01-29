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

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapDrawableResource
import com.hippo.image.BitmapDecoder
import com.hippo.image.ImageInfo
import okhttp3.internal.and
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max

class GlideImageDecoder(private val context: Context, private val pool: BitmapPool) : ResourceDecoder<ByteBuffer, BitmapDrawable> {
    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<BitmapDrawable>? {
        ByteBufferInputStream(source).use {
            val info = ImageInfo()
            BitmapDecoder.decode(it, info)
            it.reset()
            val ratio = if (info.height > info.width)
                max(info.height.floorDiv(height), 2) - 1
            else
                max(info.width.floorDiv(width), 2) - 1
            return BitmapDecoder.decode(it, BitmapDecoder.CONFIG_AUTO, ratio)?.let { bitmap ->
                BitmapDrawableResource(BitmapDrawable(context.resources, bitmap), pool)
            }
        }
    }

    override fun handles(source: ByteBuffer, options: Options) : Boolean  {
        ByteBufferInputStream(source).use {
            val info = ImageInfo()
            if (!BitmapDecoder.decode(it, info) || info.frameCount > 1) {
                it.reset()
                return false
            }

            it.reset()
            return when (ImageFormat.fromInt(info.format)) {
                ImageFormat.JPG, ImageFormat.PNG -> true
                else -> false
            }
        }
    }
}

class ByteBufferInputStream(private val buffer: ByteBuffer) : InputStream() {
    override fun read(): Int {
        if (!buffer.hasRemaining())
            return -1
        return buffer.get() and 0xFF
    }

    override fun reset() {
        buffer.rewind()
    }
}
