/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2025 Utazukin
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
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.utazukin.ichaival.database.DatabaseMessageListener
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.database.DatabaseRefreshListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TagSuggestion(tagText: String, namespaceText: String, val weight: Int) {
    private val tag = tagText.lowercase()
    private val namespace = namespaceText.lowercase()
    val displayTag = if (namespace.isNotBlank()) "$namespace:$tag" else tag

    fun contains(query: String): Boolean {
        return if (":" !in query)
            tag.contains(query, true)
        else {
            displayTag.contains(query, true)
        }
    }
}

data class Header(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: String)

object WebHandler {
    private const val connTimeoutMs = 5000L
    private const val readTimeoutMs = 60000L
    private const val headerFile = "headers.json"

    private fun HttpUrl.Builder.addPathSegment(value: Int) = addPathSegment(value.toString())
    private fun HttpUrl.Builder.addQueryParameter(name: String, value: Int) = addQueryParameter(name, value.toString())
    private fun HttpUrl.Builder.addQueryParameter(name: String, value: Long) = addQueryParameter(name, value.toString())
    private fun HttpUrl.Builder.addQueryParameter(name: String, value: Boolean) = addQueryParameter(name, value.toString())
    private fun HttpUrl.Builder.addQueryParameter(name: String, value: CharSequence) = addQueryParameter(name, value.toString())
    private fun HttpUrl.Builder.addApi() = addPathSegment("api")
    private fun HttpUrl.Builder.addDatabase() = addApi().addPathSegment("database")
    private fun HttpUrl.Builder.addArchiveList() = addApi().addPathSegment("archives")
    private fun HttpUrl.Builder.addThumb(id: String) = addArchiveList().addPathSegment(id).addPathSegment("thumbnail")
    private fun HttpUrl.Builder.addFiles(id: String) = addArchiveList().addPathSegment(id).addPathSegment("files")
    private fun HttpUrl.Builder.addProgress(id: String, page: Int) : HttpUrl.Builder {
        return addArchiveList().addPathSegment(id).addPathSegment("progress").addPathSegment((page + 1))
    }
    private fun HttpUrl.Builder.addDeleteArchive(id: String) = addArchiveList().addPathSegment(id)
    private fun HttpUrl.Builder.addTags() = addDatabase().addPathSegment("stats")
    private fun HttpUrl.Builder.addClearNew(id: String) = addArchiveList().addPathSegment(id).addPathSegment("isnew")
    private fun HttpUrl.Builder.addSearch() = addApi().addPathSegment("search")
    private fun HttpUrl.Builder.addInfo() = addApi().addPathSegment("info")
    private fun HttpUrl.Builder.addCategory() = addApi().addPathSegment("categories")
    private fun HttpUrl.Builder.addClearTemp() = addApi().addPathSegment("tempfolder")
    private fun HttpUrl.Builder.addModifyCategory(categoryId: String, archiveId: String) = addCategory().addPathSegment(categoryId).addPathSegment(archiveId)
    private fun HttpUrl.Builder.addMinionStatus(id: Int) = addApi().addPathSegment("minion").addPathSegment(id)

    var serverLocation: String = ""
    var apiKey: String = ""
        set(value) {
            field = if (value.isNotEmpty()) "Bearer ${Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)}" else ""
        }
    var customHeaders = listOf<Header>()
        private set
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(connTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 20 })
        .build()

    var verboseMessages = false
    var listener: DatabaseMessageListener? = null
    private var hasNetwork = false
    private val refreshListeners = mutableListOf<DatabaseRefreshListener>()
    private val serverUrlBuilder: HttpUrl.Builder
        get() = serverLocation.toHttpUrl().newBuilder()

    suspend fun init(context: Context) {
        withContext(Dispatchers.IO) {
            val gson = Gson()
            val file = File(context.noBackupFilesDir, headerFile)
            val listType = object: TypeToken<List<Header>>() {}.type
            customHeaders = if (file.exists()) JsonReader(file.inputStream().bufferedReader()).use { gson.fromJson(it, listType) } else listOf()
        }
    }

    suspend fun updateHeaders(context: Context, headers: List<Header>) {
        customHeaders = headers
        withContext(Dispatchers.IO) {
            val gson = Gson()
            val file = File(context.noBackupFilesDir, headerFile)
            val json = gson.toJson(headers)
            file.writeText(json)
        }
    }

    suspend fun getServerInfo(): Pair<Int, JSONObject?> {
        if (!canConnect())
            return Pair(-1, null)

        val url = serverUrlBuilder.addInfo().build()
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).await()
        val result = response.use {
            if (!it.isSuccessful)
                Pair(it.code, null)
            else
                Pair(it.code, it.body?.run { JSONObject(suspendString()) })
        }

        return result
    }

    fun registerRefreshListener(listener: DatabaseRefreshListener) = refreshListeners.add(listener)
    fun unregisterRefreshListener(listener: DatabaseRefreshListener) =
        refreshListeners.remove(listener)

    fun updateRefreshing(refreshing: Boolean) {
        for (listener in refreshListeners)
            listener.isRefreshing(refreshing)
    }

    suspend fun clearTempFolder(context: Context) = withContext(Dispatchers.IO) {
        if (!canConnect())
            return@withContext

        val errorMessage = context.getString(R.string.temp_clear_fail_message)
        val url = serverUrlBuilder.addClearTemp().build()
        val connection = createServerConnection(url, "DELETE")
        val response = httpClient.newCall(connection).tryAwait(errorMessage)
        response?.use {
            if (it.isSuccessful)
                notify(context.getString(R.string.temp_clear_success_message))
            else
                handleErrorMessage(it.code, errorMessage)
        }
    }

    suspend fun getTagList() : JSONArray? {
        if (!canConnect())
            return null

        val url = serverUrlBuilder.addTags().build()
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).tryAwait()
        return response?.use {
            if (!it.isSuccessful)
                null
            else
                tryOrNull { it.body?.run { JSONArray(suspendString()) } }
        }
    }

    suspend fun getCategories() : InputStream? = withContext(Dispatchers.IO) {
        if (!canConnect())
            return@withContext null

        val url = serverUrlBuilder.addCategory().build()
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).tryAwait()
        response?.let {
            if (!it.isSuccessful)
                null
            else
                it.body?.byteStream()
        }
    }

    suspend fun deleteArchives(ids: List<String>) : List<String> {
        if (!canConnect())
            return emptyList()

        return coroutineScope {
            val jobs = List(ids.size) { async { if (deleteArchive(ids[it], false)) ids[it] else null } }
            jobs.awaitAll().filterNotNull()
        }
    }

    suspend fun deleteArchive(archiveId: String) = withContext(Dispatchers.IO) { deleteArchive(archiveId, true) }

    private suspend fun deleteArchive(archiveId: String, checkConnection: Boolean) : Boolean {
        if (checkConnection && !canConnect())
            return false

        val url = serverUrlBuilder.addDeleteArchive(archiveId).build()
        val connection = createServerConnection(url, "DELETE")
        val response = httpClient.newCall(connection).tryAwait()
        return response?.use {
            if (!it.isSuccessful)
                false
            else
                it.body?.run { JSONObject(suspendString()).optInt("success", 0) } == 1
        } == true
    }

    suspend fun removeFromCategory(context: Context, categoryId: String, archiveId: String) : Boolean = withContext(Dispatchers.IO) {
        if (!canConnect())
            return@withContext false

        val url = serverUrlBuilder.addModifyCategory(categoryId, archiveId).build()
        val connection = createServerConnection(url, "DELETE")
        val response = httpClient.newCall(connection).tryAwait(context.getString(R.string.category_remove_fail_message))
        response?.use { it.isSuccessful } == true
    }

    suspend fun createCategory(context: Context, name: String, search: String? = null, pinned: Boolean = false) : JSONObject? = withContext(Dispatchers.IO) {
        if (!canConnect())
            return@withContext null

        val url = serverUrlBuilder.addCategory().build()
        val formBody = FormBody.Builder().addEncoded("name", name).apply {
            if (search != null)
                addEncoded("search", search)
        }.build()
        val connection = createServerConnection(url, "PUT", formBody)
        val response = httpClient.newCall(connection).tryAwait(context.getString(R.string.category_create_fail_message))
        response?.use {
            if (!it.isSuccessful) {
                notifyError(it.body?.run { JSONObject(suspendString()) }?.optString("error") ?: context.getString(R.string.category_create_fail_message))
                null
            } else
                it.body?.run { JSONObject(suspendString()) }
        }
    }

    suspend fun addToCategory(context: Context, categoryId: String, archiveId: String) : Boolean = withContext(Dispatchers.IO) {
        if (!canConnect())
            return@withContext false

        val url = serverUrlBuilder.addModifyCategory(categoryId, archiveId).build()
        val connection = createServerConnection(url, "PUT", FormBody.Builder().build())
        val response = httpClient.newCall(connection).tryAwait(context.getString(R.string.category_add_fail_message))
        response?.use { it.isSuccessful } == true
    }

    suspend fun addToCategory(categoryId: String, archiveIds: List<String>) : Boolean = withContext(Dispatchers.IO) {
        if (!canConnect())
            return@withContext false

        coroutineScope {
            val responses = List(archiveIds.size) { i ->
                val url = serverUrlBuilder.addModifyCategory(categoryId, archiveIds[i]).build()
                val connection = createServerConnection(url, "PUT", FormBody.Builder().build())
                async { httpClient.newCall(connection).tryAwait() }
            }

            responses.awaitAll().all { it.use { res -> res?.isSuccessful == true } }
        }
    }

    fun updateProgress(id: String, page: Int) {
        if (!canConnect() || !ServerManager.serverTracksProgress)
            return

        val url = serverUrlBuilder.addProgress(id, page).build()
        val connection = createServerConnection(url, "PUT", FormBody.Builder().build())
        httpClient.newCall(connection).enqueue()
    }

    suspend fun getOrderedArchives(start: Long = -1) = searchServer("", false, SortMethod.Alpha, false, start)

    suspend fun searchServer(search: CharSequence, onlyNew: Boolean, sortMethod: SortMethod, descending: Boolean, start: Long = 0) : InputStream? {
        if (!canConnect())
            return null

        val sort = when(sortMethod) {
            SortMethod.Alpha -> "title"
            SortMethod.Date -> "date_added"
        }
        val order = if (descending) "desc" else "asc"
        val url = serverUrlBuilder
            .addSearch()
            .addQueryParameter("filter", search)
            .addQueryParameter("newonly", onlyNew)
            .addQueryParameter("sortby", sort)
            .addQueryParameter("order", order)
            .addQueryParameter("start", start)
            .build()

        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).tryAwait()
        return if (response?.isSuccessful == true) response.body?.byteStream() else null
    }

    fun getPageList(response: JSONObject?) : List<String> {
        return when (val jsonPages = response?.optJSONArray("pages")) {
            null -> {
                response?.keys()?.next()?.let { notifyError(it) }
                emptyList()
            }
            else -> List(jsonPages.length()) { jsonPages.getString(it).trimStart('.') }
        }
    }

    suspend fun downloadThumb(id: String, page: Int): InputStream? = withContext(Dispatchers.IO) {
        if (!canConnect())
            return@withContext null

        val url = serverUrlBuilder
            .addThumb(id)
            .addQueryParameter("page", page + 1)
            .addQueryParameter("no_fallback", true)
            .build()
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).tryAwait()

        response.let {
            if (it?.isSuccessful != true || !isActive)
                null
            else {
                it.body?.byteStream()
            }
        }
    }

    fun getThumbUrl(id: String, page: Int): String {
        val localThumb = DownloadManager.getDownloadedPage(id, page)
        if (localThumb != null)
            return localThumb

        if (!canConnect())
            return ""

        return serverUrlBuilder
            .addThumb(id)
            .addQueryParameter("page", page + 1)
            .addQueryParameter("no_fallback", true)
            .build()
            .toString()
    }

    suspend fun downloadImage(serverPath: String) : InputStream? {
        val url = getRawImageUrl(serverPath)
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).tryAwait()

        if (response?.isSuccessful != true)
            return null

        return response.body?.byteStream()
    }

    fun getUrlForJob(jobId: Int) = serverUrlBuilder.addMinionStatus(jobId).build()

    private suspend fun waitForJob(jobId: Int): Boolean {
        val url = getUrlForJob(jobId)
        val connection = createServerConnection(url)

        var jobComplete: Boolean?
        do {
            delay(100)
            jobComplete = checkJobStatus(connection)
        } while (jobComplete == null)

        return jobComplete
    }

    suspend fun downloadThumb(context: Context, id: String, page: Int? = null) : InputStream? {
        if (!canConnect())
            return null

        return withContext(Dispatchers.IO) {
            val url = serverUrlBuilder.addThumb(id).build()
            if (page != null) {
                val updateUrl = url.newBuilder().addQueryParameter("page", page + 1).build()
                val connection = createServerConnection(updateUrl, "PUT", FormBody.Builder().build())
                val errorMessage = context.getString(R.string.thumb_set_fail_message)
                httpClient.newCall(connection).tryAwait(errorMessage)?.close() ?: return@withContext null
            }

            val connection = createServerConnection(url)
            val response = httpClient.newCall(connection).tryAwait()
            if (isActive && response?.isSuccessful == true) response.body?.byteStream() else null
        }
    }

    private suspend fun checkJobStatus(connection: Request): Boolean? {
        if (!canConnect())
            return false

        val response = httpClient.newCall(connection).tryAwait()
        response?.use {
            if (!it.isSuccessful || !coroutineContext.isActive)
                return false

            it.body?.run {
                val json = JSONObject(suspendString())
                return when(json.optString("state")) {
                    "finished" -> true
                    "failed" -> false
                    else -> null
                }
            }
        }

        return false
    }

    private suspend inline fun Call.tryAwait(errorMessage: String? = null) : Response? {
        return tryOrNull {
            suspendCancellableCoroutine {
                it.invokeOnCancellation { cancel() }
                enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!it.isCancelled) {
                            if (errorMessage != null)
                                handleErrorMessage(e, errorMessage)
                            it.resumeWithException(e)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        it.resume(response)
                    }
                })
            }
        }
    }

    private suspend inline fun Call.await() : Response {
        return suspendCancellableCoroutine {
            it.invokeOnCancellation { cancel() }
            enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!it.isCancelled)
                        it.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    it.resume(response)
                }
            })
        }
    }

    private fun Call.enqueue() {
        enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) { response.close() }
        })
    }

    suspend fun extractArchive(context: Context, id: String, forceFull: Boolean = false) : JSONObject? {
        if (!canConnect())
            return null

        notify(context.getString(R.string.archive_extract_message))

        val errorMessage = context.getString(R.string.archive_extract_fail_message)
        val url = serverUrlBuilder
            .addFiles(id)
            .apply {
                if (forceFull)
                    addQueryParameter("force", true)
            }.build()
        val connection = createServerConnection(url)

        val response = httpClient.newCall(connection).tryAwait(errorMessage)
        return response?.use {
            if (!it.isSuccessful) {
                handleErrorMessage(it.code, errorMessage)
                null
            } else {
                val jsonString = it.body?.suspendString()
                if (jsonString == null) {
                    notifyError(errorMessage)
                    null
                } else {
                    val json = JSONObject(jsonString)
                    if (json.has("error")) {
                        notifyError(json.getString("error"))
                        null
                    } else {
                        if (forceFull) {
                            if (json.has("job"))
                                waitForJob(json.getInt("job"))
                        }
                        json
                    }
                }
            }
        }
    }

    private suspend inline fun ResponseBody.suspendString() = withContext(Dispatchers.IO) { string() }

    fun setArchiveNewFlag(id: String) {
        if (!canConnect())
            return

        val url = serverUrlBuilder.addClearNew(id).build()
        val connection = createServerConnection(url, "DELETE")
        httpClient.newCall(connection).enqueue()
    }

    suspend fun downloadArchiveList(context: Context) : InputStream? {
        if (!canConnect())
            return null

        return withContext(Dispatchers.IO) {
            val errorMessage = context.getString(R.string.failed_to_connect_message)
            val url = serverUrlBuilder.addArchiveList().build()
            val connection = createServerConnection(url)
            val response = httpClient.newCall(connection).tryAwait(errorMessage)
            response?.let {
                if (!it.isSuccessful) {
                    handleErrorMessage(it.code, errorMessage)
                    return@withContext null
                }
                it.body?.byteStream()
            }
        }
    }

    suspend fun canReachServer() : Boolean {
        if (!canConnect())
            return false

        return withContext(Dispatchers.IO) {
            val connection = createServerConnection(serverLocation, "HEAD")
            val response = httpClient.newCall(connection).tryAwait()
            if (response == null) {
                notifyError(App.context.getString(R.string.failed_to_connect_message))
                false
            } else if (response.isSuccessful)
                true
            else {
                handleErrorMessage(response.code, App.context.getString(R.string.failed_to_connect_message))
                false
            }
        }
    }

    private fun canConnect() = serverLocation.toHttpUrlOrNull() != null

    fun getRawImageUrl(path: String) = serverLocation + path

    fun Request.Builder.addHeaders() : Request.Builder {
        if (apiKey.isNotEmpty())
            addHeader("Authorization", apiKey)

        for ((name, value) in customHeaders) {
            addHeader(name, value)
        }
        return this
    }

    private fun createServerConnection(url: String, method: String = "GET", body: RequestBody? = null) = createServerConnection(url.toHttpUrl(), method, body)

    private fun createServerConnection(url: HttpUrl, method: String = "GET", body: RequestBody? = null) : Request {
        return with (Request.Builder()) {
            method(method, body)
            addHeaders()
            url(url)
            build()
        }
    }

    private fun handleErrorMessage(responseCode: Int, defaultMessage: String) {
        notifyError(if (verboseMessages) "$defaultMessage Response Code: $responseCode" else defaultMessage)
    }

    private fun handleErrorMessage(e: Exception, defaultMessage: String) {
        notifyError(if (verboseMessages) e.localizedMessage else defaultMessage)
    }

    private fun notifyError(error: String) {
        listener?.onError(error)
    }

    private fun notify(message: String) = listener?.onInfo(message)

    fun onPreferenceChange(newValue: String?): Pair<Boolean, String> {
        if (newValue !is String || newValue.isEmpty())
            return Pair(true, "")

        var v = newValue.trimEnd('/')
        if (serverLocation == v)
            return Pair(false, serverLocation)

        if (!v.startsWith("http") && !v.startsWith("https")) {
            //assume http if not present
            v = "http://$v"
        }

        if (v.toHttpUrlOrNull() != null) {
            DatabaseReader.setDatabaseDirty()
            serverLocation = v
        } else {
            notifyError("Invalid URL!")
            return Pair(false, serverLocation)
        }

        return Pair(true, serverLocation)
    }

}
