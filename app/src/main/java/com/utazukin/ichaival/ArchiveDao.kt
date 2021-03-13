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

import androidx.paging.DataSource
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val MAX_PARAMETER_COUNT = 999

typealias GetArchivesFunc = (List<String>, Int, Int) -> List<Archive>

@Dao
interface ArchiveDao {
    @Query("Select * from archive")
    suspend fun getAll() : List<Archive>

    @Query("Select count(id) from archive")
    fun getArchiveCount() : Int

    @Query("Select * from archive order by :sortField collate nocase desc")
    fun getAllDescending(sortField: String) : List<Archive>

    @Query("Select * from archive order by :sortField collate nocase asc")
    fun getAllAscending(sortField: String) : List<Archive>

    @Query("Select * from archive order by dateAdded desc limit :limit offset :offset")
    fun getDateDescending(offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where id in (:ids) order by dateAdded desc limit :limit offset :offset")
    fun getDateDescending(ids: List<String>, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive order by title collate nocase desc limit :limit offset :offset")
    fun getTitleDescending(offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where id in (:ids) order by title collate nocase desc limit :limit offset :offset")
    fun getTitleDescending(ids: List<String>, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive order by dateAdded asc limit :limit offset :offset")
    fun getDateAscending(offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where id in (:ids) order by dateAdded asc limit :limit offset :offset")
    fun getDateAscending(ids: List<String>, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive order by title collate nocase asc limit :limit offset :offset")
    fun getTitleAscending(offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where id in (:ids) order by title collate nocase asc limit :limit offset :offset")
    fun getTitleAscending(ids: List<String>, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where id = :id limit 1")
    suspend fun getArchive(id: String) : Archive?

    @Query("Select title from archive where id = :id limit 1")
    fun getArchiveTitle(id: String) : String?

    @Query("Update archive set isNew = :isNew where id = :id")
    fun updateNewFlag(id: String, isNew: Boolean)

    @Query("Select id from archive")
    fun getAllIds() : List<String>

    @Query("Select currentPage from archive where id = :id and currentPage >= 0 limit 1")
    fun getBookmarkedPage(id: String) : Int?

    @Query("Update archive set currentPage = :page where id = :id")
    suspend fun updateBookmark(id: String, page: Int)

    @Query("Update archive set currentPage = -1 where id = :id")
    fun removeBookmark(id: String)

    @Query("Update archive set currentPage = -1 where id in (:ids)")
    fun removeAllBookmarks(ids: List<String>)

    @Query("Select * from readertab order by `index`")
    fun getBookmarks() : List<ReaderTab>

    @Query("Select exists (select 1 from readertab where id = :id limit 1)")
    suspend fun isBookmarked(id: String) : Boolean

    @Query("Select * from readertab where id = :id limit 1")
    suspend fun getBookmark(id: String) : ReaderTab?

    @Query("Select * from readertab order by `index`")
    fun getDataBookmarks() : DataSource.Factory<Int, ReaderTab>

    @Query("Delete from archive where id = :id")
    fun removeArchive(id: String)

    @Query("Delete from archive where id in (:ids)")
    fun removeArchives(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(vararg archives: Archive)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(archives: List<Archive>)

    @Delete
    fun removeArchive(archive: Archive)

    @Delete
    fun removeBookmark(tab: ReaderTab)

    @Delete
    fun clearBookmarks(tabs: List<ReaderTab>)

    @Update
    fun updateArchive(archive: Archive)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(tab: ReaderTab)

    @Update
    suspend fun updateBookmark(tab: ReaderTab)

    @Update
    fun updateBookmarks(tabs: List<ReaderTab>)

    @Query("Update archive set title = :title, tags = :tags, isNew = :isNew, dateAdded = :dateAdded, pageCount = :pageCount, currentPage = :currentPage where id = :id")
    fun updateFromJson(id: String, title: String, isNew: Boolean, dateAdded: Int, pageCount: Int, currentPage: Int, tags: Map<String, List<String>>)

    @Transaction
    fun updateFromJson(archives: Collection<ArchiveJson>) {
        for (archive in archives)
            updateFromJson(archive.id, archive.title, archive.isNew, archive.dateAdded, archive.pageCount, archive.currentPage, archive.tags)
    }
}

class DatabaseTypeConverters {
    @TypeConverter
    fun fromMap(value: Map<String, List<String>>) : String {
        val jsonObject = JSONObject(value)
        return jsonObject.toString()
    }

    @TypeConverter
    fun fromString(json: String) : Map<String, List<String>> {
        val jsonObject = JSONObject(json)
        val map = mutableMapOf<String, List<String>>()
        for (key in jsonObject.keys()) {
            val tagsArray = jsonObject.getJSONArray(key)
            val tags = MutableList<String>(tagsArray.length()) { tagsArray.getString(it) }
            map[key] = tags
        }

        return map
    }
}

@Database(entities = [Archive::class, ReaderTab::class], version = 2, exportSchema = false)
@TypeConverters(DatabaseTypeConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao

    @Transaction
    suspend fun insertAndRemove(archives: Map<String, ArchiveJson>) {
        val currentIds = archiveDao().getAllIds()
        val keys = archives.keys
        val allIds = currentIds.union(keys)

        val toAdd = keys.minus(currentIds)
        if (toAdd.isNotEmpty())
            archiveDao().insertAll(toAdd.map { Archive(archives.getValue(it)) })

        if (ServerManager.checkVersionAtLeast(7, 7, 7)) {
            for (archive in archives) {
                val isBookmarked = archiveDao().isBookmarked(archive.key)
                if (archive.value.currentPage > 0 && !isBookmarked) {
                    val tab = ReaderTabHolder.createTab(archive.key, archive.value.title, archive.value.currentPage)
                    addBookmark(tab)
                } else if (isBookmarked)
                    updateBookmark(archive.key, archive.value.currentPage)
            }
        }

        val toRemove = allIds.subtract(keys)
        if (toRemove.isNotEmpty()) {
            //Room has a max variable count of 999.
            if (toRemove.size <= MAX_PARAMETER_COUNT)
                archiveDao().removeArchives(toRemove.toList())
            else {
                for (splitList in toRemove.chunked(MAX_PARAMETER_COUNT))
                    archiveDao().removeArchives(splitList)
            }
        }
    }

    suspend fun updateExisting(archives: Map<String, ArchiveJson>) = withContext(Dispatchers.IO) { archiveDao().updateFromJson(archives.values) }

    @Transaction
    suspend fun addBookmark(tab: ReaderTab) {
        archiveDao().addBookmark(tab)
        archiveDao().updateBookmark(tab.id, tab.page)
    }

    @Transaction
    suspend fun updateBookmark(id: String, page: Int) : Boolean {
        val tab = archiveDao().getBookmark(id)
        return tab?.let {
            it.page = page
            archiveDao().updateBookmark(it)
            archiveDao().updateBookmark(it.id, page)
            true
        } ?: false
    }

    @Transaction
    suspend fun removeBookmark(id: String) : Boolean {
        val tab = archiveDao().getBookmark(id)
        if (tab != null) {
            removeBookmark(tab)
            return true
        }

        return false
    }

    fun getTitleDescending(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive> {
        return if (ids == null)
            archiveDao().getTitleDescending(offset, limit)
        else
            getArchives(ids, offset, limit, archiveDao()::getTitleDescending)
    }

    fun getTitleAscending(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive> {
        return if (ids == null)
            archiveDao().getTitleAscending(offset, limit)
        else
           getArchives(ids, offset, limit, archiveDao()::getTitleAscending)
    }

    fun getDateDescending(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive> {
        return if (ids == null)
            archiveDao().getDateDescending(offset, limit)
        else
            getArchives(ids, offset, limit, archiveDao()::getDateDescending)
    }

    fun getDateAscending(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive> {
        return if (ids == null)
            archiveDao().getDateAscending(offset, limit)
        else
            getArchives(ids, offset, limit, archiveDao()::getDateAscending)
    }

    private fun getArchives(ids: List<String>, offset: Int, limit: Int, dataFunc: GetArchivesFunc) : List<Archive> {
        return if (ids.size <= MAX_PARAMETER_COUNT - 2)
            dataFunc(ids, offset, limit)
        else {
            val archives = mutableListOf<Archive>()
            for (split in ids.chunked(MAX_PARAMETER_COUNT - 2))
                archives.addAll(dataFunc(ids, offset, limit))
            archives
        }
    }

    @Transaction
    fun removeBookmark(tab: ReaderTab) {
        val tabs = archiveDao().getBookmarks()
        val adjustedTabs = tabs.filter { it.index > tab.index }
        for (adjustedTab in adjustedTabs)
            --adjustedTab.index

        archiveDao().removeBookmark(tab.id)
        archiveDao().removeBookmark(tab)
        archiveDao().updateBookmarks(adjustedTabs)
    }

    @Transaction
    fun clearBookmarks() : List<String> {
        val tabs = archiveDao().getBookmarks()
        val removedTabs = tabs.map { it.id }
        if (tabs.isNotEmpty()) {
            archiveDao().removeAllBookmarks(removedTabs)
            archiveDao().clearBookmarks(tabs)
        }

        return removedTabs
    }
}

