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
import kotlin.math.min

typealias GetArchivesFunc = (List<String>, Int, Int) -> List<Archive>

@Dao
interface ArchiveDao {
    @Query("Select id, title from archive")
    fun getAllTitleSort() : List<TitleSortArchive>

    @Query("Select id, title from archive where id in (:ids)")
    fun getTitleSort(ids: List<String>) : List<TitleSortArchive>

    @Query("Select count(id) from archive")
    fun getArchiveCount() : Int

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
    fun getArchives(ids: List<String>, offset: Int = 0, limit: Int = -1) : List<Archive>

    @Query("update archive set pageCount = :pageCount where id = :id and pageCount <= 0")
    suspend fun updatePageCount(id: String, pageCount: Int)

    @Query("Update archive set isNew = :isNew where id = :id")
    suspend fun updateNewFlag(id: String, isNew: Boolean)

    @Query("Select id from archive")
    fun getAllIds() : List<String>

    @Query("Update archive set currentPage = :page where id = :id")
    suspend fun updateBookmark(id: String, page: Int)

    @Query("Update archive set currentPage = -1 where id = :id")
    suspend fun removeBookmark(id: String)

    @Query("Update archive set currentPage = -1 where id in (:ids)")
    suspend fun removeAllBookmarks(ids: List<String>)

    @Query("Select count(id) from readertab")
    suspend fun getBookmarkCount() : Int

    @Query("Select * from readertab order by `index`")
    suspend fun getBookmarks() : List<ReaderTab>

    @Query("Select id from readertab")
    suspend fun getBookmarkedIds() : List<String>

    @Query("Select exists (select id from readertab where id = :id limit 1)")
    suspend fun isBookmarked(id: String) : Boolean

    @Query("Select * from readertab where id = :id limit 1")
    suspend fun getBookmark(id: String) : ReaderTab?

    @Query("Select * from readertab order by `index`")
    fun getDataBookmarks() : PagingSource<Int, ReaderTab>

    @Query("Delete from archive where id = :id")
    suspend fun removeArchive(id: String)

    @Query("Delete from archive where id in (:ids)")
    suspend fun removeArchives(ids: Collection<String>)

    @Upsert(entity = Archive::class)
    suspend fun insertAllJson(archives: Collection<ArchiveJson>)

    @Query("Delete from archive where updatedAt < :updateTime")
    suspend fun removeNotUpdated(updateTime: Long)

    @Delete
    suspend fun removeBookmark(tab: ReaderTab)

    @Query("Delete from readertab")
    suspend fun clearBookmarks()

    @Insert
    suspend fun addBookmark(tab: ReaderTab)

    @Update
    suspend fun updateBookmark(tab: ReaderTab)

    @Update
    suspend fun updateBookmarks(tabs: List<ReaderTab>)

    @Upsert
    suspend fun upsertBookmarks(tabs: List<ReaderTab>)
}

class DatabaseTypeConverters {
    @TypeConverter
    fun fromMap(value: Map<String, List<String>>) : String {
        val builder = StringBuilder()
        for ((namespace, tags) in value) {
            if (namespace != "global") {
                for (tag in tags)
                    builder.append("$namespace:$tag, ")
            } else {
                for (tag in tags)
                    builder.append("$tag, ")
            }
        }

        if (builder.isNotEmpty())
            builder.delete(builder.length - 2, builder.length)

        return builder.toString()
    }

    @TypeConverter
    fun fromString(json: String) : Map<String, List<String>> {
        return buildMap<String, MutableList<String>> {
            val split = json.split(',')
            for (tag in split.map { it.trim() }) {
                val colonIndex = tag.indexOf(':')
                if (colonIndex >= 0) {
                    val namespace = tag.substring(0, colonIndex)
                    if (namespace == "date_added")
                        continue

                    val t = tag.substring(colonIndex + 1, tag.length)
                    val tags = getOrPut(namespace) { mutableListOf() }
                    tags.add(t)
                } else {
                    val tags = getOrPut("global") { mutableListOf() }
                    tags.add(tag)
                }
            }
        }
    }

    fun fromStringv3(json: String) : Map<String, List<String>> {
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

@Database(entities = [Archive::class, ReaderTab::class], version = 5, exportSchema = false)
@TypeConverters(DatabaseTypeConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao

    suspend fun updateArchives(archives: List<ArchiveJson>, bookmarks: Map<String, ReaderTab>?) {
        archiveDao().insertAllJson(archives)

        if (bookmarks != null) {
            var bookmarkCount = bookmarks.size
            val toUpdate = buildList {
                for (archive in archives) {
                    val bookmark = bookmarks[archive.id]
                    if (bookmark != null) {
                        bookmark.page = archive.currentPage
                        add(bookmark)
                    } else if (archive.currentPage > 0)
                        add(ReaderTab(archive.id, archive.title, bookmarkCount++, archive.currentPage))
                }
            }

            if (toUpdate.isNotEmpty())
                archiveDao().upsertBookmarks(toUpdate)
        }
    }

    suspend fun removeOldArchives(updateTime: Long) = archiveDao().removeNotUpdated(updateTime)

    suspend fun getBookmarks() = archiveDao().getBookmarks().associateBy { it.id }

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

    fun getTitleSort(ids: List<String>? = null) : List<TitleSortArchive> {
        return when {
            ids == null -> archiveDao().getAllTitleSort()
            ids.size <= MAX_BIND_PARAMETER_CNT -> archiveDao().getTitleSort(ids)
            else -> buildList {
                for (split in ids.chunked(MAX_BIND_PARAMETER_CNT))
                    addAll(archiveDao().getTitleSort(split))
            }
        }
    }

    private fun getArchives(ids: List<String>, offset: Int, limit: Int, dataFunc: GetArchivesFunc) : List<Archive> {
        val endIndex = min(offset + limit, ids.size)
        val ids = ids.subList(offset, endIndex)
        return when {
            ids.size <= MAX_BIND_PARAMETER_CNT - 2 -> dataFunc(ids, 0, -1)
            else -> buildList {
                for (split in ids.chunked(MAX_BIND_PARAMETER_CNT - 2))
                    addAll(dataFunc(split, 0, -1))
            }
        }
    }

    @Transaction
    suspend fun removeBookmark(tab: ReaderTab) {
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
    suspend fun clearBookmarks() : List<String> {
        val tabs = archiveDao().getBookmarkedIds()
        if (tabs.isNotEmpty()) {
            archiveDao().removeAllBookmarks(tabs)
            archiveDao().clearBookmarks()
        }

        return tabs
    }
}

