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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import androidx.preference.Preference
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
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

object WebHandler : Preference.OnPreferenceChangeListener {
    private const val apiPath: String = "/api"
    private const val databasePath = "$apiPath/database"
    private const val archiveListPath = "$apiPath/archives"
    private const val thumbPath = "$archiveListPath/%s/thumbnail"
    private const val extractPath = "$archiveListPath/%s/extract"
    private const val filesPath = "$archiveListPath/%s/files"
    private const val progressPath = "$archiveListPath/%s/progress/%s"
    private const val deleteArchivePath = "$archiveListPath/%s"
    private const val tagsPath = "$databasePath/stats"
    private const val clearNewPath = "$archiveListPath/%s/isnew"
    private const val searchPath = "$apiPath/search"
    private const val randomPath = "$searchPath/random"
    private const val infoPath = "$apiPath/info"
    private const val categoryPath = "$apiPath/categories"
    private const val clearTempPath = "$apiPath/tempfolder"
    private const val modifyCatPath = "$categoryPath/%s/%s"
    private const val minionStatusPath = "$apiPath/minion/%s"
    private const val connTimeoutMs = 5000L
    private const val readTimeoutMs = 30000L

    var serverLocation: String = ""
    var apiKey: String = ""
    private val urlRegex by lazy { Regex("^(https?://|www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*(:\\d+)?([/?].*)?\$") }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(connTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .dispatcher(Dispatcher().apply { maxRequests = 20 })
        .build()

    var verboseMessages = false
    var listener: DatabaseMessageListener? = null
    private val refreshListeners = mutableListOf<DatabaseRefreshListener>()
    var connectivityManager: ConnectivityManager? = null
    val encodedKey
        get() = "Bearer ${Base64.encodeToString(apiKey.toByteArray(), Base64.NO_WRAP)}"

    suspend fun getServerInfo(context: Context) : JSONObject? {
        if (!canConnect(context))
            return null

        updateRefreshing(true)
        val errorMessage = context.getString(R.string.failed_to_connect_message)
        val url = "$serverLocation$infoPath"
        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail(errorMessage) }
        response?.use {
            if (!it.isSuccessful) {
                handleErrorMessage(it.code, errorMessage)
                updateRefreshing(false)
                return null
            }

            val json = it.body?.run { JSONObject(suspendString()) }
            updateRefreshing(false)
            return json
        }

        updateRefreshing(false)
        return null
    }

    fun registerRefreshListener(listener: DatabaseRefreshListener) = refreshListeners.add(listener)
    fun unregisterRefreshListener(listener: DatabaseRefreshListener) = refreshListeners.remove(listener)
    fun updateRefreshing(refreshing: Boolean) {
        for (listener in refreshListeners)
            listener.isRefreshing(refreshing)
    }

    suspend fun clearTempFolder(context: Context) {
        if (!canConnect(context))
            return

        val errorMessage = context.getString(R.string.temp_clear_fail_message)
        val url = "$serverLocation$clearTempPath"
        val connection = createServerConnection(url, "DELETE")
        val response = httpClient.newCall(connection).await(errorMessage)
        response.use {
            if (it.isSuccessful)
                notify(context.getString(R.string.temp_clear_success_message))
            else
                handleErrorMessage(it.code, errorMessage)
        }
    }

    suspend fun generateSuggestionList() : JSONArray? {
        if (!canConnect())
            return null

        val url = "$serverLocation$tagsPath"
        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail() }
        return response?.use {
            if (!it.isSuccessful)
                null
            else
                tryOrNull { it.body?.run { parseJsonArray(suspendString()) } }
        }
    }

    suspend fun getCategories() : JSONArray? {
        if (!canConnect())
            return null

        val url = "$serverLocation$categoryPath"
        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail() }
        return response?.use {
            if (!it.isSuccessful)
                null
            else
                it.body?.run { parseJsonArray(suspendString()) }
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

    suspend fun deleteArchive(archiveId: String) = deleteArchive(archiveId, true)

    private suspend fun deleteArchive(archiveId: String, checkConnection: Boolean) : Boolean {
        if (checkConnection && !canConnect())
            return false

        val url = "$serverLocation${deleteArchivePath.format(archiveId)}"
        val connection = createServerConnection(url, "DELETE")
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail() }
        return response?.use {
            if (!it.isSuccessful)
                false
            else
                it.body?.run { JSONObject(suspendString()).optInt("success", 0) } == 1
        } ?: false
    }

    suspend fun removeFromCategory(context: Context, categoryId: String, archiveId: String) : Boolean {
        if (!canConnect(context, false))
            return false

        val url = "$serverLocation${modifyCatPath.format(categoryId, archiveId)}"
        val connection = createServerConnection(url, "DELETE")
        val response = httpClient.newCall(connection).await(context.getString(R.string.category_remove_fail_message))
        return response.use { it.isSuccessful }
    }

    suspend fun createCategory(context: Context, name: String, search: String? = null, pinned: Boolean = false) : JSONObject? {
        if (!canConnect())
            return null

        val url = "$serverLocation$categoryPath"
        val builder = FormBody.Builder().addEncoded("name", name)
        if (search != null)
            builder.addEncoded("search", search)
        val formBody = builder.build()
        val connection = createServerConnection(url, "PUT", formBody)
        val response = httpClient.newCall(connection).await(context.getString(R.string.category_create_fail_message))
        response.use {
            if (!it.isSuccessful) {
                notifyError(it.body?.run { JSONObject(suspendString()) }?.optString("error") ?: context.getString(R.string.category_create_fail_message))
                return null
            }

            return it.body?.run { JSONObject(suspendString()) }
        }
    }

    suspend fun addToCategory(context: Context, categoryId: String, archiveId: String) : Boolean {
        if (!canConnect(context))
            return false

        val url = "$serverLocation${modifyCatPath.format(categoryId, archiveId)}"
        val connection = createServerConnection(url, "PUT", FormBody.Builder().build())
        val response = httpClient.newCall(connection).await(context.getString(R.string.category_add_fail_message))
        return response.use { it.isSuccessful }
    }

    suspend fun addToCategory(context: Context, categoryId: String, archiveIds: List<String>) : Boolean {
        if (!canConnect(context))
            return false

        return coroutineScope {
            val responses = List(archiveIds.size) { i ->
                val url = "$serverLocation${modifyCatPath.format(categoryId, archiveIds[i])}"
                val connection = createServerConnection(url, "PUT", FormBody.Builder().build())
                async { httpClient.newCall(connection).await() }
            }

            responses.awaitAll().all { it.use { res -> res.isSuccessful } }
        }
    }

    suspend fun updateProgress(id: String, page: Int) {
        if (!canConnect() || !ServerManager.serverTracksProgress)
            return

        val url = "$serverLocation${progressPath.format(id, page + 1)}"
        val connection = createServerConnection(url, "PUT", FormBody.Builder().build())
        httpClient.newCall(connection).await(autoClose = true)
    }

    fun searchServer(search: CharSequence, onlyNew: Boolean, sortMethod: SortMethod, descending: Boolean, start: Int = 0, showRefresh: Boolean = true) : ServerSearchResult {
        if (search.isBlank() && !onlyNew)
            return ServerSearchResult(null)

        if (showRefresh)
            updateRefreshing(true)

        val jsonResults = runBlocking { internalSearchServer(search, onlyNew, sortMethod, descending, start) }
        if (jsonResults == null) {
            if (showRefresh)
                updateRefreshing(false)
            return ServerSearchResult(null)
        }

        val totalResults = jsonResults.getInt("recordsFiltered")

        val dataArray = jsonResults.getJSONArray("data")
        val results = List(dataArray.length()) { dataArray.getJSONObject(it).getString("arcid") }

        if (showRefresh)
            updateRefreshing(false)

        return ServerSearchResult(results, totalResults, search, onlyNew)
    }

    suspend fun getRandomArchives(count: UInt, filter: CharSequence? = null, categoryId: String? = null) : ServerSearchResult {
        if (!canConnect())
            return ServerSearchResult(null)

        updateRefreshing(true)

        val encodedSearch = if (filter == null) null else withContext(Dispatchers.IO) { URLEncoder.encode(filter.toString(), "utf-8") }
        val url = "$serverLocation$randomPath?count=$count${if (encodedSearch == null) "" else "&filter=$encodedSearch"}${if (categoryId == null) "" else "&category=$categoryId"}"
        val idKey = if (ServerManager.checkVersionAtLeast(0, 8, 8)) "arcid" else "id"

        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail() }
        val result = tryOrNull { response?.use {
            if (!it.isSuccessful)
                ServerSearchResult(null)
            else {
                it.body?.run { JSONObject(suspendString()) }?.let { json ->
                    val dataArray = json.getJSONArray("data")
                    val results = List(dataArray.length()) { i -> dataArray.getJSONObject(i).getString(idKey) }
                    ServerSearchResult(results, results.size)
                }
            }
        } } ?: ServerSearchResult(null)

        updateRefreshing(false)
        return result
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun internalSearchServer(search: CharSequence, onlyNew: Boolean, sortMethod: SortMethod, descending: Boolean, start: Int = 0) : JSONObject? {
        if (!canConnect())
            return null

        val encodedSearch =  withContext(Dispatchers.IO) { URLEncoder.encode(search.toString(), "utf-8") }
        val sort = when(sortMethod) {
            SortMethod.Alpha -> "title"
            SortMethod.Date -> "date_added"
        }
        val order = if (descending) "desc" else "asc"
        val url = "$serverLocation$searchPath?filter=$encodedSearch&newonly=$onlyNew&sortby=$sort&order=$order&start=$start"

        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail() }
        return response?.use {
            if (!it.isSuccessful)
                null
            else
                it.body?.run { JSONObject(suspendString()) }
        }
    }

    fun getPageList(response: JSONObject?) : List<String> {
        return when (val jsonPages = response?.optJSONArray("pages")) {
            null -> {
                response?.keys()?.next()?.let { notifyError(it) }
                emptyList()
            }
            else -> List(jsonPages.length()) { jsonPages.getString(it).substring(1) }
        }
    }

    suspend fun downloadThumb(id: String, page: Int): String? {
        if (!canConnect() || !ServerManager.checkVersionAtLeast(0, 8, 4))
            return null

        val url = "$serverLocation${thumbPath.format(id)}?page=${page + 1}&no_fallback=true"
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).await()
        return response.use {
            when {
                it.code == HttpURLConnection.HTTP_OK -> url
                //The minion api is protected before v0.8.5, so return null if the thumbnail hasn't been generated yet
                it.code == HttpURLConnection.HTTP_ACCEPTED && !ServerManager.canEdit && !ServerManager.checkVersionAtLeast(0, 8, 5) -> null
                it.code == HttpURLConnection.HTTP_ACCEPTED -> {
                    it.body?.run {
                        val json = JSONObject(suspendString())
                        val job = json.getInt("job")
                        if (waitForJob(job)) url else null
                    }
                }
                else -> null
            }
        }
    }

    private suspend fun waitForJob(jobId: Int): Boolean {
        var jobComplete: Boolean?
        do {
            delay(500)
            jobComplete = checkJobStatus(jobId)
        } while (jobComplete == null)

        return jobComplete
    }

    suspend fun downloadThumb(context: Context, id: String, thumbDir: File, page: Int? = null) : File? {
        if (!canConnect(context, page == null))
            return null

        val url = "$serverLocation${thumbPath.format(id)}"
        if (page != null) {
            val updateUrl = url + "?page=${page + 1}"
            val connection = createServerConnection(updateUrl, "PUT", FormBody.Builder().build())
            val errorMessage = context.getString(R.string.thumb_set_fail_message)
            tryOrNull { httpClient.newCall(connection).awaitWithFail(errorMessage) }?.close() ?: return null
        }

        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail() } ?: return null
        return response.use {
            if (!it.isSuccessful)
                null
            else {
                it.body?.run {
                    val thumbFile = File(thumbDir, "$id.jpg")
                    thumbFile.writeBytes(suspendBytes())
                    thumbFile
                }
            }
        }
    }

    private suspend fun checkJobStatus(jobId: Int): Boolean? {
        if (!canConnect())
            return false

        val url = "$serverLocation${minionStatusPath.format(jobId)}"
        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail() }
        response?.use {
            if (!it.isSuccessful)
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

    private suspend fun Call.awaitWithFail(errorMessage: String? = null) : Response {
        return suspendCancellableCoroutine {
            enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) {
                    it.resumeWithException(e)
                    if (errorMessage != null)
                        handleErrorMessage(e, errorMessage)
                }

                override fun onResponse(call: Call, response: Response) {
                    it.invokeOnCancellation { response.close() }
                    it.resume(response)
                }
            })
        }
    }

    private suspend fun Call.await(errorMessage: String? = null, autoClose: Boolean = false) : Response {
        return suspendCancellableCoroutine {
            enqueue(object: Callback{
                override fun onFailure(call: Call, e: IOException) {
                    it.cancel()
                    if (errorMessage != null)
                        handleErrorMessage(e, errorMessage)
                }

                override fun onResponse(call: Call, response: Response) {
                    it.invokeOnCancellation { response.close() }
                    if (autoClose)
                        response.use { res -> it.resume(res) }
                    else
                        it.resume(response)
                }
            })
        }
    }

    private fun parseJsonArray(input: String) : JSONArray? {
        return try {
            JSONArray(input)
        } catch (e: JSONException) {
            null
        }
    }

    suspend fun extractArchive(context: Context, id: String, forceFull: Boolean = false) : JSONObject? {
        if (!canConnect(context))
            return null

        notify(context.getString(R.string.archive_extract_message))

        val errorMessage = context.getString(R.string.archive_extract_fail_message)
        val connection = if (ServerManager.checkVersionAtLeast(0, 8, 2)) {
            var url = "$serverLocation${extractPath.format(id)}"
            if (forceFull)
                url += "?force=true"
            createServerConnection(url, "POST", FormBody.Builder().build())
        } else {
            val url = "$serverLocation${filesPath.format(id)}"
            createServerConnection(url)
        }

        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail(errorMessage) }
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

    private suspend fun ResponseBody.suspendString() = withContext(Dispatchers.IO) { string() }

    private suspend fun ResponseBody.suspendBytes() = withContext(Dispatchers.IO) { bytes() }

    suspend fun setArchiveNewFlag(id: String) {
        if (!canConnect())
            return

        val url = "$serverLocation${clearNewPath.format(id)}"
        val connection = createServerConnection(url, "DELETE")
        httpClient.newCall(connection).await(autoClose = true)
    }

    suspend fun downloadArchiveList(context: Context) : JSONArray? {
        if (!canConnect(context))
            return null

        val errorMessage = context.getString(R.string.failed_to_connect_message)
        val url = "$serverLocation$archiveListPath"
        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail(errorMessage) }
        return response?.use {
            if (!it.isSuccessful) {
                handleErrorMessage(it.code, errorMessage)
                return null
            }

            val jsonString = it.body?.suspendString()
            if (jsonString != null) {
                val json = parseJsonArray(jsonString)
                if (json == null)
                    notifyError(JSONObject(jsonString).getString("error")) //Invalid api key

                return json
            }

            notifyError(context.getString(R.string.get_archives_fail_message))
            null
        }
    }

    private fun canConnect() = canConnect(null, true)

    private fun canConnect(context: Context) = canConnect(context, false)

    @Suppress("DEPRECATION")
    private fun canConnect(context: Context?, silent: Boolean) : Boolean {
        if (serverLocation.isEmpty())
            return false

        val connected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
           connectivityManager?.let {
               it.getNetworkCapabilities(it.activeNetwork)?.let { active ->
                   (active.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                           || active.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                           || active.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
               }
           } ?: false
        } else
            connectivityManager?.activeNetworkInfo?.isConnected != true

        if (!connected && !silent) {
            context?.run { notifyError(getString(R.string.no_net_connection)) }
            return false
        }

        return connected
    }

    fun getRawImageUrl(path: String) = serverLocation + path

    private fun createServerConnection(url: String, method: String = "GET", body: RequestBody? = null) : Request {
        return with (Request.Builder()) {
            method(method, body)
            url(url)
            if (apiKey.isNotEmpty())
                addHeader("Authorization", encodedKey)
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

    override fun onPreferenceChange(pref: Preference, newValue: Any?): Boolean {
        if (newValue !is String || newValue.isEmpty())
            return true

        if (serverLocation == newValue)
            return true

        if (urlRegex.matches(newValue)) {
            DatabaseReader.setDatabaseDirty()
            if (newValue.startsWith("http") || newValue.startsWith("https")) {
                serverLocation = newValue
                return true
            }

            //assume http if not present
            serverLocation = "http://$newValue"
            pref.summary = serverLocation
            pref.sharedPreferences?.edit()?.putString(pref.key, serverLocation)?.apply()
            return false
        } else {
            notifyError("Invalid URL!")
            return false
        }
    }

}
