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

import androidx.paging.DataSource
import androidx.room.*
import org.json.JSONArray
import org.json.JSONObject

@Dao
interface ArchiveDao {
    @Query("Select * from archive")
    fun getAll() : List<Archive>

    @Query("Select * from archive order by :sortField collate nocase desc")
    fun getAllDescending(sortField: String) : List<Archive>

    @Query("Select * from archive order by :sortField collate nocase asc")
    fun getAllAscending(sortField: String) : List<Archive>

    @Query("Select * from archive order by dateAdded desc")
    fun getDateDescending() : List<Archive>

    @Query("Select * from archive where id in (:ids) order by dateAdded desc")
    fun getDateDescending(ids: List<String>) : List<Archive>

    @Query("Select * from archive order by title collate nocase desc")
    fun getTitleDescending() : List<Archive>

    @Query("Select * from archive where id in (:ids) order by title collate nocase desc")
    fun getTitleDescending(ids: List<String>) : List<Archive>

    @Query("Select * from archive order by dateAdded asc")
    fun getDateAscending() : List<Archive>

    @Query("Select * from archive where id in (:ids) order by dateAdded asc")
    fun getDateAscending(ids: List<String>) : List<Archive>

    @Query("Select * from archive order by title collate nocase asc")
    fun getTitleAscending() : List<Archive>

    @Query("Select * from archive where id in (:ids) order by title collate nocase asc")
    fun getTitleAscending(ids: List<String>) : List<Archive>

    @Query("Select * from archive order by dateAdded desc")
    fun getDataDateDescending() : DataSource.Factory<Int, Archive>

    @Query("Select * from archive where id in (:ids) order by dateAdded desc")
    fun getDataDateDescending(ids: List<String>) : DataSource.Factory<Int, Archive>

    @Query("Select * from archive order by title collate nocase desc")
    fun getDataTitleDescending() : DataSource.Factory<Int, Archive>

    @Query("Select * from archive where id in (:ids) order by title collate nocase desc")
    fun getDataTitleDescending(ids: List<String>) : DataSource.Factory<Int, Archive>

    @Query("Select * from archive order by dateAdded asc")
    fun getDataDateAscending() : DataSource.Factory<Int, Archive>

    @Query("Select * from archive where id in (:ids) order by dateAdded asc")
    fun getDataDateAscending(ids: List<String>) : DataSource.Factory<Int, Archive>

    @Query("Select * from archive order by title collate nocase asc")
    fun getDataTitleAscending() : DataSource.Factory<Int, Archive>

    @Query("Select * from archive where id in (:ids) order by title collate nocase asc")
    fun getDataTitleAscending(ids: List<String>) : DataSource.Factory<Int, Archive>

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
    suspend fun updateBookmark(tab: ReaderTab)

    @Update
    fun updateBookmarks(tabs: List<ReaderTab>)

    @Query("Update archive set title = :title, tags = :tags, isNew = :isNew, dateAdded = :dateAdded where id = :id")
    fun updateFromJson(id: String, title: String, isNew: Boolean, dateAdded: Int, tags: Map<String, List<String>>)

    @Transaction
    fun updateFromJson(archives: Collection<ArchiveJson>) {
        for (archive in archives)
            updateFromJson(archive.id, archive.title, archive.isNew, archive.dateAdded, archive.tags)
    }
}

class DatabaseTypeConverters {
    @TypeConverter
    fun fromMap(value: Map<String, List<String>>) : String {
        val jsonObject = JSONObject()
        for (pair in value)
            jsonObject.put(pair.key, JSONArray(pair.value))
        return jsonObject.toString()
    }

    @TypeConverter
    fun fromString(json: String) : Map<String, List<String>> {
        val jsonObject = JSONObject(json)
        val map = mutableMapOf<String, List<String>>()
        for (key in jsonObject.keys()) {
            val tagsArray = jsonObject.getJSONArray(key)
            val tags = mutableListOf<String>()
            for (i in 0 until tagsArray.length())
                tags.add(tagsArray.getString(i))
            map[key] = tags
        }

        return map
    }
}

@Database(entities = [Archive::class, ReaderTab::class], version = 1, exportSchema = false)
@TypeConverters(DatabaseTypeConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao

    @Transaction
    fun insertOrUpdate(archives: Map<String, ArchiveJson>) {
        val currentIds = archiveDao().getAllIds()
        val keys = archives.keys
        val allIds = currentIds.union(keys)
        archiveDao().updateFromJson(archives.values)

        val toAdd = keys.minus(currentIds)
        if (toAdd.isNotEmpty())
            archiveDao().insertAll(toAdd.map { Archive(archives.getValue(it)) })

        val toRemove = allIds.subtract(keys)
        if (toRemove.isNotEmpty())
            archiveDao().removeArchives(toRemove.toList())
    }

    @Transaction
    suspend fun addBookmark(tab: ReaderTab) {
        archiveDao().updateBookmark(tab)
        archiveDao().updateBookmark(tab.id, tab.page)
    }

    @Transaction
    suspend fun updateBookmark(id: String, page: Int) {
        val tab = archiveDao().getBookmark(id)
        tab?.let {
            it.page = page
            archiveDao().updateBookmark(it)
            archiveDao().updateBookmark(it.id, page)
        }
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
    fun clearBookmarks() {
        val tabs = archiveDao().getBookmarks()
        if (tabs.isNotEmpty()) {
            archiveDao().removeAllBookmarks(tabs.map { it.id })
            archiveDao().clearBookmarks(tabs)
        }
    }
}

