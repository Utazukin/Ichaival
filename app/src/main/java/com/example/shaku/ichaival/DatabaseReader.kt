package com.example.shaku.ichaival

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.preference.Preference
import android.support.annotation.UiThread
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.*

class DatabaseReader private constructor() : Preference.OnPreferenceChangeListener {
    companion object {
        private const val jsonLocation: String = "archives.json"
        private const val apiPath: String = "/api"
        private const val archiveListPath = "$apiPath/archivelist"
        private const val thumbPath = "$apiPath/thumbnail"
        private const val extractPath = "$apiPath/extract"
        private const val tempFolder = "temp"

        val reader = DatabaseReader()

        @UiThread
        suspend fun readArchiveList(context: Context, forceUpdate: Boolean = false): List<Archive> {
            return reader.readArchiveList(context, forceUpdate)
        }

        suspend fun getArchive(id: String, context: Context) : Archive? {
            return reader.getArchive(id, context)
        }

        fun downloadPage(path: String) : ByteArray? {
            return reader.downloadPage(path)
        }

        fun extractArchive(id: String) : JSONObject? {
            return reader.extractArchive(id)
        }

        fun getRawImageUrl(path: String) : String {
            return reader.getRawImageUrl(path)
        }

        fun updateServerLocation(location: String) {
            reader.serverLocation = location
        }

        fun clearCache(context: Context) {
            reader.clearCache(context)
        }

        fun getCacheSize(context: Context) : Long {
            return reader.getCacheSize(context)
        }

        suspend fun getArchiveImage(archive: Archive, context: Context) : Bitmap? {
            return reader.getArchiveImage(archive, context)
        }
    }

    private lateinit var archiveList: List<Archive>
    private var serverLocation: String = ""
    private var isDirty = false

    @UiThread
    private suspend fun readArchiveList(context: Context, forceUpdate: Boolean = false): List<Archive> {
        if (!this::archiveList.isInitialized || forceUpdate) {
            val cacheDir: File = context.filesDir
            val jsonFile = File(cacheDir, jsonLocation)
            archiveList = if (!checkDirty(cacheDir))
                readArchiveList(jsonFile.readText())
            else {
                val archiveJson = GlobalScope.async { downloadArchiveList() }.await()
                if (archiveJson == "")
                    listOf()
                else {
                    jsonFile.writeText(archiveJson)
                    readArchiveList(archiveJson)
                }
            }
            archiveList = archiveList.sortedBy { archive -> archive.title }
        }
        return archiveList
    }

    private fun checkDirty(fileDir: File) : Boolean {
        val jsonCache = File(fileDir, jsonLocation)
        val dayInMill = 1000 * 60 * 60 * 60 * 24L
        return isDirty || !jsonCache.exists() || Calendar.getInstance().timeInMillis - jsonCache.lastModified() >  dayInMill
    }

    override fun onPreferenceChange(pref: Preference?, newValue: Any?): Boolean {
        return try { //TODO use something better for validation
            val url = URL(newValue as String)
            if (serverLocation != newValue) {
                serverLocation = newValue
                isDirty = false
                true
            } else
                false
        } catch (e: Exception) {
            //TODO show a toast telling the user to enter a valid url.
            false
        }
    }

    private suspend fun getArchive(id: String, context: Context) : Archive? {
        readArchiveList(context)

        for (archive: Archive in archiveList) {
            if (archive.id == id)
                return archive
        }
        return null
    }

    private fun clearCache(context: Context) {
        val tempDir = File(context.filesDir, tempFolder)
        if (tempDir.exists() && tempDir.isDirectory)
            tempDir.deleteRecursively()
    }

    private fun getCacheSize(context: Context) : Long {
        val tempDir = File(context.filesDir, tempFolder)
        return if (tempDir.exists()) tempDir.totalSpace else 0
    }

    private fun downloadPage(path: String) : ByteArray? {
        val url = URL(serverLocation + path)

        with (url.openConnection() as HttpURLConnection) {
            if (responseCode != 200)
                return null

            BufferedReader(InputStreamReader(inputStream)).use {
                return inputStream.readBytes()
            }
        }
    }

    private fun getRawImageUrl(path: String) : String {
        return serverLocation + path
    }

    private fun extractArchive(id: String) : JSONObject? {
        val url = URL("$serverLocation$extractPath?id=$id")

        with (url.openConnection() as HttpURLConnection) {
            if (responseCode != 200)
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

    private fun downloadArchiveList() : String {
        try {
            val url = URL(serverLocation + archiveListPath)

            with(url.openConnection() as HttpURLConnection) {
                if (responseCode != 200)
                    return ""

                val decoder = Charset.forName("utf-8").newDecoder()
                BufferedReader(InputStreamReader(inputStream, decoder)).use {
                    val response = StringBuffer()

                    var inputLine = it.readLine()
                    while (inputLine != null) {
                        response.append(inputLine)
                        inputLine = it.readLine()
                    }
                    return response.toString()
                }
            }
        }
        catch (e: Exception) {
            return ""
        }
    }

    private fun readArchiveList(jsonString: String) : List<Archive> {
        val json = JSONArray(jsonString)

        val archiveList = mutableListOf<Archive>()
        for (i in 0..(json.length() - 1)) {
            archiveList.add(Archive(json.getJSONObject(i)))
        }

        return archiveList
    }

    private fun downloadThumb(id: String, thumbDir: File) : File? {
        val url = URL("$serverLocation$thumbPath?id=$id")

        with(url.openConnection() as HttpURLConnection) {
            if (responseCode != 200)
                return null

            BufferedReader(InputStreamReader(inputStream)).use {
                val bytes = inputStream.readBytes()

                val thumbFile = File(thumbDir, "$id.jpg")
                thumbFile.writeBytes(bytes)
                return thumbFile
            }
        }
    }

    private fun getThumbDir(cacheDir: File) : File {
        val thumbDir = File(cacheDir, "thumbs")
        if (!thumbDir.exists())
            thumbDir.mkdir()
        return thumbDir
    }

    private suspend fun getArchiveImage(archive: Archive, context: Context) : Bitmap? {
        val id = archive.id
        val cacheDir = context.filesDir
        val thumbDir = getThumbDir(cacheDir)

        var image: File? = File(thumbDir, "$id.jpg")
        if (image != null && !image.exists())
            image = GlobalScope.async { downloadThumb(id, thumbDir) }.await()

        return if (image != null) BitmapFactory.decodeFile(image.path) else null
    }
}