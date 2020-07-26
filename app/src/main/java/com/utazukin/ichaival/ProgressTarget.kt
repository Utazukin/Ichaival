/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2020 Utazukin
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

import android.graphics.drawable.Drawable
import android.widget.ProgressBar
import com.bumptech.glide.request.Request
import com.bumptech.glide.request.target.SizeReadyCallback
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition

class ProgressTarget<Z>(private val model: String,
                        private val target: Target<Z>,
                        private val progressBar: ProgressBar) : Target<Z>, UIProgressListener {
    private var ignoreProgress = true

    private fun start() {
        ResponseProgressListener.expect(model, this)
        ignoreProgress = false
        progressBar.max = 100
        progressBar.isIndeterminate = false
        update(0)
    }

    private fun cleanup() {
        ignoreProgress = true
        progressBar.progress = 100
        ResponseProgressListener.forget(model)
    }

    override fun onLoadStarted(placeholder: Drawable?) {
        target.onLoadStarted(placeholder)
        start()
    }

    override fun onLoadFailed(errorDrawable: Drawable?) {
        cleanup()
        target.onLoadFailed(errorDrawable)
    }

    override fun getSize(cb: SizeReadyCallback) = target.getSize(cb)

    override fun getRequest() = target.request

    override fun onStop() = target.onStop()

    override fun setRequest(request: Request?) {
        target.request = request
    }

    override fun removeCallback(cb: SizeReadyCallback) = target.removeCallback(cb)

    override fun onLoadCleared(placeholder: Drawable?) {
        cleanup()
        target.onLoadCleared(placeholder)
    }

    override fun onStart() = target.onStart()

    override fun onDestroy() = target.onDestroy()

    override fun onResourceReady(resource: Z, transition: Transition<in Z>?) {
        cleanup()
        target.onResourceReady(resource, transition)
    }

    override fun update(progress: Int) {
        progressBar.progress = progress
    }
}