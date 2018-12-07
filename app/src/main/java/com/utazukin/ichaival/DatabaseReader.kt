/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2018 Utazukin
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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.Charset
import java.util.*

object DatabaseReader : Preference.OnPreferenceChangeListener {
    private const val jsonLocation: String = "archives.json"
    private const val apiPath: String = "/api"
    private const val archiveListPath = "$apiPath/archivelist"
    private const val thumbPath = "$apiPath/thumbnail"
    private const val extractPath = "$apiPath/extract"
    private const val timeout = 5000 //ms

    private lateinit var archiveList: List<Archive>
    private var serverLocation: String = ""
    private var isDirty = false
    private var apiKey: String = ""
    var listener: DatabaseMessageListener? = null
    var connectivityManager: ConnectivityManager? = null

    suspend fun readArchiveList(cacheDir: File, forceUpdate: Boolean = false): List<Archive> {
        if (!this::archiveList.isInitialized || forceUpdate) {
            val jsonFile = File(cacheDir, jsonLocation)
            archiveList = if (!forceUpdate && !checkDirty(cacheDir))
                readArchiveList(JSONArray(jsonFile.readText()))
            else if (connectivityManager?.activeNetworkInfo?.isConnected != true) {
                if (this::archiveList.isInitialized) archiveList else listOf()
            } else {
                val archiveJson = GlobalScope.async(Dispatchers.Default) { downloadArchiveList() }.await()
                if (archiveJson == null)
                    if (this::archiveList.isInitialized) archiveList else listOf()
                else {
                    jsonFile.writeText(archiveJson.toString())
                    readArchiveList(archiveJson)
                }
            }
            archiveList = archiveList.sortedBy { archive -> archive.title.toLowerCase() }
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
        val dayInMill = 1000 * 60 * 60 * 60 * 24L
        return isDirty || !jsonCache.exists() || Calendar.getInstance().timeInMillis - jsonCache.lastModified() >  dayInMill
    }

    override fun onPreferenceChange(pref: Preference?, newValue: Any?): Boolean {
        if ((newValue as String?)?.isEmpty() == true)
            return false

        return try { //TODO use something better for validation
            val url = URL(newValue as String)
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

    fun extractArchive(id: String) : JSONObject? {
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
                if (responseCode != 200)
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
            when (e) {
                is SocketException, is SocketTimeoutException -> {
                    notifyError("Failed to extract archive!")
                    return null
                }
                else -> throw e
            }
        }
        finally {
            connection.disconnect()
        }
    }

    private fun notifyError(error: String) {
        GlobalScope.launch(Dispatchers.Main) { listener?.onError(error) }
    }

    private fun getArchive(id: String) : Archive? {
        return if (this::archiveList.isInitialized) archiveList.find { x -> x.id == id } else null
    }

    private fun notifyExtract(id: String) {
        val title = getArchive(id)?.title
        if (title != null)
            GlobalScope.launch(Dispatchers.Main) { listener?.onExtract(title) }
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
                if (responseCode != 200) {
                    notifyError("Failed to connect to server!")
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
            notifyError("Failed to connect to server!")
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun readArchiveList(json: JSONArray) : List<Archive> {
        val archiveList = mutableListOf<Archive>()
        for (i in 0..(json.length() - 1)) {
            archiveList.add(Archive(json.getJSONObject(i)))
        }

        return archiveList
    }

    private fun downloadThumb(id: String, thumbDir: File) : File? {
        val url = URL("$serverLocation$thumbPath?id=$id${getApiKey(true)}")

        with(url.openConnection() as HttpURLConnection) {
            if (responseCode != 200) {
                notifyError("Failed to download thumbnail!")
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

    suspend fun getArchiveImage(archive: Archive, filesDir: File) : Bitmap? {
        val id = archive.id
        val thumbDir = getThumbDir(filesDir)

        var image: File? = File(thumbDir, "$id.jpg")
        if (image != null && !image.exists())
            image = GlobalScope.async { downloadThumb(id, thumbDir) }.await()

        return if (image != null) BitmapFactory.decodeFile(image.path) else null
    }
}