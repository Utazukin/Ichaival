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

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.util.*

object DatabaseReader {
    private const val jsonLocation: String = "archives.json"

    private var isDirty = false
    private val archivePageMap = mutableMapOf<String, List<String>>()
    private val extractingArchives = mutableMapOf<String, Mutex>()
    private val extractingMutex = Mutex()
    private val extractListeners = mutableListOf<DatabaseExtractListener>()
    private val deleteListeners = mutableListOf<DatabaseDeleteListener>()

    private val MIGRATION_1_2 = object: Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("alter table archive add column pageCount INTEGER not null default 0")
        }
    }


    lateinit var database: ArchiveDatabase
        private set
    var refreshListener: DatabaseRefreshListener?
        get() = WebHandler.refreshListener
        set(value) { WebHandler.refreshListener = value }

    fun init(context: Context) {
        if (!this::database.isInitialized) {
            database =
                Room.databaseBuilder(context, ArchiveDatabase::class.java, "archive-db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
        }
    }

    suspend fun updateArchiveList(context: Context, forceUpdate: Boolean = false) = coroutineScope {
        val cacheDir = context.noBackupFilesDir
        if (forceUpdate || checkDirty(cacheDir)) {
            refreshListener?.isRefreshing(true)
            val jsonFile = File(cacheDir, jsonLocation)
            val archiveJson = withContext(Dispatchers.IO) { WebHandler.downloadArchiveList() }
            archiveJson?.let {
                jsonFile.writeText(it.toString())
                val serverArchives = readArchiveList(it)
                database.insertAndRemove(serverArchives)
                if (!forceUpdate)
                    launch { database.updateExisting(serverArchives) }
                else
                    database.updateExisting(serverArchives)
            }
            refreshListener?.isRefreshing(false)
            isDirty = false
        }
    }

    fun setDatabaseDirty() {
        isDirty = true
    }

    fun registerExtractListener(listener: DatabaseExtractListener) = extractListeners.add(listener)

    fun unregisterExtractListener(listener: DatabaseExtractListener) = extractListeners.remove(listener)

    fun registerDeleteListener(listener: DatabaseDeleteListener) = deleteListeners.add(listener)

    fun unregisterDeleteListener(listener: DatabaseDeleteListener) = deleteListeners.remove(listener)

    private fun notifyExtractListeners(id: String, pageCount: Int) {
        for (listener in extractListeners)
            listener.onExtract(id, pageCount)
    }

    private fun notifyDeleteListeners() {
        for (listener in deleteListeners)
            listener.onDelete()
    }

    suspend fun getPageList(id: String) : List<String> {
        val mutex = extractingMutex.withLock { extractingArchives.getOrPut(id) { Mutex() } }

        mutex.withLock {
            val pages = archivePageMap.getOrPut(id) {
                val p = WebHandler.getPageList(WebHandler.extractArchive(id))
                notifyExtractListeners(id, p.size)
                p
            }
            extractingArchives.remove(id)
            return pages
        }
    }

    fun getPageCount(id: String) : Int = archivePageMap[id]?.size ?: -1

    fun invalidateImageCache(id: String) = archivePageMap.remove(id)

    fun invalidateImageCache() = archivePageMap.clear()

    suspend fun isBookmarked(id: String) = withContext(Dispatchers.IO) { database.archiveDao().isBookmarked(id) }

    private fun checkDirty(fileDir: File) : Boolean {
        val jsonCache = File(fileDir, jsonLocation)
        val dayInMill = 1000 * 60 * 60 * 24L
        return isDirty || !jsonCache.exists() || Calendar.getInstance().timeInMillis - jsonCache.lastModified() >  dayInMill
    }

    suspend fun getArchive(id: String) = database.archiveDao().getArchive(id)

    suspend fun getRandomArchive() : Archive? {
        return withContext(Dispatchers.IO) {
            val ids = database.archiveDao().getAllIds()
            getArchive(ids.random())
        }
    }

    suspend fun deleteArchive(id: String) = withContext(Dispatchers.IO) {
        database.archiveDao().removeArchive(id)
        notifyDeleteListeners()
    }

    suspend fun deleteArchives(ids: List<String>) = withContext(Dispatchers.IO) {
        database.archiveDao().removeArchives(ids)
        notifyDeleteListeners()
    }

    suspend fun updateBookmark(id: String, page: Int) : Boolean = database.updateBookmark(id, page)

    suspend fun addBookmark(tab: ReaderTab) = withContext(Dispatchers.IO) { database.addBookmark(tab) }

    suspend fun removeBookmark(id: String) = withContext(Dispatchers.IO) { database.removeBookmark(id) }

    suspend fun clearBookmarks() : List<String> = withContext(Dispatchers.IO) { database.clearBookmarks() }

    suspend fun setArchiveNewFlag(id: String) {
        database.archiveDao().updateNewFlag(id, false)
        WebHandler.setArchiveNewFlag(id)
    }

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

    fun clearThumbnails(context: Context) {
        val thumbDir = File(context.noBackupFilesDir, "thumbs")
        if (thumbDir.exists())
            thumbDir.deleteRecursively()
    }

    suspend fun refreshThumbnail(id: String?, context: Context) : String? {
        if (id == null)
            return null

        val thumbDir = getThumbDir(context.noBackupFilesDir)
        val image = File(thumbDir, "$id.jpg")
        if (image.exists())
            image.delete()

        return getArchiveImage(id, context)
    }

    suspend fun getArchiveImage(archive: Archive, context: Context) = getArchiveImage(archive.id, context)

    suspend fun getArchiveImage(id: String, context: Context) : String? {
        val thumbDir = getThumbDir(context.noBackupFilesDir)

        var image: File? = File(thumbDir, "$id.jpg")
        if (image?.exists() == false)
            image = withContext(Dispatchers.IO) { WebHandler.downloadThumb(id, thumbDir) }

        return image?.path
    }
}