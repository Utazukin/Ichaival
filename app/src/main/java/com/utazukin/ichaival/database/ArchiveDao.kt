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

@Dao
interface ArchiveDao {
    @Query("Select * from archive limit :limit offset :offset")
    suspend fun getArchives(offset: Int, limit: Int): List<Archive>

    @Query("Select * from archive where id in (:ids) limit :limit offset :offset")
    suspend fun getArchives(ids: List<String>, offset: Int, limit: Int): MutableList<Archive>

    @Query("Select count(id) from archive")
    suspend fun getArchiveCount() : Int

    @Query("Select * from archive where not :onlyNew or isNew = true order by titleSortIndex desc")
    fun getTitleDescendingSource(onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive where id in (:ids) and (not :onlyNew or isNew = true) order by titleSortIndex desc")
    fun getTitleDescendingSource(ids: List<String>, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive where not :onlyNew or isNew = true order by titleSortIndex asc")
    fun getTitleAscendingSource(onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive where id in (:ids) and (not :onlyNew or isNew = true) order by titleSortIndex asc")
    fun getTitleAscendingSource(ids: List<String>, onlyNew: Boolean) : PagingSource<Int, Archive>

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

    @Upsert(entity = ArchiveFull::class)
    suspend fun insertAllJson(archives: Collection<ArchiveJson>)

    @Query("Delete from archive where updatedAt < :updateTime")
    suspend fun removeNotUpdated(updateTime: Long)

    @Query("Delete from readertab where id in (select readertab.id from readertab join archive on readertab.id = archive.id where archive.updatedAt < :updateTime)")
    suspend fun removeNotUpdatedBookmarks(updateTime: Long)

    @Delete
    suspend fun removeBookmark(tab: ReaderTab)

    @Query("Delete from readertab where id in (:ids)")
    suspend fun removeBookmarks(ids: Collection<String>)

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

    @Upsert
    suspend fun insertCategories(categories: Collection<ArchiveCategoryFull>)

    @Upsert
    suspend fun insertStaticCategories(references: Collection<StaticCategoryRef>)

    @Query("Delete from staticcategoryref where updatedAt < :updateTime")
    suspend fun removeOutdatedStaticCategories(updateTime: Long)

    @Query("Delete from archivecategory where updatedAt < :updateTime")
    suspend fun removeOutdatedCategories(updateTime: Long)

    @Query("Delete from staticcategoryref where archiveId in (select archiveId from staticcategoryref join archive on archiveId = archive.id where archive.updatedAt < :updateTime)")
    suspend fun removeOldCategoryReferences(updateTime: Long)

    @Query("Select * from archivecategory order by pinned")
    suspend fun getAllCategories() : List<ArchiveCategory>

    @Query("Select * from archivecategory join staticcategoryref on archiveId = :archiveId and archivecategory.id = categoryId")
    suspend fun getCategoryArchives(archiveId: String) : List<ArchiveCategory>

    @Query("Select archive.* from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId where not :onlyNew or archive.isNew = :onlyNew order by archive.titleSortIndex asc")
    fun getStaticCategoryArchiveTitleAsc(categoryId: String, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select archive.* from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId where not :onlyNew or archive.isNew = :onlyNew order by archive.titleSortIndex desc")
    fun getStaticCategoryArchiveTitleDesc(categoryId: String, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select archive.* from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId where not :onlyNew or archive.isNew = :onlyNew order by archive.dateAdded asc")
    fun getStaticCategoryArchiveDateAsc(categoryId: String, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select archive.* from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId where not :onlyNew or archive.isNew = :onlyNew order by archive.dateAdded desc")
    fun getStaticCategoryArchiveDateDesc(categoryId: String, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSearch(reference: SearchArchiveRef)

    @Query("Delete from search")
    suspend fun clearSearchCache()

    @Query("Select count(archiveId) from search where searchText = :search")
    suspend fun getCachedSearchCount(search: String) : Int

    @Query("Select * from archive join search on searchText = :search and archive.id = archiveId where not :onlyNew or isNew = true order by titleSortIndex asc")
    fun getSearchResultsTitleAscending(search: String, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive join search on searchText = :search and archive.id = archiveId where not :onlyNew or isNew = true order by titleSortIndex desc")
    fun getSearchResultsTitleDescending(search: String, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive join search on searchText = :search and archive.id = archiveId where not :onlyNew or isNew = true order by dateAdded asc")
    fun getSearchResultsDateAscending(search: String, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive join search on searchText = :search and archive.id = archiveId where not :onlyNew or isNew = true order by dateAdded desc")
    fun getSearchResultsDateDescending(search: String, onlyNew: Boolean) : PagingSource<Int, Archive>

    @Query("Select * from archive where id in (select id from archive join search on searchText = :search and archive.id = archiveId order by random() limit :count)")
    fun getSearchResultsRandom(search: String, count: Int) : PagingSource<Int, Archive>

    @Query("Select * from archive where id in (select id from archive order by random() limit :count)")
    fun getRandomSource(count: Int) : PagingSource<Int, Archive>

    @Query("Select archive.* from archive where archive.id in (select archive.id from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId order by random() limit :count)")
    fun getRandomCategorySource(categoryId: String, count: Int) : PagingSource<Int, Archive>
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
            for (tag in split.asSequence().map { it.trim() }) {
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

@Database(entities = [ArchiveFull::class, ReaderTab::class, ArchiveCategoryFull::class, StaticCategoryRef::class, SearchArchiveRef::class], version = 8, exportSchema = false)
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

    suspend fun removeOldArchives(updateTime: Long) = withTransaction {
        with(archiveDao()) {
            removeNotUpdatedBookmarks(updateTime)
            removeOldCategoryReferences(updateTime)
            removeNotUpdated(updateTime)
        }
    }

    suspend fun removeOutdatedCategories(updateTime: Long) = withTransaction {
        with(archiveDao()) {
            removeOutdatedStaticCategories(updateTime)
            removeOutdatedCategories(updateTime)
        }
    }

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

    suspend fun deleteArchives(ids: Collection<String>) = withTransaction {
        archiveDao().removeArchives(ids)
        archiveDao().removeBookmarks(ids)
    }

    fun getArchiveSource(sortMethod: SortMethod, descending: Boolean, onlyNew: Boolean = false) : PagingSource<Int, Archive> {
        return when {
            sortMethod == SortMethod.Alpha && descending -> archiveDao().getTitleDescendingSource(onlyNew)
            sortMethod == SortMethod.Alpha -> archiveDao().getTitleAscendingSource(onlyNew)
            sortMethod == SortMethod.Date && descending -> archiveDao().getDateDescendingSource(onlyNew)
            else -> archiveDao().getDateAscendingSource(onlyNew)
        }
    }

    fun getArchiveSearchSource(search: String, sortMethod: SortMethod, descending: Boolean, onlyNew: Boolean) : PagingSource<Int, Archive> {
        return when {
            sortMethod == SortMethod.Alpha && descending -> archiveDao().getSearchResultsTitleDescending(search, onlyNew)
            sortMethod == SortMethod.Alpha -> archiveDao().getSearchResultsTitleAscending(search, onlyNew)
            sortMethod == SortMethod.Date && descending -> archiveDao().getSearchResultsDateDescending(search, onlyNew)
            else -> archiveDao().getSearchResultsDateAscending(search, onlyNew)
        }
    }

    fun getStaticCategorySource(categoryId: String, sortMethod: SortMethod, descending: Boolean, onlyNew: Boolean = false) : PagingSource<Int, Archive> {
        return when {
            sortMethod == SortMethod.Alpha && descending -> archiveDao().getStaticCategoryArchiveTitleDesc(categoryId, onlyNew)
            sortMethod == SortMethod.Alpha -> archiveDao().getStaticCategoryArchiveTitleAsc(categoryId, onlyNew)
            sortMethod == SortMethod.Date && descending -> archiveDao().getStaticCategoryArchiveDateDesc(categoryId, onlyNew)
            else -> archiveDao().getStaticCategoryArchiveDateAsc(categoryId, onlyNew)
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

