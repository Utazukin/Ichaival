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

import android.content.Context
import android.net.ConnectivityManager
import android.preference.Preference
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.*

object DatabaseReader : Preference.OnPreferenceChangeListener {
    private const val jsonLocation: String = "archives.json"

    val tagSuggestions
        get() = WebHandler.tagSuggestions
    private var isDirty = false
    private val archivePageMap = mutableMapOf<String, List<String>>()
    private val extractingArchives = mutableMapOf<String, Mutex>()
    private val extractingMutex = Mutex()

    lateinit var database: ArchiveDatabase
        private set
    var listener: DatabaseMessageListener?
        get() = WebHandler.listener
        set(value) { WebHandler.listener = value }

    var refreshListener: DatabaseRefreshListener?
        get() = WebHandler.refreshListener
        set(value) { WebHandler.refreshListener = value }

    var verboseMessages
        get() = WebHandler.verboseMessages
        set(value) { WebHandler.verboseMessages = value }

    fun init(context: Context) {
        if (!this::database.isInitialized) {
            database =
                Room.databaseBuilder(context, ArchiveDatabase::class.java, "archive-db").build()
            WebHandler.connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        }
    }

    suspend fun updateArchiveList(cacheDir: File, forceUpdate: Boolean = false) {
        if (forceUpdate || checkDirty(cacheDir)) {
            refreshListener?.isRefreshing(true)
            val jsonFile = File(cacheDir, jsonLocation)
            val archiveJson = withContext(Dispatchers.Default) { WebHandler.downloadArchiveList() }
            archiveJson?.let {
                jsonFile.writeText(it.toString())
                val serverArchives = readArchiveList(it)
                database.insertOrUpdate(serverArchives)
            }
            refreshListener?.isRefreshing(false)
            isDirty = false
        }
    }

    fun updateServerLocation(location: String) {
        WebHandler.serverLocation = location
    }

    fun updateApiKey(key: String) {
        WebHandler.apiKey = key
    }

    fun setDatabaseDirty() {
        isDirty = true
    }

    suspend fun getPageList(id: String) : List<String> {
        val mutex = extractingMutex.withLock {
            extractingArchives[id]
                ?: run {
                val m = Mutex()
                extractingArchives[id] = m
                m
            }
        }

        mutex.withLock {
            var pages = archivePageMap[id]
            if (pages == null) {
                pages = WebHandler.getPageList(WebHandler.extractArchive(id))
                archivePageMap[id] = pages
            }
            extractingArchives.remove(id)
            return pages
        }
    }

    fun getPageCount(id: String) : Int = archivePageMap[id]?.size ?: -1

    fun invalidateImageCache(id: String) {
        archivePageMap.remove(id)
    }

    private fun checkDirty(fileDir: File) : Boolean {
        val jsonCache = File(fileDir, jsonLocation)
        val dayInMill = 1000 * 60 * 60 * 24L
        return isDirty || !jsonCache.exists() || Calendar.getInstance().timeInMillis - jsonCache.lastModified() >  dayInMill
    }

    override fun onPreferenceChange(pref: Preference, newValue: Any) = WebHandler.onPreferenceChange(pref, newValue)

    fun getRawImageUrl(path: String) = WebHandler.getRawImageUrl(path)

    fun generateSuggestionList() = WebHandler.generateSuggestionList()

    fun searchServer(search: CharSequence, onlyNew: Boolean, sortMethod: SortMethod, descending: Boolean, start: Int = 0, showRefresh: Boolean = true) = WebHandler.searchServer(search, onlyNew, sortMethod, descending, start, showRefresh)

    suspend fun getArchive(id: String) = database.archiveDao().getArchive(id)

    suspend fun getRandomArchive() : Archive? {
        return withContext(Dispatchers.IO) {
            val ids = database.archiveDao().getAllIds()
            getArchive(ids.random())
        }
    }

    fun updateBookmark(id: String, page: Int) {
        GlobalScope.launch(Dispatchers.IO) { database.updateBookmark(id, page) }
    }

    fun addBookmark(tab: ReaderTab) {
        GlobalScope.launch(Dispatchers.IO) { database.addBookmark(tab) }
    }

    suspend fun removeBookmark(id: String) = withContext(Dispatchers.IO) { database.removeBookmark(id) }

    fun clearBookmarks() {
        GlobalScope.launch(Dispatchers.IO) { database.clearBookmarks() }
    }

    fun setArchiveNewFlag(id: String, isNew: Boolean) = WebHandler.setArchiveNewFlag(id, isNew)

    private fun readArchiveList(json: JSONArray) : Map<String, ArchiveJson> {
        val archiveList = mutableMapOf<String, ArchiveJson>()
        for (i in 0 until json.length()) {
            val archive = ArchiveJson(json.getJSONObject(i))
            archiveList[archive.id] = archive
        }

        return archiveList
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
            image = withContext(Dispatchers.Default) { WebHandler.downloadThumb(id, thumbDir) }

        return image?.path
    }
}