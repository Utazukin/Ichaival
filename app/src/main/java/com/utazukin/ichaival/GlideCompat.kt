package com.utazukin.ichaival

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.fragment.app.Fragment
import android.view.View
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import java.io.File

/**
 * Helper to load animated WebP via Glide for pre-P devices.
 * Calls [onReady] on success and [onError] on failure. The drawable
 * will be set into [imageView] by Glide; if it's animatable it will be started.
 */
fun loadAnimatedWebpWithGlide(
    fragment: Fragment,
    imageView: android.view.View,
    imageFile: File,
    onReady: (() -> Unit)? = null,
    onError: (() -> Unit)? = null
) {
    // PhotoView extends ImageView, so it's safe to cast at call sites
    Glide.with(fragment)
        .load(imageFile)
        .listener(object : RequestListener<Drawable?> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable?>,
                isFirstResource: Boolean
            ): Boolean {
                onError?.invoke()
                return true
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable?>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {
                onReady?.invoke()
                if (resource is Animatable) {
                    try {
                        resource.start()
                    } catch (_: Exception) {}
                }
                // Return false so Glide will set the drawable on the view.
                return false
            }
        })
        .into(imageView as android.widget.ImageView)
}
