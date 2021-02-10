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

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TagSuggestion(tagText: String, namespaceText: String, val weight: Int) {
    private val tag = tagText.toLowerCase(Locale.getDefault())
    private val namespace = namespaceText.toLowerCase(Locale.getDefault())
    val displayTag = if (namespace.isNotBlank()) "$namespace:$tag" else tag

    fun contains(query: String) : Boolean {
        return if (!query.contains(":"))
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
    private const val tagsPath = "$databasePath/stats"
    private const val clearNewPath = "$archiveListPath/%s/isnew"
    private const val searchPath = "$apiPath/search"
    private const val infoPath = "$apiPath/info"
    private const val categoryPath = "$apiPath/categories"
    private const val clearTempPath = "$apiPath/tempfolder"
    private const val connTimeoutMs = 5000L
    private const val readTimeoutMs = 30000L

    var serverLocation: String = ""
    var apiKey: String = ""
    private val urlRegex by lazy { Regex("^(https?://|www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*(:\\d+)?([/?].*)?\$") }
    private val httpClient = OkHttpClient.Builder().connectTimeout(connTimeoutMs, TimeUnit.MILLISECONDS).readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS).build()

    var verboseMessages = false
    var listener: DatabaseMessageListener? = null
    var refreshListener: DatabaseRefreshListener? = null
    var connectivityManager: ConnectivityManager? = null
    val encodedKey
        get() = "Bearer ${Base64.encodeToString(apiKey.toByteArray(), Base64.NO_WRAP)}"

    suspend fun getServerInfo() : JSONObject? {
        if (!canConnect())
            return null

        refreshListener?.isRefreshing(true)
        val errorMessage = "Failed to connect to server!"
        val url = "$serverLocation$infoPath"
        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail(errorMessage) }
        response?.run {
            if (!isSuccessful) {
                handleErrorMessage(code, errorMessage)
                refreshListener?.isRefreshing(false)
                return null
            }

            val json = body?.let { JSONObject(it.suspendString()) }
            refreshListener?.isRefreshing(false)
            return json
        }

        refreshListener?.isRefreshing(false)
        return null
    }

    suspend fun clearTempFolder() {
        if (!canConnect())
            return

        val errorMessage = "Failed to clear temp folder."
        val url = "$serverLocation$clearTempPath"
        val connection = createServerConnection(url, "DELETE")
        val response = httpClient.newCall(connection).await(errorMessage)
        with (response) {
            if (isSuccessful)
                notify("Temp folder cleared.")
            else
                handleErrorMessage(code, errorMessage)
        }
    }

    suspend fun generateSuggestionList() : JSONArray? {
        if (!canConnect(true))
            return null

        val url = "$serverLocation$tagsPath"
        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail() }
        response?.run {
            if (!isSuccessful)
                return null

            return body?.let { parseJsonArray(it.suspendString()) }
        }

        return null
    }

    suspend fun getCategories() : JSONArray? {
        if (!canConnect(true))
            return null

        val url = "$serverLocation$categoryPath"
        val connection = createServerConnection(url)
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail() }
        response?.run {
            if (!isSuccessful)
                return null

            return body?.let { parseJsonArray(it.suspendString()) }
        }

        return null
    }

    fun searchServer(search: CharSequence, onlyNew: Boolean, sortMethod: SortMethod, descending: Boolean, start: Int = 0, showRefresh: Boolean = true) : ServerSearchResult {
        if (search.isBlank() && !onlyNew)
            return ServerSearchResult(null)

        if (showRefresh)
            refreshListener?.isRefreshing(true)

        val jsonResults = runBlocking { internalSearchServer(search, onlyNew, sortMethod, descending, start) }
        if (jsonResults == null) {
            if (showRefresh)
                refreshListener?.isRefreshing(false)
            return ServerSearchResult(null)
        }

        val totalResults = jsonResults.getInt("recordsFiltered")

        val dataArray = jsonResults.getJSONArray("data")
        val results = MutableList(dataArray.length()) { dataArray.getJSONObject(it).getString("arcid") }

        if (showRefresh)
            refreshListener?.isRefreshing(false)

        return ServerSearchResult(results, totalResults, search, onlyNew)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun internalSearchServer(search: CharSequence, onlyNew: Boolean, sortMethod: SortMethod, descending: Boolean, start: Int = 0) : JSONObject? {
        if (!canConnect(true))
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
        return response?.run {
            if (!isSuccessful)
                return null

            body?.let { JSONObject(it.suspendString()) }
        }
    }

    fun getPageList(response: JSONObject?) : List<String> {
        val jsonPages = response?.optJSONArray("pages")
        if (jsonPages == null) {
            response?.keys()?.next()?.let { notifyError(it) }
            return listOf()
        }

        val count = jsonPages.length()
        return MutableList(count) { jsonPages.getString(it).substring(1) }
    }

    suspend fun downloadThumb(id: String, thumbDir: File) : File? {
        if (!canConnect(true))
            return null

        val url = "$serverLocation${thumbPath.format(id)}"

        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).await()
        with (response) {
            if (!isSuccessful) {
                return null
            }

            return body?.let {
                val thumbFile = File(thumbDir, "$id.jpg")
                thumbFile.writeBytes(it.suspendBytes())
                thumbFile
            }
        }
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
                    it.resume(response)
                }
            })
        }
    }

    private suspend fun Call.await(errorMessage: String? = null) : Response {
        return suspendCancellableCoroutine {
            enqueue(object: Callback{
                override fun onFailure(call: Call, e: IOException) {
                    it.cancel()
                    if (errorMessage != null)
                        handleErrorMessage(e, errorMessage)
                }

                override fun onResponse(call: Call, response: Response) {
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


    suspend fun extractArchive(id: String) : JSONObject? {
        if (!canConnect())
            return null

        notify("Extracting...")

        val errorMessage = "Failed to extract archive!"
        val url = "$serverLocation${extractPath.format(id)}"
        val connection = createServerConnection(url, "POST", FormBody.Builder().build())
        val response = tryOrNull { httpClient.newCall(connection).awaitWithFail(errorMessage) }
        return response?.run {
            if (!isSuccessful) {
                handleErrorMessage(code, errorMessage)
                null
            } else {
                val jsonString = body?.suspendString()
                if (jsonString == null) {
                    notifyError(errorMessage)
                    null
                } else {
                    val json = JSONObject(jsonString)
                    if (json.has("error")) {
                        notifyError(json.getString("error"))
                        null
                    } else
                        json
                }
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun ResponseBody.suspendString() = withContext(Dispatchers.IO) { string() }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun ResponseBody.suspendBytes() = withContext(Dispatchers.IO) { bytes() }

    suspend fun setArchiveNewFlag(id: String) {
        if (!canConnect(true))
            return

        val url = "$serverLocation${clearNewPath.format(id)}"
        val connection = createServerConnection(url, "DELETE")
        httpClient.newCall(connection).await()
    }

    suspend fun downloadArchiveList() : JSONArray? {
        if (!canConnect())
            return null

        val errorMessage = "Failed to connect to server!"
        val url = "$serverLocation$archiveListPath"
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).await(errorMessage)
        with (response) {
            if (!isSuccessful) {
                handleErrorMessage(code, errorMessage)
                return null
            }

            val jsonString = body?.suspendString()
            if (jsonString != null) {
                val json = parseJsonArray(jsonString)
                if (json == null)
                    notifyError(JSONObject(jsonString).getString("error")) //Invalid api key

                return json
            }

            notifyError("Error getting archive list")
            return null
        }
    }


    @Suppress("DEPRECATION")
    private fun canConnect(silent: Boolean = false) : Boolean {
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
            notifyError("No network connection!")
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
        if ((newValue as String).isEmpty())
            return false

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
            pref.sharedPreferences.edit().putString(pref.key, serverLocation).apply()
            return false
        } else {
            notifyError("Invalid URL!")
            return false
        }
    }

}
