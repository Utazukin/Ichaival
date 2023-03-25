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

import android.content.ContentValues
import androidx.paging.PagingSource
import androidx.room.*
import com.utazukin.ichaival.*
import com.utazukin.ichaival.reader.ScaleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

typealias GetArchivesFunc = suspend (List<String>, Int, Int) -> List<Archive>
typealias GetArchivesBigFunc = suspend (Int, Int, Boolean) -> List<Archive>

@Dao
interface ArchiveDao {
    @Query("Select * from archive limit :limit offset :offset")
    suspend fun getArchives(offset: Int, limit: Int): List<Archive>

    @Query("Select * from archive where id in (:ids) limit :limit offset :offset")
    suspend fun getArchives(ids: List<String>, offset: Int, limit: Int): List<Archive>

    @Query("Select count(id) from archive")
    suspend fun getArchiveCount() : Int

    @Query("Select * from archive order by titleSortIndex desc limit :limit offset :offset")
    suspend fun getTitleDescending(offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where id in (:ids) order by titleSortIndex desc limit :limit offset :offset")
    suspend fun getTitleDescending(ids: List<String>, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive order by titleSortIndex asc limit :limit offset :offset")
    suspend fun getTitleAscending(offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where id in (:ids) order by titleSortIndex asc limit :limit offset :offset")
    suspend fun getTitleAscending(ids: List<String>, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where not :onlyNew or isNew = true order by titleSortIndex desc")
    fun getTitleDescendingSource(onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive where id in (:ids) and (not :onlyNew or isNew = true) order by titleSortIndex desc")
    fun getTitleDescendingSource(ids: List<String>, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive where not :onlyNew or isNew = true order by titleSortIndex asc")
    fun getTitleAscendingSource(onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive where id in (:ids) and (not :onlyNew or isNew = true) order by titleSortIndex asc")
    fun getTitleAscendingSource(ids: List<String>, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive order by dateAdded desc limit :limit offset :offset")
    suspend fun getDateDescending(offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where id in (:ids) order by dateAdded desc limit :limit offset :offset")
    suspend fun getDateDescending(ids: List<String>, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive order by dateAdded asc limit :limit offset :offset")
    suspend fun getDateAscending(offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where id in (:ids) order by dateAdded asc limit :limit offset :offset")
    suspend fun getDateAscending(ids: List<String>, offset: Int = 0, limit: Int = Int.MAX_VALUE) : List<Archive>

    @Query("Select * from archive where not :onlyNew or isNew = true order by dateAdded desc")
    fun getDateDescendingSource(onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive where id in (:ids) and (not :onlyNew or isNew = true) order by dateAdded desc")
    fun getDateDescendingSource(ids: List<String>, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive where not :onlyNew or isNew = true order by dateAdded asc")
    fun getDateAscendingSource(onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive where id in (:ids) and (not :onlyNew or isNew = true) order by dateAdded asc")
    fun getDateAscendingSource(ids: List<String>, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive order by random() limit 1")
    suspend fun getRandom() : Archive

    @Query("Select archive.* from archive left join readertab on archive.id = readertab.id where readertab.id is null order by random() limit 1")
    suspend fun getRandomExcludeBookmarked() : Archive?

    @Query("Select * from archive where id = :id limit 1")
    suspend fun getArchive(id: String) : Archive?

    @Query("update archive set pageCount = :pageCount where id = :id and pageCount <= 0")
    suspend fun updatePageCount(id: String, pageCount: Int)

    @Query("Update archive set isNew = :isNew where id = :id")
    suspend fun updateNewFlag(id: String, isNew: Boolean)

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

    @Update(entity = Archive::class)
    suspend fun updateTitleSort(sort: List<TitleSortArchive>)

    @SkipQueryVerification
    @Query("Select * from archive join search on search.id = archive.id where not :onlyNew or archive.isNew = true order by search.position limit :limit offset :offset")
    suspend fun getArchivesBig(offset: Int, limit: Int, onlyNew: Boolean) : List<Archive>

    @SkipQueryVerification
    @Query("Select * from archive join search on search.id = archive.id where not :onlyNew or archive.isNew order by archive.dateAdded desc limit :limit offset :offset")
    suspend fun getArchivesBigByDateDescending(offset: Int, limit: Int, onlyNew: Boolean) : List<Archive>

    @SkipQueryVerification
    @Query("Select * from archive join search on search.id = archive.id where not :onlyNew or archive.isNew order by archive.dateAdded asc limit :limit offset :offset")
    suspend fun getArchivesBigByDate(offset: Int, limit: Int, onlyNew: Boolean) : List<Archive>

    @SkipQueryVerification
    @Query("Select * from archive join search on search.id = archive.id where not :onlyNew or archive.isNew order by archive.titleSortIndex desc limit :limit offset :offset")
    suspend fun getArchivesBigByTitleDescending(offset: Int, limit: Int, onlyNew: Boolean) : List<Archive>

    @SkipQueryVerification
    @Query("Select * from archive join search on search.id = archive.id where not :onlyNew or archive.isNew order by archive.titleSortIndex asc limit :limit offset :offset")
    suspend fun getArchivesBigByTitle(offset: Int, limit: Int, onlyNew: Boolean) : List<Archive>

    @SkipQueryVerification
    @Query("Select * from archive join search on search.id = archive.id where not :onlyNew or archive.isNew = true order by archive.dateAdded")
    fun getArchivesBigByDateDescendingSource(onlyNew: Boolean) : PagingSource<Int, Archive>

    @SkipQueryVerification
    @Query("Select * from archive join search on search.id = archive.id where not :onlyNew or archive.isNew = true order by archive.dateAdded asc")
    fun getArchivesBigByDateSource(onlyNew: Boolean) : PagingSource<Int, Archive>

    @SkipQueryVerification
    @Query("Select * from archive join search on search.id = archive.id where not :onlyNew or archive.isNew = true order by archive.titleSortIndex desc")
    fun getArchivesBigByTitleDescendingSource(onlyNew: Boolean) : PagingSource<Int, Archive>

    @SkipQueryVerification
    @Query("Select * from archive join search on search.id = archive.id where not :onlyNew or archive.isNew = true order by archive.titleSortIndex asc")
    fun getArchivesBigByTitleSource(onlyNew: Boolean) : PagingSource<Int, Archive>
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

@Database(entities = [Archive::class, ReaderTab::class], version = 6, exportSchema = false)
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

    fun getTitleDescendingSource(ids: List<String>? = null, onlyNew: Boolean = false) : PagingSource<Int, Archive> {
        return when {
            ids == null -> archiveDao().getTitleDescendingSource(onlyNew)
            ids.size < MAX_BIND_PARAMETER_CNT - 2 -> archiveDao().getTitleDescendingSource(ids, onlyNew)
            else -> {
                createSearchTable(ids, false)
                archiveDao().getArchivesBigByTitleDescendingSource(onlyNew)
            }
        }
    }

    fun getTitleAscendingSource(ids: List<String>? = null, onlyNew: Boolean = false) : PagingSource<Int, Archive> {
        return when {
            ids == null -> archiveDao().getTitleAscendingSource(onlyNew)
            ids.size < MAX_BIND_PARAMETER_CNT - 2 -> archiveDao().getTitleAscendingSource(ids, onlyNew)
            else -> {
                createSearchTable(ids, false)
                archiveDao().getArchivesBigByTitleSource(onlyNew)
            }
        }
    }

    fun getDateDescendingSource(ids: List<String>? = null, onlyNew: Boolean = false) : PagingSource<Int, Archive> {
        return when {
            ids == null -> archiveDao().getDateDescendingSource(onlyNew)
            ids.size < MAX_BIND_PARAMETER_CNT - 2 -> archiveDao().getDateDescendingSource(ids, onlyNew)
            else -> {
                createSearchTable(ids, false)
                archiveDao().getArchivesBigByDateDescendingSource(onlyNew)
            }
        }
    }

    fun getDateAscendingSource(ids: List<String>? = null, onlyNew: Boolean = false) : PagingSource<Int, Archive> {
        return when {
            ids == null -> archiveDao().getDateAscendingSource(onlyNew)
            ids.size < MAX_BIND_PARAMETER_CNT - 2 -> archiveDao().getDateAscendingSource(ids, onlyNew)
            else -> {
                createSearchTable(ids, false)
                archiveDao().getArchivesBigByDateSource(onlyNew)
            }
        }
    }

    suspend fun getTitleDescending(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE, onlyNew: Boolean = false) : List<Archive> {
        return when {
            ids == null -> archiveDao().getTitleDescending(offset, limit)
            ids.size < MAX_BIND_PARAMETER_CNT - 2 -> getArchives(ids, offset, limit, archiveDao()::getTitleDescending)
            else -> getArchivesBig(ids, offset, limit, archiveDao()::getArchivesBigByTitleDescending, onlyNew)
        }
    }

    suspend fun getTitleAscending(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE, onlyNew: Boolean = false) : List<Archive> {
        return when {
            ids == null -> archiveDao().getTitleAscending(offset, limit)
            ids.size < MAX_BIND_PARAMETER_CNT - 2 -> getArchives(ids, offset, limit, archiveDao()::getTitleAscending)
            else -> getArchivesBig(ids, offset, limit, archiveDao()::getArchivesBigByTitle, onlyNew)
        }
    }
    suspend fun getDateDescending(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE, onlyNew: Boolean = false) : List<Archive> {
        return when {
            ids == null -> archiveDao().getDateDescending(offset, limit)
            ids.size < MAX_BIND_PARAMETER_CNT - 2 -> getArchives(ids, offset, limit, archiveDao()::getDateDescending)
            else -> getArchivesBig(ids, offset, limit, archiveDao()::getArchivesBigByDateDescending, onlyNew)
        }
    }

    suspend fun getDateAscending(ids: List<String>?, offset: Int = 0, limit: Int = Int.MAX_VALUE, onlyNew: Boolean = false) : List<Archive> {
        return when {
            ids == null -> archiveDao().getDateAscending(offset, limit)
            ids.size < MAX_BIND_PARAMETER_CNT - 2 -> getArchives(ids, offset, limit, archiveDao()::getDateAscending)
            else -> getArchivesBig(ids, offset, limit, archiveDao()::getArchivesBigByDate, onlyNew)
        }
    }

    suspend fun getArchives(ids: List<String>, offset: Int, limit: Int) : List<Archive> {
        return when {
            ids.size < MAX_BIND_PARAMETER_CNT - 2 -> archiveDao().getArchives(ids, offset, limit)
            else -> getArchivesBig(ids, offset, limit)
        }
    }

    private fun createSearchTable(ids: List<String>, useIndex: Boolean) {
        with(openHelper.writableDatabase) {
            try {
                beginTransaction()
                execSQL("drop table if exists search")
                val value = ContentValues()
                if (useIndex) {
                    execSQL("create table if not exists search (id text primary key, position integer unique)")
                    for (i in ids.indices) {
                        with(value) {
                            clear()
                            put("id", ids[i])
                            put("position", i)
                        }
                        insert("search", OnConflictStrategy.IGNORE, value)
                    }
                } else {
                    execSQL("create table if not exists search (id text primary key)")
                    for (id in ids) {
                        with(value) {
                            clear()
                            put("id", id)
                        }
                        insert("search", OnConflictStrategy.IGNORE, value)
                    }
                }
                setTransactionSuccessful()
            }
            finally {
                endTransaction()
            }
        }
    }

    private suspend fun createSearchTableSuspend(ids: List<String>, useIndex: Boolean) = withContext(Dispatchers.IO) { createSearchTable(ids, useIndex) }

    private suspend fun getArchivesBig(ids: List<String>, offset: Int, limit: Int, dataFunc: GetArchivesBigFunc? = null, onlyNew: Boolean = false) : List<Archive> {
        return if (dataFunc == null) {
            val idOrder = ids.filterIndexed { i, _ -> i >= offset && i < offset + limit }
            createSearchTableSuspend(idOrder, true)
            archiveDao().getArchivesBig(0, -1, onlyNew)
        } else {
            createSearchTableSuspend(ids, false)
            dataFunc(offset, limit, onlyNew)
        }
    }


    private suspend fun getArchives(ids: List<String>, offset: Int, limit: Int, dataFunc: GetArchivesFunc) : List<Archive> {
        return when {
            offset >= ids.size -> emptyList()
            else -> dataFunc(ids, offset, limit)
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

