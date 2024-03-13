/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2024 Utazukin
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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import okio.use
import org.json.JSONObject
import java.net.HttpURLConnection
import kotlin.math.floor

class ProgressInterceptor(private val listener: ResponseProgressListener) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = if (WebHandler.apiKey.isEmpty()) chain.request() else chain.request().newBuilder().addHeader("Authorization", WebHandler.apiKey).build()
        val response = chain.proceed(request)
        return response.newBuilder()
            .body(OkHttpProgressResponseBody(request.url, response.body!!, listener))
            .build()
    }
}

class ThumbHttpInterceptor(private val scope: CoroutineScope) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = if (WebHandler.apiKey.isEmpty()) chain.request() else chain.request().newBuilder().addHeader("Authorization", WebHandler.apiKey).build()
        val response = chain.proceed(request)
        if (response.code != HttpURLConnection.HTTP_ACCEPTED)
            return response

        val clone = chain.call().clone()
        return response.body?.use {
            val json = JSONObject(it.string())
            val job = json.getInt("job")
            waitForJob(job, chain) ?: return@use response
            clone.execute()
        } ?: response
    }

    private fun waitForJob(jobId: Int, chain: Interceptor.Chain): Boolean? {
        var jobComplete: Boolean?
        do {
            Thread.sleep(100)
            jobComplete = checkJobStatus(jobId, chain)
        } while (jobComplete == null && !chain.call().isCanceled() && scope.isActive)

        return jobComplete
    }

    private fun checkJobStatus(jobId: Int, chain: Interceptor.Chain): Boolean? {
        val url = WebHandler.getUrlForJob(jobId)
        val response = chain.proceed(Request.Builder().apply {
            addHeader("Authorization", WebHandler.apiKey)
            url(url)
        }.build())

        response.use {
            if (!it.isSuccessful)
                return false

            it.body?.run {
                val json = JSONObject(string())
                return when(json.optString("state")) {
                    "finished" -> true
                    "failed" -> false
                    else -> null
                }
            }
        }

        return false
    }
}

interface UIProgressListener {
    fun update(progress: Int)
}

class ResponseProgressListener {

    fun update(url: HttpUrl, bytesRead: Long, fullLength: Long) {
        val urlString = url.toString()
        progressMap[urlString]?.update(((bytesRead / fullLength.toDouble()) * 100).toInt())

        if (fullLength <= bytesRead)
            forget(urlString)
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
                val previousBytesRead = totalBytesRead
                if (bytesRead == -1L)
                    totalBytesRead = fullLength
                else
                    totalBytesRead += bytesRead


                val prevPercent = floor((previousBytesRead / fullLength.toFloat()) * 100)
                val percent = floor((totalBytesRead / fullLength.toFloat()) * 100)
                if (totalBytesRead >= fullLength || percent > prevPercent)
                    progressListener.update(url, totalBytesRead, fullLength)
                return bytesRead
            }
        }
    }
}
