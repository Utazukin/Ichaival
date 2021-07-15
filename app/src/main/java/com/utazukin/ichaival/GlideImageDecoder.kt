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
import kotlin.math.floor
import kotlin.math.max

class GlideImageDecoder(private val context: Context, private val pool: BitmapPool) : ResourceDecoder<ByteBuffer, BitmapDrawable> {
    override fun decode(source: ByteBuffer, width: Int, height: Int, options: Options): Resource<BitmapDrawable>? {
        val info = ImageInfo()
        val stream = ByteBufferInputStream(source)
        BitmapDecoder.decode(stream, info)
        stream.reset()
        val ratio = max(floor(info.height / height.toFloat()).toInt(), 1)
        return BitmapDecoder.decode(stream, BitmapDecoder.CONFIG_AUTO, ratio)?.let {
            BitmapDrawableResource(BitmapDrawable(context.resources, it), pool)
        }
    }

    override fun handles(source: ByteBuffer, options: Options) : Boolean  {
        val info = ImageInfo()
        val stream = ByteBufferInputStream(source)
        if (!BitmapDecoder.decode(stream, info) || info.frameCount > 1) {
            stream.reset()
            return false
        }

        stream.reset()
        return when (ImageFormat.fromInt(info.format)) {
            ImageFormat.JPG, ImageFormat.PNG -> true
            else -> false
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
