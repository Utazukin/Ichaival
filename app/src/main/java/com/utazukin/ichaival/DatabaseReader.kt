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

import android.content.Context
import android.net.ConnectivityManager
import android.preference.Preference
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
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
    private const val clearNewPath = "$apiPath/clear_new"
    private const val timeout = 5000 //ms

    private var serverLocation: String = ""
    private var isDirty = false
    private var apiKey: String = ""
    private val urlRegex by lazy { Regex("^(https?://|www\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*(:\\d+)?([/?].*)?\$") }
    private val archivePageMap = mutableMapOf<String, List<String>>()
    private val archiveActorMap = mutableMapOf<String, SendChannel<ExtractMsg>>()
    lateinit var database: ArchiveDatabase
        private set
    var listener: DatabaseMessageListener? = null
    var refreshListener: DatabaseRefreshListener? = null
    var connectivityManager: ConnectivityManager? = null
    var verboseMessages = false
    var tagSuggestions : Array<String> = arrayOf()
        private set

    fun init(context: Context) {
        if (!this::database.isInitialized)
            database = Room.databaseBuilder(context, ArchiveDatabase::class.java, "archive-db").build()
    }

    suspend fun updateArchiveList(cacheDir: File, forceUpdate: Boolean = false) {
        if (forceUpdate || checkDirty(cacheDir)) {
            refreshListener?.isRefreshing(true)
            val jsonFile = File(cacheDir, jsonLocation)
            val archiveJson = withContext(Dispatchers.Default) { downloadArchiveList() }
            archiveJson?.let {
                jsonFile.writeText(it.toString())
                val serverArchives = readArchiveList(it)
                updateDatabase(serverArchives)
            }
            refreshListener?.isRefreshing(false)
            isDirty = false
        }
    }

    private fun updateDatabase(jsonArchives: List<ArchiveJson>) {
        database.insertOrUpdate(jsonArchives)
    }

    fun updateServerLocation(location: String) {
        serverLocation = location
    }

    fun updateApiKey(key: String) {
        apiKey = key
    }

    private suspend fun getPageList(id: String, extractActor: SendChannel<ExtractMsg>) : List<String> {
        fun getPages(id: String): List<String> {
            return if (!archivePageMap.containsKey(id)) {
                val pages = getPageList(extractArchive(id))
                archivePageMap[id] = pages
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

    suspend fun getPageList(id: String) : List<String> {
        val actor = GlobalScope.getArchiveActor(id)
        return getPageList(id, actor)
    }

    private fun CoroutineScope.getArchiveActor(id: String) : SendChannel<ExtractMsg> {
        var archiveActor = archiveActorMap[id]
        if (archiveActor == null) {
            archiveActor = actor {
                var pages: List<String>? = null
                for (msg in channel) {
                    pages = when (msg) {
                        is QueueExtract -> msg.action(msg.id)
                        is GetPages -> {
                            msg.response.complete(pages ?: emptyList())
                            null
                        }
                    }
                }
            }
            archiveActorMap[id] = archiveActor
        }

        return archiveActor
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

        if (serverLocation == newValue)
            return true

        if (urlRegex.matches(newValue)) {
            isDirty = true
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

    fun getRawImageUrl(path: String) : String {
        return serverLocation + path
    }

    fun generateSuggestionList() {
        if (tagSuggestions.isNotEmpty() || !canConnect(true))
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
        if (!canConnect())
            return null

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

    fun getArchive(id: String) = database.archiveDao().getArchive(id)

    fun updateBookmark(id: String, page: Int) {
        GlobalScope.launch(Dispatchers.IO) { database.archiveDao().updateBookmark(id, page) }
    }

    fun removeBookmark(id: String) {
        GlobalScope.launch(Dispatchers.IO) { database.archiveDao().removeBookmark(id) }
    }

    fun clearBookmarks(ids: List<String>) {
        GlobalScope.launch(Dispatchers.IO) { database.archiveDao().removeAllBookmarks(ids) }
    }

    fun setArchiveNewFlag(id: String, isNew: Boolean) {
        database.archiveDao().updateNewFlag(id, isNew)

        if (!canConnect(true))
            return

        val url = URL("$serverLocation$clearNewPath?id=$id${getApiKey(true)}")
        val connection = url.openConnection() as HttpURLConnection
        try {
            with(connection) {
                connectTimeout = timeout
                if (responseCode != HttpURLConnection.HTTP_OK)
                    return
            }
        }
        catch (e: Exception) {}
        finally {
            connection.disconnect()
        }
    }

    private fun notifyExtract(id: String) {
        val title = database.archiveDao().getArchiveTitle(id)
        if (title != null)
            listener?.onExtract(title)
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

    private fun downloadArchiveList() : JSONArray? {
        if (!canConnect())
            return null

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

    private fun readArchiveList(json: JSONArray) : List<ArchiveJson> {
        val archiveList = mutableListOf<ArchiveJson>()
        for (i in 0 until json.length()) {
            archiveList.add(ArchiveJson(json.getJSONObject(i)))
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