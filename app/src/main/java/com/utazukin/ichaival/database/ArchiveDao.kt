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

import androidx.paging.PagingSource
import androidx.room.*
import com.utazukin.ichaival.*
import com.utazukin.ichaival.reader.ScaleType
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

    @Query("Select * from archive where id in (:ids) limit :limit offset :offset")
    fun getArchives(ids: List<String>, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select title from archive where id = :id limit 1")
    fun getArchiveTitle(id: String) : String?

    @Query("update archive set pageCount = :pageCount where id = :id and pageCount <= 0")
    fun updatePageCount(id: String, pageCount: Int)

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

    @Query("Select count(id) from readertab")
    suspend fun getBookmarkCount() : Int

    @Query("Select * from readertab order by `index`")
    fun getBookmarks() : List<ReaderTab>

    @Query("Select id from readertab")
    fun getBookmarkedIds() : List<String>

    @Query("Select exists (select id from readertab where id = :id limit 1)")
    suspend fun isBookmarked(id: String) : Boolean

    @Query("Select * from readertab where id = :id limit 1")
    suspend fun getBookmark(id: String) : ReaderTab?

    @Query("Select * from readertab order by `index`")
    fun getDataBookmarks() : PagingSource<Int, ReaderTab>

    @Query("Delete from archive where id = :id")
    fun removeArchive(id: String)

    @Query("Delete from archive where id in (:ids)")
    fun removeArchives(ids: Collection<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(vararg archives: Archive)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(archives: List<Archive>)

    @Insert(onConflict = OnConflictStrategy.IGNORE, entity = Archive::class)
    fun insertAllJson(archives: Collection<ArchiveJson>)

    @Delete
    fun removeArchive(archive: Archive)

    @Delete
    fun removeBookmark(tab: ReaderTab)

    @Query("Delete from readertab")
    fun clearBookmarks()

    @Update
    fun updateArchive(archive: Archive)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(tab: ReaderTab)

    @Update
    suspend fun updateBookmark(tab: ReaderTab)

    @Update
    fun updateBookmarks(tabs: List<ReaderTab>)

    @Query("Update archive set title = :title, tags = :tags, isNew = :isNew, dateAdded = :dateAdded, pageCount = :pageCount, currentPage = :currentPage where id = :id")
    fun updateFromJson(id: String, title: String, isNew: Boolean, dateAdded: Long, pageCount: Int, currentPage: Int, tags: Map<String, List<String>>)

    @Update(entity = Archive::class)
    fun updateFromJson(archives: Collection<ArchiveJson>)
}

class DatabaseTypeConverters {
    @TypeConverter
    fun fromMap(value: Map<String, List<String>>) = JSONObject(value).toString()

    @TypeConverter
    fun fromString(json: String) : Map<String, List<String>> {
        val jsonObject = JSONObject(json)
        return buildMap(jsonObject.length()) {
            for (key in jsonObject.keys()) {
                val tagsArray = jsonObject.getJSONArray(key)
                val tags = List(tagsArray.length()) { tagsArray.getString(it) }
                put(key, tags)
            }
        }
    }
}

@Database(entities = [Archive::class, ReaderTab::class], version = 3, exportSchema = false)
@TypeConverters(DatabaseTypeConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao

    @Transaction
    suspend fun insertAndRemove(archives: Map<String, ArchiveJson>) {
        archiveDao().insertAllJson(archives.values)

        val allIds = archiveDao().getAllIds().toSet()
        val toRemove = allIds subtract archives.keys
        if (toRemove.isNotEmpty()) {
            //Room has a max variable count of 999.
            if (toRemove.size <= MAX_PARAMETER_COUNT)
                archiveDao().removeArchives(toRemove)
            else {
                for (splitList in toRemove.chunked(MAX_PARAMETER_COUNT))
                    archiveDao().removeArchives(splitList)
            }
        }

        archiveDao().updateFromJson(archives.values)

        if (ServerManager.serverTracksProgress) {
            val bookmarks = archiveDao().getBookmarkedIds()
            for (id in bookmarks) {
                archives[id]?.let { archiveDao().updateBookmark(id, it.currentPage) }
            }
            var bookmarkCount = bookmarks.size
            for ((id, archive) in archives) {
                if (archive.currentPage > 0 && !bookmarks.contains(id)) {
                    val tab = ReaderTab(id, archive.title, bookmarkCount++, archive.currentPage)
                    archiveDao().addBookmark(tab)
                }
            }
        }
    }

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

    suspend fun updateBookmarkScaleType(id: String, scaleType: ScaleType) : Boolean {
        val tab = archiveDao().getBookmark(id)
        return tab?.let {
            it.scaleType = scaleType
            archiveDao().updateBookmark(tab)
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

    fun getArchives(ids: List<String>, offset: Int, limit: Int) : List<Archive> {
        val idOrder = ids.withIndex().associate { it.value to it.index }
        return getArchives(ids, offset, limit, archiveDao()::getArchives).sortedBy { idOrder[it.id] }
    }

    private fun getArchives(ids: List<String>, offset: Int, limit: Int, dataFunc: GetArchivesFunc) : List<Archive> {
        return if (ids.size <= MAX_PARAMETER_COUNT - 2)
            dataFunc(ids, offset, limit)
        else {
            buildList {
                for (split in ids.chunked(MAX_PARAMETER_COUNT - 2))
                    addAll(dataFunc(ids, offset, limit))
            }
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
    suspend fun insertBookmark(tab: ReaderTab) {
        val tabs = archiveDao().getBookmarks()
        val adjustedTabs = tabs.filter { it.index >= tab.index }
        for (adjustedTab in adjustedTabs)
            ++adjustedTab.index

        archiveDao().addBookmark(tab)
        archiveDao().updateBookmark(tab.id, tab.page)
        archiveDao().updateBookmarks(adjustedTabs)
    }

    @Transaction
    fun clearBookmarks() : List<String> {
        val tabs = archiveDao().getBookmarkedIds()
        if (tabs.isNotEmpty()) {
            archiveDao().removeAllBookmarks(tabs)
            archiveDao().clearBookmarks()
        }

        return tabs
    }
}

