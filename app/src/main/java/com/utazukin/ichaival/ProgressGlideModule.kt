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

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import okhttp3.*
import okio.*
import java.io.InputStream

@GlideModule
class ProgressGlideModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        val client = OkHttpClient.Builder().addInterceptor(createInterceptor(ResponseProgressListener())).build()
        registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(client))
    }

    companion object {
        private fun createInterceptor(listener: ResponseProgressListener): Interceptor {
            return object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    val request = chain.request()
                    val response = chain.proceed(request)
                    return response.newBuilder()
                        .body(OkHttpProgressResponseBody(request.url, response.body!!, listener))
                        .build()
                }
            }
        }
    }
}

interface UIProgressListener {
    fun update(progress: Int)
}

class ResponseProgressListener {

    fun update(url: HttpUrl, bytesRead: Long, fullLength: Long) {
        if (fullLength <= bytesRead)
            forget(url.toString())

        progressMap[url.toString()]?.update(((bytesRead / fullLength.toDouble()) * 100).toInt())
    }

    companion object {
        private val progressMap = mutableMapOf<String, UIProgressListener>()

        fun forget(url: String) = progressMap.remove(url)
        fun expect(url: String, listener: UIProgressListener) = progressMap.put(url, listener)
    }
}

private class OkHttpProgressResponseBody(private val url: HttpUrl,
                                         private val responseBody: ResponseBody,
                                         private val progressListener: ResponseProgressListener) : ResponseBody() {
    private var bufferedSource: BufferedSource? = null

    override fun contentLength() = responseBody.contentLength()

    override fun contentType() = responseBody.contentType()

    override fun source(): BufferedSource {
        return bufferedSource ?: source(responseBody.source()).buffer().also { bufferedSource = it }
    }

    private fun source(source: Source): ForwardingSource {
        return object: ForwardingSource(source) {
            private var totalBytesRead = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                val fullLength = responseBody.contentLength()
                if (bytesRead == -1L)
                    totalBytesRead = fullLength
                else
                    totalBytesRead += bytesRead

                progressListener.update(url, totalBytesRead, fullLength)
                return bytesRead
            }
        }
    }
}
