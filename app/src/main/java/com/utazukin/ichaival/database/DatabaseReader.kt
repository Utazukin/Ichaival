/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2023 Utazukin
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

package com.utazukin.ichaival.database

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.stream.JsonReader
import com.utazukin.ichaival.Archive
import com.utazukin.ichaival.ArchiveJson
import com.utazukin.ichaival.ReaderTab
import com.utazukin.ichaival.WebHandler
import com.utazukin.ichaival.reader.ScaleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Type
import java.util.*

private class ArchiveDeserializer : JsonDeserializer<ArchiveJson> {
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): ArchiveJson {
        return ArchiveJson(json.asJsonObject)
    }
}

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

    private val MIGRATION_2_3 = object: Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("alter table readertab add column scaleType TEXT")
        }
    }

    lateinit var database: ArchiveDatabase
        private set

    fun init(context: Context) {
        if (!this::database.isInitialized) {
            database =
                Room.databaseBuilder(context, ArchiveDatabase::class.java, "archive-db")
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .build()
        }
    }

    suspend fun updateArchiveList(context: Context, forceUpdate: Boolean = false) = withContext(Dispatchers.IO) {
        val cacheDir = context.noBackupFilesDir
        if (forceUpdate || checkDirty(cacheDir)) {
            WebHandler.updateRefreshing(true)
            val jsonFile = File(cacheDir, jsonLocation)
            WebHandler.downloadArchiveList(context)?.use { jsonFile.outputStream().use { output -> it.copyTo(output) } }
            jsonFile.inputStream().use {
                val serverArchives = mutableMapOf<String, ArchiveJson>()
                JsonReader(InputStreamReader(it, "UTF-8")).use { reader ->
                    val gson = GsonBuilder().registerTypeAdapter(ArchiveJson::class.java, ArchiveDeserializer()).create()
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val archive: ArchiveJson = gson.fromJson(reader, ArchiveJson::class.java)
                        serverArchives[archive.id] = archive
                    }
                    reader.endArray()
                }
                database.insertAndRemove(serverArchives)
            }
            WebHandler.updateRefreshing(false)
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

    suspend fun getPageList(context: Context, id: String, forceFull: Boolean = false) : List<String> = withContext(Dispatchers.IO) {
        val mutex = extractingMutex.withLock { extractingArchives.getOrPut(id) { Mutex() } }

        mutex.withLock {
            archivePageMap.getOrPut(id) {
                WebHandler.getPageList(WebHandler.extractArchive(context, id, forceFull)).also {
                    database.archiveDao().updatePageCount(id, it.size)
                    notifyExtractListeners(id, it.size)
                }
            }.also { extractingArchives.remove(id) }
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

    suspend fun getArchive(id: String) = withContext(Dispatchers.IO) { database.archiveDao().getArchive(id) }

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

    suspend fun getBookmarks() = withContext(Dispatchers.IO) { database.archiveDao().getBookmarks() }

    suspend fun updateBookmark(id: String, page: Int) : Boolean = database.updateBookmark(id, page)

    suspend fun updateBookmark(id: String, scaleType: ScaleType) = withContext(Dispatchers.IO) { database.updateBookmarkScaleType(id, scaleType) }

    suspend fun addBookmark(tab: ReaderTab) = withContext(Dispatchers.IO) { database.addBookmark(tab) }

    suspend fun removeBookmark(id: String) = withContext(Dispatchers.IO) { database.removeBookmark(id) }

    suspend fun clearBookmarks() : List<String> = withContext(Dispatchers.IO) { database.clearBookmarks() }

    suspend fun insertBookmark(tab: ReaderTab) = withContext(Dispatchers.IO) { database.insertBookmark(tab) }

    suspend fun setArchiveNewFlag(id: String) = withContext(Dispatchers.IO) {
        database.archiveDao().updateNewFlag(id, false)
        WebHandler.setArchiveNewFlag(id)
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

    suspend fun refreshThumbnail(id: String?, context: Context, page: Int? = null) : Pair<String?, Long> {
        if (id == null)
            return Pair(null, -1)

        return withContext(Dispatchers.IO) {
            val thumbDir = getThumbDir(context.noBackupFilesDir)
            val image = File(thumbDir, "$id.jpg")
            if (image.exists())
                image.delete()

            getArchiveImage(id, context, page)
        }
    }

    suspend fun getArchiveImage(archive: Archive, context: Context) = getArchiveImage(archive.id, context)

    suspend fun getArchiveImage(id: String, context: Context, page: Int? = null) : Pair<String?, Long> {
        return withContext(Dispatchers.IO) {
            val thumbDir = getThumbDir(context.noBackupFilesDir)

            var image: File? = File(thumbDir, "$id.jpg")
            if (image?.exists() == false)
                image = WebHandler.downloadThumb(context, id, thumbDir, page)

            image?.run { Pair(path, lastModified()) } ?: Pair(null, -1)
        }
    }
}