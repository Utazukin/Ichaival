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

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.preference.Preference
import android.util.Base64
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
    private const val timeout = 5000L //ms

    var serverLocation: String = ""
    var apiKey: String = ""
    private val urlRegex by lazy { Regex("^(https?://|www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*(:\\d+)?([/?].*)?\$") }
    private val httpClient = OkHttpClient.Builder().connectTimeout(timeout, TimeUnit.MILLISECONDS).build()

    var verboseMessages = false
    var listener: DatabaseMessageListener? = null
    var refreshListener: DatabaseRefreshListener? = null
    var connectivityManager: ConnectivityManager? = null

    suspend fun getServerInfo() : JSONObject? {
        if (!canConnect())
            return null

        refreshListener?.isRefreshing(true)
        val url = "$serverLocation$infoPath"
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).await()
        with (response) {
            if (!isSuccessful) {
                handleErrorMessage(code, "Failed to connect to server!")
                return null
            }

            val json = body?.let { JSONObject(it.suspendString()) }
            refreshListener?.isRefreshing(false)
            return json
        }
    }

    suspend fun clearTempFolder() {
        if (!canConnect())
            return

        val url = "$serverLocation$clearTempPath"
        val connection = createServerConnection(url, "DELETE")
        val response = httpClient.newCall(connection).await()
        with (response) {
            if (isSuccessful)
                notify("Temp folder cleared.")
            else
                handleErrorMessage(code, "Failed to clear temp folder.")
        }
    }

    suspend fun generateSuggestionList() : JSONArray? {
        if (!canConnect(true))
            return null

        val url = "$serverLocation$tagsPath"
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).await()
        with (response) {
            if (!isSuccessful)
                return null

            return body?.let { parseJsonArray(it.suspendString()) }
        }
    }

    suspend fun getCategories() : JSONArray? {
        if (!canConnect(true))
            return null

        val url = "$serverLocation$categoryPath"
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).await()
        with (response) {
            if (!isSuccessful)
                return null

            return body?.let { parseJsonArray(it.suspendString()) }
        }
    }

    fun searchServer(search: CharSequence, onlyNew: Boolean, sortMethod: SortMethod, descending: Boolean, start: Int = 0, showRefresh: Boolean = true) : ServerSearchResult {
        if (search.isBlank() && !onlyNew)
            return ServerSearchResult(null)

        if (showRefresh)
            refreshListener?.isRefreshing(true)

        val jsonResults = runBlocking { internalSearchServer(search, onlyNew, sortMethod, descending, start) } ?: return ServerSearchResult(null)
        val totalResults = jsonResults.getInt("recordsFiltered")

        val dataArray = jsonResults.getJSONArray("data")
        val results = mutableListOf<String>()
        for (i in 0 until dataArray.length()) {
            val id = dataArray.getJSONObject(i).getString("arcid")
            results.add(id)
        }

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
        val response = httpClient.newCall(connection).await()
        with (response) {
            if (!isSuccessful)
                return null

            return body?.let { JSONObject(it.suspendString()) }
        }
    }

    fun getPageList(response: JSONObject?) : List<String> {
        val jsonPages = response?.optJSONArray("pages")
        if (jsonPages == null) {
            notifyError(response?.keys()?.next() ?: "Failed to extract archive")
            return listOf()
        }

        val count = jsonPages.length()
        return MutableList(count) { jsonPages.getString(it).substring(1) }
    }

    suspend fun downloadThumb(id: String, thumbDir: File) : File? {
        val url = "$serverLocation${thumbPath.format(id)}"

        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).await()
        with (response) {
            if (!isSuccessful) {
                handleErrorMessage(code, "Failed to download thumbnail!")
                return null
            }

            return body?.let {
                val thumbFile = File(thumbDir, "$id.jpg")
                thumbFile.writeBytes(it.suspendBytes())
                thumbFile
            }
        }
    }

    private suspend fun Call.await() : Response {
        return suspendCancellableCoroutine {
            enqueue(object: Callback{
                override fun onFailure(call: Call, e: IOException) {
                    it.cancel(e)
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

        notifyExtract(id)

        val url = "$serverLocation${extractPath.format(id)}"
        val connection = createServerConnection(url, "POST", FormBody.Builder().build())
        val response = httpClient.newCall(connection).await()
        with (response) {
            if (!isSuccessful) {
                handleErrorMessage(code, "Failed to extract archive!")
                return null
            }

            val jsonString = body?.suspendString()
            return if (jsonString == null) {
                notifyError("Failed to extract archive!")
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

        val url = "$serverLocation$archiveListPath"
        val connection = createServerConnection(url)
        val response = httpClient.newCall(connection).await()
        with (response) {
            if (!isSuccessful) {
                handleErrorMessage(code, "Failed to connect to server!")
                return null
            }

            val jsonString = body?.suspendString()
            if (jsonString != null) {
                val json = parseJsonArray(jsonString)
                if (json == null)
                    notifyError(JSONObject(jsonString).getString("error")) //Invalid api key

                return json
            }

            notifyError("Error get archive list")
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

        return true
    }

    fun getRawImageUrl(path: String) = serverLocation + path

    private fun createServerConnection(url: String, method: String = "GET", body: RequestBody? = null) : Request {
        return Request.Builder().run {
            method(method, body)
            url(url)
            if (apiKey.isNotEmpty())
                header("Authorization", "Bearer ${Base64.encodeToString(apiKey.toByteArray(), Base64.URL_SAFE)}")
            build()
        }
    }

    private fun notifyExtract(id: String) {
        val title = DatabaseReader.database.archiveDao().getArchiveTitle(id)
        if (title != null)
            listener?.onExtract(title)
    }

    private fun handleErrorMessage(responseCode: Int, defaultMessage: String) {
        notifyError(if (verboseMessages) "$defaultMessage Response Code: $responseCode" else defaultMessage)
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
            pref.editor.putString(pref.key, serverLocation).apply()
            pref.summary = serverLocation
            return false
        } else {
            notifyError("Invalid URL!")
            return false
        }
    }

}