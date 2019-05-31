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

import android.net.ConnectivityManager
import android.preference.Preference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.*

sealed class ExtractMsg
class QueueExtract(val id: String, val action: (id: String) -> List<String>) : ExtractMsg()
class GetPages(val response: CompletableDeferred<List<String>>) : ExtractMsg()

object DatabaseReader : Preference.OnPreferenceChangeListener {
    private const val jsonLocation: String = "archives.json"
    private const val apiPath: String = "/api"
    private const val archiveListPath = "$apiPath/archivelist"
    private const val thumbPath = "$apiPath/thumbnail"
    private const val extractPath = "$apiPath/extract"
    private const val tagsPath = "$apiPath/tagstats"
    private const val timeout = 5000 //ms

    private lateinit var archiveList: List<Archive>
    private var serverLocation: String = ""
    private var isDirty = false
    private var apiKey: String = ""
    private val archivePageMap = mutableMapOf<String, List<String>>()
    var listener: DatabaseMessageListener? = null
    var connectivityManager: ConnectivityManager? = null
    var verboseMessages = false
    var tagSuggestions : Array<String> = arrayOf()
        private set

    suspend fun readArchiveList(cacheDir: File, forceUpdate: Boolean = false): List<Archive> {
        if (!this::archiveList.isInitialized || forceUpdate) {
            val jsonFile = File(cacheDir, jsonLocation)
            archiveList = if (!forceUpdate && !checkDirty(cacheDir))
                readArchiveList(JSONArray(jsonFile.readText()))
            else if (connectivityManager?.activeNetworkInfo?.isConnected != true) {
                if (this::archiveList.isInitialized) archiveList else listOf()
            } else {
                val archiveJson = withContext(Dispatchers.Default) { downloadArchiveList() }
                if (archiveJson == null) {
                    when {
                        this::archiveList.isInitialized -> archiveList
                        jsonFile.exists() -> readArchiveList(JSONArray(jsonFile.readText()))
                        else -> listOf()
                    }
                }
                else {
                    jsonFile.writeText(archiveJson.toString())
                    readArchiveList(archiveJson)
                }
            }
            archiveList = archiveList.sortedBy { it.title.toLowerCase() }
            isDirty = false
        }
        return archiveList
    }

    fun updateServerLocation(location: String) {
        serverLocation = location
    }

    fun updateApiKey(key: String) {
        apiKey = key
    }

    suspend fun getPageList(id: String, extractActor: SendChannel<ExtractMsg>) : List<String> {
        fun getPages(id: String): List<String> {
            return if (!archivePageMap.containsKey(id)) {
                val pages = getPageList(extractArchive(id))
                archivePageMap[id] = pages

                if (pages.isNotEmpty())
                    getArchive(id)?.isNew = false //Set to false since its been opened.
                    //TODO update the json file.
                pages
            } else
                archivePageMap[id]!!
        }

        return if (!archivePageMap.containsKey(id)) {
            runBlocking { extractActor.send(QueueExtract(id, ::getPages)) }
            val response = CompletableDeferred<List<String>>()
            extractActor.send(GetPages(response))
            response.await()
        } else archivePageMap[id]!!
    }

    fun getPageCount(id: String) : Int = archivePageMap[id]?.size ?: -1

    fun invalidateImageCache(id: String) {
        archivePageMap.remove(id)
    }

    private fun getPageList(response: JSONObject?) : List<String> {
        val jsonPages = response?.optJSONArray("pages")
        if (jsonPages == null) {
            notifyError(response?.keys()?.next() ?: "Failed to extract archive")
            return listOf()
        }

        val count = jsonPages.length()
        return MutableList(count) {
            val pagePath = jsonPages.getString(it)
            if (pagePath.startsWith("./"))
                pagePath.substring(1)
            else
                "/$pagePath"
        }
    }

    private fun getApiKey(multiParam: Boolean) : String {
        if (apiKey.isBlank())
            return ""
        else {
           var string = "key=$apiKey"
            if (multiParam)
                string = "&$string"
            return string
        }
    }

    private fun checkDirty(fileDir: File) : Boolean {
        val jsonCache = File(fileDir, jsonLocation)
        val dayInMill = 1000 * 60 * 60 * 24L
        return isDirty || !jsonCache.exists() || Calendar.getInstance().timeInMillis - jsonCache.lastModified() >  dayInMill
    }

    override fun onPreferenceChange(pref: Preference, newValue: Any): Boolean {
        if ((newValue as String).isEmpty())
            return false

        return try { //TODO use something better for validation
            val url = URL(newValue)
            if (serverLocation != newValue) {
                serverLocation = newValue
                isDirty = true
            }
            true
        } catch (e: Exception) {
            notifyError("Invalid URL!")
            false
        }
    }

    suspend fun getArchive(id: String, fileDir: File) : Archive? {
        readArchiveList(fileDir)

        for (archive: Archive in archiveList) {
            if (archive.id == id)
                return archive
        }
        return null
    }

    fun getRawImageUrl(path: String) : String {
        return serverLocation + path
    }

    fun generateSuggestionList() {
        if (tagSuggestions.isNotEmpty() || serverLocation.isEmpty() || connectivityManager?.activeNetworkInfo?.isConnected != true)
            return

        val url = URL("$serverLocation$tagsPath${getApiKey(false)}")

        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = timeout
        try {
            with(connection) {
                if (responseCode != HttpURLConnection.HTTP_OK)
                    return

                BufferedReader(InputStreamReader(inputStream)).use {
                    val response = StringBuffer()

                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }
                    val tagJsonArray = parseJsonArray(response.toString())
                    if (tagJsonArray != null) {
                        val tagArray = Array<Pair<String, Int>>(tagJsonArray.length()) { i ->
                            val item = tagJsonArray.getJSONObject(i)
                            Pair(item.getString("text"), item.getInt("weight"))
                        }
                        tagArray.sortByDescending { tag -> tag.second }
                        tagSuggestions = Array(tagArray.size) { i -> tagArray[i].first.toLowerCase() }
                    }
                }
            }
        }
        catch(e: Exception) { }
        finally {
            connection.disconnect()
        }
    }

    private fun extractArchive(id: String) : JSONObject? {
        if (connectivityManager?.activeNetworkInfo?.isConnected != true) {
            notifyError("No network connection!")
            return null
        }

        val url = URL("$serverLocation$extractPath?id=$id${getApiKey(true)}")
        notifyExtract(id)

        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = timeout
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

    private fun notifyError(error: String) {
        listener?.onError(error)
    }

    private fun getArchive(id: String) : Archive? {
        return if (this::archiveList.isInitialized) archiveList.find { x -> x.id == id } else null
    }

    private fun notifyExtract(id: String) {
        val title = getArchive(id)?.title
        if (title != null)
            listener?.onExtract(title)
    }

    private fun downloadArchiveList() : JSONArray? {
        if (serverLocation.isEmpty())
            return null

        if (connectivityManager?.activeNetworkInfo?.isConnected != true) {
            notifyError("No network connection!")
            return null
        }

        val url = URL("$serverLocation$archiveListPath?${getApiKey(false)}")
        val connection = url.openConnection() as HttpURLConnection
        try {
            with(connection) {
                connectTimeout = timeout
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

    private fun handleErrorMessage(e: Exception, defaultMessage: String) {
        notifyError(if (verboseMessages) e.localizedMessage else defaultMessage)
    }

    private fun handleErrorMessage(responseCode: Int, defaultMessage: String) {
        notifyError(if (verboseMessages) "$defaultMessage Response Code: $responseCode" else defaultMessage)
    }

    private fun readArchiveList(json: JSONArray) : List<Archive> {
        val archiveList = mutableListOf<Archive>()
        for (i in 0 until json.length()) {
            archiveList.add(Archive(json.getJSONObject(i)))
        }

        return archiveList
    }

    private fun downloadThumb(id: String, thumbDir: File) : File? {
        val url = URL("$serverLocation$thumbPath?id=$id${getApiKey(true)}")

        try {
            with(url.openConnection() as HttpURLConnection) {
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
           return null
        }
    }

    private fun parseJsonArray(input: String) : JSONArray? {
        return try {
            JSONArray(input)
        } catch (e: JSONException) {
            null
        }
    }

    private fun getThumbDir(cacheDir: File) : File {
        val thumbDir = File(cacheDir, "thumbs")
        if (!thumbDir.exists())
            thumbDir.mkdir()
        return thumbDir
    }

    fun clearThumbnails(cacheDir: File) {
        val thumbDir = File(cacheDir, "thumbs")
        if (thumbDir.exists())
            thumbDir.deleteRecursively()
    }

    suspend fun getArchiveImage(archive: Archive, filesDir: File) = getArchiveImage(archive.id, filesDir)

    suspend fun getArchiveImage(id: String, filesDir: File) : String? {
        val thumbDir = getThumbDir(filesDir)

        var image: File? = File(thumbDir, "$id.jpg")
        if (image != null && !image.exists())
            image = withContext(Dispatchers.Default) { downloadThumb(id, thumbDir) }

        return image?.path
    }
}