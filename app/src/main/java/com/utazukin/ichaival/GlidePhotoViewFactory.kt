/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2019 Utazukin
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
import android.view.View
import com.bumptech.glide.Glide
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import com.github.piasy.biv.metadata.ImageInfoExtractor
import com.github.piasy.biv.view.BigImageView
import com.github.piasy.biv.view.GlideImageViewFactory
import java.io.File

class GlidePhotoViewFactory : GlideImageViewFactory() {
    var photoView: PhotoView? = null
        private set

    override fun createAnimatedImageView(context: Context?,
                                                   imageType: Int,
                                                   imageFile: File?,
                                                   initScaleType: Int): View {
        val view = when (imageType) {
            ImageInfoExtractor.TYPE_GIF -> {
                val photoView = PhotoView(context)
                photoView.scaleType = BigImageView.scaleType(initScaleType)
                if (context != null)
                    Glide.with(context).load(imageFile).into(photoView)
                photoView
            }
            else -> super.createAnimatedImageView(context, imageType, imageFile, initScaleType)
        }
        photoView = view as PhotoView
        return view
    }

    override fun createStillImageView(context: Context?): SubsamplingScaleImageView {
        photoView = null
        return super.createStillImageView(context)
    }
}