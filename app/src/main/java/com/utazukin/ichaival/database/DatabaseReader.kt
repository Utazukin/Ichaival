/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2026 Utazukin
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
import androidx.core.content.edit
import androidx.paging.PagingSource
import androidx.preference.PreferenceManager
import androidx.room.Entity
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.stream.JsonReader
import com.utazukin.ichaival.Archive
import com.utazukin.ichaival.ArchiveBase
import com.utazukin.ichaival.ArchiveCategory
import com.utazukin.ichaival.ArchiveCategoryFull
import com.utazukin.ichaival.ArchiveJson
import com.utazukin.ichaival.CategoryManager
import com.utazukin.ichaival.R
import com.utazukin.ichaival.ReaderTab
import com.utazukin.ichaival.SortMethod
import com.utazukin.ichaival.StaticCategoryRef
import com.utazukin.ichaival.StatusFilter
import com.utazukin.ichaival.ToCEntryFull
import com.utazukin.ichaival.ToCEntryUpdate
import com.utazukin.ichaival.WebHandler
import com.utazukin.ichaival.castStringPrefToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Type
import java.util.Calendar

private class ArchiveDeserializer(private val updateTime: Long) : JsonDeserializer<ArchiveJson> {
    private var index = 0

    override fun deserialize(json: JsonElement, typeOfT: Type?, context: JsonDeserializationContext?): ArchiveJson {
        return ArchiveJson(json.asJsonObject, updateTime, index++)
    }
}

@Entity(tableName = "search", primaryKeys = ["searchText", "archiveId"])
data class SearchArchiveRef(val searchText: String, val archiveId: String)

private class DatabaseHelper {
    private val MIGRATION_1_2 = object: Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("alter table archive add column pageCount INTEGER not null default 0")
        }
    }

    private val MIGRATION_2_3 = object: Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("alter table readertab add column scaleType TEXT")
        }
    }

    private val MIGRATION_3_4 = object: Migration(3, 4) {
        val converters = DatabaseTypeConverters()
        override fun migrate(db: SupportSQLiteDatabase) {
            val cursor = db.query("select id, tags from archive")
            while (cursor.moveToNext()) {
                val tags = cursor.getString(cursor.getColumnIndexOrThrow("tags"))
                val id = DatabaseUtils.sqlEscapeString(cursor.getString(cursor.getColumnIndexOrThrow("id")))
                val tagMap = converters.fromStringv3(tags)
                db.execSQL("update archive set tags = ${DatabaseUtils.sqlEscapeString(converters.fromMap(tagMap))} where id = $id")
            }
        }
    }

    private val MIGRATION_4_5 = object: Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("alter table archive add column updatedAt INTEGER not null default 0")
        }
    }

    private val MIGRATION_5_6 = object: Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("alter table archive add column titleSortIndex INTEGER not null default 0")
            DatabaseReader.setDatabaseDirty()
        }
    }

    private val MIGRATION_6_7 = object: Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            with(db) {
                execSQL("create table if not exists archivecategory (name text not null, pinned integer not null, search text, id text not null primary key, updatedAt integer not null default 0)")
                execSQL("create table if not exists staticcategoryref (archiveId text not null, categoryId text not null, updatedAt integer not null default 0, primary key (categoryId, archiveId))")
            }
            DatabaseReader.setDatabaseDirty()
        }

    }

    private val MIGRATION_7_8 = object: Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            with(db) {
                execSQL("drop table if exists search")
                execSQL("create table search (searchText text not null, archiveId text not null, primary key (searchText, archiveId))")
            }
        }
    }

    private val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("alter table archive add column summary TEXT")
        }
    }

    private val MIGRATION_9_10 = object: Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("drop table if exists readertab")
            db.execSQL("create table if not exists readertab (id text not null, title text not null, index integer not null default 0, currentPage integer not null, primary key (id, currentPage))")
        }
    }

    private val MIGRATION_10_11 = object: Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("create table toc (name text not null, page integer not null, updateTime integer default 0, archiveId text not null, primary key (archiveId, page)")
        }
    }

    val migrations = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11
    )

    val callbacks = object: RoomDatabase.Callback() {
        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
            super.onDestructiveMigration(db)
            DatabaseReader.setDatabaseDirty()
        }
    }
}

object DatabaseReader {
    const val MAX_WORKING_ARCHIVES = 3000
    private const val jsonLocation = "archives.json"
    private const val UPDATE_KEY = "last_update"

    private var isDirty = false
    private val archivePageMap = mutableMapOf<String, List<String>>()
    private val extractingArchives = mutableMapOf<String, Mutex>()
    private val extractingMutex = Mutex()
    private val extractListeners = mutableListOf<DatabaseExtractListener>()
    private lateinit var database: ArchiveDatabase

    fun init(context: Context) {
        val dbHelper = DatabaseHelper()
        database = Room.databaseBuilder(context, ArchiveDatabase::class.java, "archive-db")
            .addMigrations(*dbHelper.migrations)
            .fallbackToDestructiveMigration(true)
            .addCallback(dbHelper.callbacks)
            .build()
    }

    suspend fun updateArchiveList(context: Context) = withContext(Dispatchers.IO) {
        if (!WebHandler.canReachServer())
            return@withContext

        val currentTime = Calendar.getInstance().timeInMillis
        withTransaction {
            val gson = GsonBuilder().registerTypeAdapter(ArchiveJson::class.java, ArchiveDeserializer(currentTime)).create()
            val archiveStream = WebHandler.getOrderedArchives(-1) ?: return@withTransaction
            JsonReader(archiveStream.bufferedReader(Charsets.UTF_8)).use {
                it.beginObject()
                while (it.hasNext()) {
                    val name = it.nextName()
                    if (name == "data") {
                        it.beginArray()
                        while (it.hasNext()) {
                            val archive: ArchiveJson = gson.fromJson(it, ArchiveJson::class.java)
                            updateArchive(archive)
                            val tocList = archive.toc?.let { toc ->
                                List(toc.size()) { i ->
                                    val entry = toc.get(i).asJsonObject
                                    ToCEntryFull(
                                            entry.get("name").asString,
                                            entry.get("page").asInt - 1,
                                            currentTime,
                                            archive.id
                                    )
                                }
                            }
                            if (tocList != null)
                                database.archiveDao().addToc(tocList)
                        }
                        it.endArray()
                    } else it.skipValue()
                }
                it.endObject()
            }

            removeOldArchives(currentTime)
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit { putLong(UPDATE_KEY, currentTime) }

        launch { database.archiveDao().clearSearchCache() }
        CategoryManager.updateCategories()
        isDirty = false
    }

    private suspend fun updateArchive(archiveJson: ArchiveJson) = database.archiveDao().insertJson(archiveJson)

    suspend fun <R> withTransaction(block: suspend () -> R) = database.withTransaction { block() }

    private suspend fun removeOldArchives(updateTime: Long) = withTransaction {
        with(database.archiveDao()) {
            removeNotUpdatedBookmarks(updateTime)
            removeOldCategoryReferences(updateTime)
            removeNotUpdated(updateTime)
            removeOldToC(updateTime)
        }
    }

    suspend fun removeOutdatedCategories(updateTime: Long) = withTransaction {
        with(database.archiveDao()) {
            removeOutdatedStaticCategories(updateTime)
            removeOutdatedCategories(updateTime)
        }
    }

    fun getAllCategories() = database.archiveDao().getAllCategories()

    suspend fun getCategoryArchives(id: String) = database.archiveDao().getCategoryArchives(id)

    suspend fun isInCategory(categoryId: String, archiveId: String) = database.archiveDao().isInCategory(categoryId, archiveId)

    suspend fun insertCategories(categories: Collection<ArchiveCategoryFull>) = database.archiveDao().insertCategories(categories)

    suspend fun insertCategory(category: ArchiveCategoryFull) = database.archiveDao().insertCategory(category)

    suspend fun insertCategory(category: ArchiveCategory) {
        with(category) {
            database.archiveDao().insertCategory(ArchiveCategoryFull(name, id, search, false, 0))
        }
    }

    suspend fun removeFromCategory(categoryId: String, archiveId: String) = database.archiveDao().removeFromCategory(categoryId, archiveId)

    suspend fun insertStaticCategories(references: Collection<StaticCategoryRef>) = database.archiveDao().insertStaticCategories(references)
    suspend fun insertStaticCategory(reference: StaticCategoryRef) = database.archiveDao().insertStaticCategory(reference)

    private suspend fun getBookmarkMap(): MutableMap<String, ReaderTab> {
        val map = mutableMapOf<String, ReaderTab>()
        database.archiveDao().getBookmarks().associateByTo(map) { it.id }
        return map
    }

    suspend fun getBookmarks() = database.archiveDao().getBookmarks()

    fun getDataBookmarks() = database.archiveDao().getDataBookmarks()

    suspend fun getBookmarkCount() = database.archiveDao().getBookmarkCount()

    suspend fun getBookmark(id: String, page: Int) = database.archiveDao().getBookmark(id, page)

    suspend fun addBookmark(tab: ReaderTab) = database.archiveDao().addBookmark(tab)

    suspend fun removeBookmark(id: String, page: Int) : Boolean = withTransaction {
        val tab = database.archiveDao().getBookmark(id, page)
        if (tab != null) {
            removeBookmark(tab)
            true
        } else false
    }

    suspend fun updateProgress(id: String, page: Int) = database.archiveDao().updateProgress(id, page)

    suspend fun markCompleted(archives: List<ArchiveBase>) = withTransaction {
        for (archive in archives)
            database.archiveDao().markCompleted(archive.id)
    }

    suspend fun deleteArchives(ids: Collection<String>) {
        withTransaction {
            with(database.archiveDao()) {
                removeArchives(ids)
                removeBookmarks(ids)
            }
        }
    }

    fun getToC(archiveId: String) = database.archiveDao().getToC(archiveId)

    suspend fun getToCEntry(page: Int, archiveId: String) = database.archiveDao().getTocEntry(page, archiveId)

    suspend fun updateToCEntry(entry: ToCEntryUpdate) = database.archiveDao().updateToCEntry(entry)

    suspend fun removeToCEntry(page: Int, archiveId: String) = database.archiveDao().removeToCEntry(page, archiveId)

    fun getArchiveSource(sortMethod: SortMethod, descending: Boolean, status: StatusFilter, search: String? = null, categoryId: String? = null) : PagingSource<Int, ArchiveBase> {
        val queryBuilder = StringBuilder("Select id, title, tags, pageCount from archive")
        val args = buildList(2) {
            if (!search.isNullOrBlank()) {
                add(search)
                queryBuilder.append(" join search on searchText = ? and archive.id = search.archiveId")
            }

            if (!categoryId.isNullOrBlank()) {
                add(categoryId)
                queryBuilder.append(" join staticcategoryref on categoryId = ? and archive.id = staticcategoryref.archiveId")
            }
        }

        when (status) {
            StatusFilter.OnlyNew -> queryBuilder.append(" where archive.isNew")
            StatusFilter.InProgress -> queryBuilder.append(" where archive.currentPage > 0 and archive.currentPage < archive.pageCount - 1")
            StatusFilter.Completed -> queryBuilder.append(" where archive.currentPage == archive.pageCount - 1")
            StatusFilter.None -> {}
        }
        queryBuilder.append(" order by ${if (sortMethod == SortMethod.Date) "dateAdded" else "titleSortIndex"} ${if (descending) "desc" else "asc"}")

        val query = if (args.isNotEmpty()) SimpleSQLiteQuery(queryBuilder.toString(), args.toTypedArray()) else SimpleSQLiteQuery(queryBuilder.toString())
        return database.archiveDao().getSearchSource(query)
    }

    suspend fun insertSearch(reference: SearchArchiveRef) = database.archiveDao().insertSearch(reference)

    suspend fun getCachedSearchCount(search: String) = database.archiveDao().getCachedSearchCount(search)

    suspend fun getArchiveCount() = database.archiveDao().getArchiveCount()

    suspend fun getArchives(offset: Int, limit: Int) = database.archiveDao().getArchives(offset, limit)

    fun getRandomSource(filter: String, categoryId: String, count: Int) = when {
        categoryId.isNotEmpty() -> database.archiveDao().getRandomCategorySource(categoryId, count)
        filter.isNotBlank() -> database.archiveDao().getSearchResultsRandom(filter, count)
        else -> database.archiveDao().getRandomSource(count)
    }

    suspend fun getRandom(search: String, status: StatusFilter, categoryId: String, excludeBookmarked: Boolean = false): Archive? {
        val queryBuilder = StringBuilder("Select archive.id, archive.title, dateAdded, isNew, tags, archive.currentPage, summary from archive")
        val args = buildList(2) {
            if (search.isNotBlank()) {
                add(search)
                queryBuilder.append(" join search on searchText = ? and archive.id = search.archiveId")
            }

            if (categoryId.isNotBlank()) {
                add(categoryId)
                queryBuilder.append(" join staticcategoryref on categoryId = ? and archive.id = staticcategoryref.archiveId")
            }
        }

        when (status) {
            StatusFilter.OnlyNew -> queryBuilder.append(" where archive.isNew")
            StatusFilter.InProgress -> queryBuilder.append(" where archive.currentPage > 0 and archive.currentPage < archive.pageCount - 1")
            StatusFilter.Completed -> queryBuilder.append(" where archive.currentPage == archive.pageCount - 1")
            StatusFilter.None -> {}
        }

        if (excludeBookmarked)
            queryBuilder.append(" left join readertab on archive.id = readertab.id and readertab.currentPage = -1 where readertab.id is null")

        queryBuilder.append(" order by random() limit 1")
        val query = if (args.isNotEmpty()) SimpleSQLiteQuery(queryBuilder.toString(), args.toTypedArray()) else SimpleSQLiteQuery(queryBuilder.toString())
        return database.archiveDao().getRandom(query)
    }

    suspend fun removeBookmark(tab: ReaderTab) = withTransaction {
        with(database.archiveDao()) {
            val tabs = getBookmarks()
            val adjustedTabs = tabs.filter { it.index > tab.index }
            for (adjustedTab in adjustedTabs)
                --adjustedTab.index

            removeBookmark(tab)
            updateBookmarks(adjustedTabs)
        }
    }

    suspend fun insertBookmark(tab: ReaderTab) = withTransaction {
        with(database.archiveDao()) {
            val tabs = getBookmarks()
            val adjustedTabs = tabs.filter { it.index >= tab.index }
            for (adjustedTab in adjustedTabs)
                ++adjustedTab.index

            addBookmark(tab)
            updateBookmarks(adjustedTabs)
        }
    }

    suspend fun clearBookmarks() = database.archiveDao().clearBookmarks()

    fun setDatabaseDirty() {
        isDirty = true
    }

    fun registerExtractListener(listener: DatabaseExtractListener) = extractListeners.add(listener)

    fun unregisterExtractListener(listener: DatabaseExtractListener) = extractListeners.remove(listener)

    private fun notifyExtractListeners(id: String, pageCount: Int) {
        for (listener in extractListeners)
            listener.onExtract(id, pageCount)
    }

    suspend fun getPageList(context: Context, id: String, forceFull: Boolean = false) : List<String> = withContext(Dispatchers.IO) {
        val mutex = extractingMutex.withLock { extractingArchives.getOrPut(id) { Mutex() } }

        mutex.withLock {
            archivePageMap.getOrPut(id) {
                WebHandler.getPageList(WebHandler.extractArchive(context, id, forceFull)).also {
                    if (it.isNotEmpty())
                        database.archiveDao().updatePageCount(id, it.size)
                    notifyExtractListeners(id, it.size)
                }
            }.also { extractingArchives.remove(id) }
        }
    }

    fun invalidateImageCache(id: String) {
        archivePageMap.remove(id)
    }

    fun invalidateImageCache() = archivePageMap.clear()

    suspend fun isBookmarked(id: String, page: Int) = database.archiveDao().isBookmarked(id, page)

    suspend fun needsUpdate(context: Context) : Boolean = withContext(Dispatchers.IO) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val syncTime = prefs.castStringPrefToLong(context.getString(R.string.sync_time_key), 24)
        val updateTime = prefs.getLong(UPDATE_KEY, -1)
        val syncMilli = 1000 * 60 * 60 * syncTime
        isDirty || updateTime < 0 || (syncMilli >= 0 && Calendar.getInstance().timeInMillis - updateTime > syncMilli)
    }

    suspend fun getArchive(id: String) = database.archiveDao().getArchive(id)

    suspend fun getRandomArchive() = database.archiveDao().getRandom(false)

    suspend fun deleteArchive(id: String) = deleteArchives(listOf(id))

    suspend fun setArchiveNewFlag(id: String) = withContext(Dispatchers.IO) {
        database.archiveDao().updateNewFlag(id, false)
        WebHandler.setArchiveNewFlag(id)
    }

    private fun getThumbDir(cacheDir: File, id: String) : File {
        val thumbDir = File(cacheDir, "thumbs/${id.substring(0, 3)}")
        if (!thumbDir.exists())
            thumbDir.mkdirs()

        return thumbDir
    }

    fun clearThumbnails(context: Context) {
        val thumbDir = File(context.noBackupFilesDir, "thumbs")
        if (thumbDir.exists())
            thumbDir.deleteRecursively()
    }

    suspend fun refreshThumbnail(id: String, context: Context, page: Int? = null) : File? {
        return withContext(Dispatchers.IO) {
            val thumbDir = getThumbDir(context.noBackupFilesDir, id)
            val image = File(thumbDir, "$id.jpg")
            if (image.exists())
                image.delete()

            getArchiveImage(id, context, page)
        }
    }

    suspend fun getArchiveImage(archive: ArchiveBase, context: Context) = getArchiveImage(archive.id, context)
    suspend fun getArchiveImage(archive: Archive, context: Context) = getArchiveImage(archive.id, context)

    suspend fun getArchiveImage(id: String, context: Context, page: Int? = null) : File? {
        val thumbDir = getThumbDir(context.noBackupFilesDir, id)

        val image = File(thumbDir, "$id.jpg")
        if (!image.exists())
            withContext(Dispatchers.IO) { WebHandler.downloadThumb(context, id, page)?.use { image.outputStream().use { f -> it.copyTo(f) } } } ?: return null

        return image
    }
}