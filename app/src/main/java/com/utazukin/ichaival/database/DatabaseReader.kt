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
import android.database.DatabaseUtils
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.stream.JsonReader
import com.utazukin.ichaival.*
import com.utazukin.ichaival.reader.ScaleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Type
import java.util.*

private class ArchiveDeserializer(val updateTime: Long) : JsonDeserializer<ArchiveJson> {
    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): ArchiveJson {
        return ArchiveJson(json.asJsonObject, updateTime)
    }
}

private class DatabaseMigrations {
    val MIGRATION_1_2 = object: Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("alter table archive add column pageCount INTEGER not null default 0")
        }
    }

    val MIGRATION_2_3 = object: Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("alter table readertab add column scaleType TEXT")
        }
    }

    val MIGRATION_3_4 = object: Migration(3, 4) {
        val converters = DatabaseTypeConverters()
        override fun migrate(database: SupportSQLiteDatabase) {
            val cursor = database.query("select id, tags from archive")
            while (cursor.moveToNext()) {
                val tags = cursor.getString(cursor.getColumnIndexOrThrow("tags"))
                val id = DatabaseUtils.sqlEscapeString(cursor.getString(cursor.getColumnIndexOrThrow("id")))
                val tagMap = converters.fromStringv3(tags)
                database.execSQL("update archive set tags = ${DatabaseUtils.sqlEscapeString(converters.fromMap(tagMap))} where id = $id")
            }
        }
    }

    val MIGRATION_4_5 = object: Migration(4, 5) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("alter table archive add column updatedAt INTEGER not null default 0")
        }
    }
}

object DatabaseReader {
    private const val jsonLocation: String = "archives.json"
    private const val MAX_ARCHIVE_SYNC = 1000

    private var isDirty = false
    private val archivePageMap = mutableMapOf<String, List<String>>()
    private val extractingArchives = mutableMapOf<String, Mutex>()
    private val extractingMutex = Mutex()
    private val extractListeners = mutableListOf<DatabaseExtractListener>()
    private val deleteListeners = mutableListOf<DatabaseDeleteListener>()

    lateinit var database: ArchiveDatabase
        private set

    fun init(context: Context) {
        val migrations = DatabaseMigrations()
        database = Room.databaseBuilder(context, ArchiveDatabase::class.java, "archive-db")
            .addMigrations(migrations.MIGRATION_1_2)
            .addMigrations(migrations.MIGRATION_2_3)
            .addMigrations(migrations.MIGRATION_3_4)
            .addMigrations(migrations.MIGRATION_4_5)
            .fallbackToDestructiveMigration()
            .build()
    }

    suspend fun updateArchiveList(context: Context, forceUpdate: Boolean = false) = withContext(Dispatchers.IO) {
        val cacheDir = context.noBackupFilesDir
        if (forceUpdate || checkDirty(cacheDir)) {
            WebHandler.updateRefreshing(true)
            val archiveStream = WebHandler.downloadArchiveList(context)
            if (archiveStream != null) {
                val jsonFile = File(cacheDir, jsonLocation)
                archiveStream.use { jsonFile.outputStream().use { output -> it.copyTo(output) } }
                readArchiveJson(jsonFile)
            }
            WebHandler.updateRefreshing(false)
            isDirty = false
        }
    }

    private suspend fun readArchiveJson(jsonFile: File) = jsonFile.inputStream().use {
        val serverArchives = mutableListOf<ArchiveJson>()
        val currentTime = Calendar.getInstance().timeInMillis
        val gson = GsonBuilder().registerTypeAdapter(ArchiveJson::class.java, ArchiveDeserializer(currentTime)).create()
        database.withTransaction {
            val bookmarks = if (ServerManager.serverTracksProgress) database.getBookmarks() else null
            JsonReader(InputStreamReader(it, "UTF-8")).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    val archive: ArchiveJson = gson.fromJson(reader, ArchiveJson::class.java)
                    serverArchives.add(archive)

                    if (serverArchives.size == MAX_ARCHIVE_SYNC) {
                        database.updateArchives(serverArchives, bookmarks)
                        serverArchives.clear()
                    }
                }
                reader.endArray()
            }

            if (serverArchives.isNotEmpty())
                database.updateArchives(serverArchives, bookmarks)
            database.removeOldArchives(currentTime)
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