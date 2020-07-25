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
import android.preference.Preference
import android.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.*

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
    private const val timeout = 5000 //ms

    var serverLocation: String = ""
    var apiKey: String = ""
    private val urlRegex by lazy { Regex("^(https?://|www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*(:\\d+)?([/?].*)?\$") }

    var verboseMessages = false
    var listener: DatabaseMessageListener? = null
    var refreshListener: DatabaseRefreshListener? = null
    var connectivityManager: ConnectivityManager? = null

    fun getServerInfo() : JSONObject? {
        if (!canConnect())
            return null

        refreshListener?.isRefreshing(true)
        val url = "$serverLocation$infoPath"
        val connection = createServerConnection(url)
        try {
            with(connection) {
                if (responseCode != HttpURLConnection.HTTP_OK) {
                        handleErrorMessage(responseCode, "Failed to connect to server!")
                    return null
                }

                val decoder = Charset.forName("utf-8").newDecoder()
                BufferedReader(InputStreamReader(inputStream, decoder)).use {
                    val response = StringBuffer()

                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }

                    val jsonString = response.toString()
                    return JSONObject(jsonString)
                }
            }
        } catch (e: Exception) {
            handleErrorMessage(e, "Failed to connect to server!")
            return null
        } finally {
            connection.disconnect()
            refreshListener?.isRefreshing(false)
        }
    }

    fun clearTempFolder() {
        if (!canConnect())
            return

        val url = "$serverLocation$clearTempPath"
        val connection = createServerConnection(url)
        try {
            with(connection) {
                requestMethod = "DELETE"
                if (responseCode == HttpURLConnection.HTTP_OK)
                    notify("Temp folder cleared")
                else
                    handleErrorMessage(responseCode, "Failed to clear temp folder.")
            }
        } catch (e: Exception) {
            handleErrorMessage(e, "Failed to clear temp folder.")
        } finally {
            connection.disconnect()
        }
    }

    fun generateSuggestionList() : JSONArray? {
        if (!canConnect(true))
            return null

        val url = "$serverLocation$tagsPath"
        val connection = createServerConnection(url)
        try {
            with(connection) {
                if (responseCode != HttpURLConnection.HTTP_OK)
                    return null

                BufferedReader(InputStreamReader(inputStream)).use {
                    val response = StringBuffer()

                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }
                    return parseJsonArray(response.toString())
                }
            }
        }
        catch(e: Exception) {
            return null
        }
        finally {
            connection.disconnect()
        }
    }

    fun getCategories() : JSONArray? {
        if (!canConnect(true))
            return null

        val url = "$serverLocation$categoryPath"
        val connection = createServerConnection(url)
        try {
            with(connection) {
                if (responseCode != HttpURLConnection.HTTP_OK)
                    return null

                BufferedReader(InputStreamReader(inputStream)).use {
                    val response = StringBuffer()

                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }
                    return parseJsonArray(response.toString())
                }
            }
        }
        catch (e: java.lang.Exception) {
            return null
        }
        finally {
            connection.disconnect()
        }
    }

    fun searchServer(search: CharSequence, onlyNew: Boolean, sortMethod: SortMethod, descending: Boolean, start: Int = 0, showRefresh: Boolean = true) : ServerSearchResult {
        if (search.isBlank() && !onlyNew)
            return ServerSearchResult(null)

        if (showRefresh)
            refreshListener?.isRefreshing(true)

        val jsonResults = internalSearchServer(search, onlyNew, sortMethod, descending, start) ?: return ServerSearchResult(null)
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

    private fun internalSearchServer(search: CharSequence, onlyNew: Boolean, sortMethod: SortMethod, descending: Boolean, start: Int = 0) : JSONObject? {
        if (!canConnect(true))
            return null

        val encodedSearch =  URLEncoder.encode(search.toString(), "utf-8")
        val sort = when(sortMethod) {
            SortMethod.Alpha -> "title"
            SortMethod.Date -> "date_added"
        }
        val order = if (descending) "desc" else "asc"
        val url = "$serverLocation$searchPath?filter=$encodedSearch&newonly=$onlyNew&sortby=$sort&order=$order&start=$start"

        val connection = createServerConnection(url)
        try {
            with (connection) {
                connectTimeout = timeout
                if (responseCode != HttpURLConnection.HTTP_OK)
                    return null

                BufferedReader(InputStreamReader(inputStream)).use {
                    val response = StringBuffer()
                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }
                    return JSONObject(response.toString())
                }
            }
        }
        catch (e: Exception) {}
        finally {
            connection.disconnect()
        }

        return null
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

    fun downloadThumb(id: String, thumbDir: File) : File? {
        val url = "$serverLocation${thumbPath.format(id)}"

        val connection = createServerConnection(url)
        try {
            with(connection) {
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    handleErrorMessage(responseCode, "Failed to download thumbnail!")
                    return null
                }

                BufferedReader(InputStreamReader(inputStream)).use {
                    val bytes = inputStream.readBytes()

                    val thumbFile = File(thumbDir, "$id.jpg")
                    thumbFile.writeBytes(bytes)
                    return thumbFile
                }
            }
        }
        catch(e: Exception) {
            handleErrorMessage(e, "Failed to download thumbnail!")
            return null
        }
        finally {
            connection.disconnect()
        }
    }

    private fun parseJsonArray(input: String) : JSONArray? {
        return try {
            JSONArray(input)
        } catch (e: JSONException) {
            null
        }
    }


    fun extractArchive(id: String) : JSONObject? {
        if (!canConnect())
            return null

        notifyExtract(id)

        val url = "$serverLocation${extractPath.format(id)}"
        val connection = createServerConnection(url)
        try {
            with(connection) {
                requestMethod = "POST"
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    handleErrorMessage(responseCode, "Failed to extract archive!")
                    return null
                }

                BufferedReader(InputStreamReader(inputStream)).use {
                    val response = StringBuffer()

                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }
                    val json = JSONObject(response.toString())
                    if (!json.has("error"))
                        return json
                    else{
                        notifyError(json.getString("error"))
                        return null
                    }
                }
            }
        } catch (e: Exception) {
            handleErrorMessage(e, "Failed to extract archive!")
            return null
        }
        finally {
            connection.disconnect()
        }
    }

    fun setArchiveNewFlag(id: String, isNew: Boolean) {
        DatabaseReader.database.archiveDao().updateNewFlag(id, isNew)

        if (!canConnect(true))
            return

        val url = "$serverLocation${clearNewPath.format(id)}"
        val connection = createServerConnection(url)
        try {
            with(connection) {
                if (responseCode != HttpURLConnection.HTTP_OK)
                    return
            }
        }
        catch (e: Exception) {}
        finally {
            connection.disconnect()
        }
    }

    fun downloadArchiveList() : JSONArray? {
        if (!canConnect())
            return null

        val url = "$serverLocation$archiveListPath"
        val connection = createServerConnection(url)
        try {
            with(connection) {
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    handleErrorMessage(responseCode, "Failed to connect to server!")
                    return null
                }

                val decoder = Charset.forName("utf-8").newDecoder()
                BufferedReader(InputStreamReader(inputStream, decoder)).use {
                    val response = StringBuffer()

                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }

                    val jsonString = response.toString()
                    val json =  parseJsonArray(jsonString)
                    if (json == null)
                        notifyError(JSONObject(jsonString).getString("error")) //Invalid api key
                    return json
                }
            }
        } catch (e: Exception) {
            handleErrorMessage(e, "Failed to connect to server!")
            return null
        } finally {
            connection.disconnect()
        }
    }


    private fun canConnect(silent: Boolean = false) : Boolean {
        if (serverLocation.isEmpty())
            return false

        if (connectivityManager?.activeNetworkInfo?.isConnected != true) {
            if (!silent)
                notifyError("No network connection!")
            return false
        }

        return true
    }

    fun getRawImageUrl(path: String) = serverLocation + path

    private fun createServerConnection(url: String) : HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = timeout
            if (apiKey.isNotEmpty())
                setRequestProperty("Authorization", "Bearer ${Base64.encodeToString(apiKey.toByteArray(), Base64.URL_SAFE)}")
        }

        return connection
    }

    private fun notifyExtract(id: String) {
        val title = DatabaseReader.database.archiveDao().getArchiveTitle(id)
        if (title != null)
            listener?.onExtract(title)
    }

    private fun handleErrorMessage(e: Exception, defaultMessage: String) {
        notifyError(if (verboseMessages) e.localizedMessage else defaultMessage)
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