/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2024 Utazukin
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
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import com.utazukin.ichaival.Archive
import com.utazukin.ichaival.ArchiveBase
import com.utazukin.ichaival.ArchiveCategory
import com.utazukin.ichaival.ArchiveCategoryFull
import com.utazukin.ichaival.ArchiveFull
import com.utazukin.ichaival.ArchiveJson
import com.utazukin.ichaival.ReaderTab
import com.utazukin.ichaival.StaticCategoryRef
import org.json.JSONObject

@Dao
@RewriteQueriesToDropUnusedColumns
interface ArchiveDao {
    @Query("Select * from archive limit :limit offset :offset")
    suspend fun getArchives(offset: Int, limit: Int): List<ArchiveBase>

    @Query("Select count(id) from archive")
    suspend fun getArchiveCount() : Int

    @Query("Select * from archive where not :onlyNew or isNew order by titleSortIndex desc")
    fun getTitleDescendingSource(onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive where id in (:ids) and (not :onlyNew or isNew) order by titleSortIndex desc")
    fun getTitleDescendingSource(ids: List<String>, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive where not :onlyNew or isNew order by titleSortIndex asc")
    fun getTitleAscendingSource(onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive where id in (:ids) and (not :onlyNew or isNew) order by titleSortIndex asc")
    fun getTitleAscendingSource(ids: List<String>, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive where not :onlyNew or isNew order by dateAdded desc")
    fun getDateDescendingSource(onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive where id in (:ids) and (not :onlyNew or isNew) order by dateAdded desc")
    fun getDateDescendingSource(ids: List<String>, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive where not :onlyNew or isNew order by dateAdded asc")
    fun getDateAscendingSource(onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive where id in (:ids) and (not :onlyNew or isNew) order by dateAdded asc")
    fun getDateAscendingSource(ids: List<String>, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive where not :onlyNew or isNew order by random() limit 1")
    suspend fun getRandom(onlyNew: Boolean) : Archive

    @Query("Select archive.* from archive left join readertab on archive.id = readertab.id where readertab.id is null and (not :onlyNew or isNew) order by random() limit 1")
    suspend fun getRandomExcludeBookmarked(onlyNew: Boolean) : Archive?

    @Query("Select * from archive join search on searchText = :search and archive.id = archiveId and (not :onlyNew or isNew) order by random() limit 1")
    suspend fun getRandom(search: String, onlyNew: Boolean) : Archive

    @Query("Select archive.* from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId and (not :onlyNew or isNew) order by random() limit 1")
    suspend fun getRandomFromCategory(categoryId: String, onlyNew: Boolean) : Archive

    @Query("Select archive.* from archive join search on searchText = :search and archive.id = archiveId and (not :onlyNew or isNew) " +
            "left join readertab on archive.id = readertab.id where readertab.id is null order by random() limit 1")
    suspend fun getRandomExcludeBookmarked(search: String, onlyNew: Boolean) : Archive?

    @Query("Select archive.* from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId and (not :onlyNew or isNew) " +
            "left join readertab on archive.id = readertab.id where readertab.id is null order by random() limit 1")
    suspend fun getRandomFromCategoryExcludeBookmarked(categoryId: String, onlyNew: Boolean) : Archive?

    @Query("Select * from archive where id = :id limit 1")
    suspend fun getArchive(id: String) : Archive?

    @Query("update archive set pageCount = :pageCount where id = :id and pageCount != :pageCount")
    suspend fun updatePageCount(id: String, pageCount: Int)

    @Query("Update archive set isNew = :isNew where id = :id")
    suspend fun updateNewFlag(id: String, isNew: Boolean)

    @Query("Update archive set currentPage = :page where id = :id")
    suspend fun updateBookmark(id: String, page: Int)

    @Query("Update archive set currentPage = -1 where id = :id")
    suspend fun removeBookmark(id: String)

    @Query("Update archive set currentPage = -1 where id in (:ids)")
    suspend fun removeAllBookmarks(ids: List<String>)

    @Query("Select * from archive where currentPage > 0")
    suspend fun getInProgressArchives() : List<Archive>

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: ArchiveCategoryFull)

    @Upsert
    suspend fun insertStaticCategories(references: Collection<StaticCategoryRef>)

    @Query("Delete from staticCategoryRef where categoryId = :categoryId and archiveId = :archiveId")
    suspend fun removeFromCategory(categoryId: String, archiveId: String)

    @Query("Delete from staticcategoryref where updatedAt < :updateTime")
    suspend fun removeOutdatedStaticCategories(updateTime: Long)

    @Query("Delete from archivecategory where updatedAt < :updateTime")
    suspend fun removeOutdatedCategories(updateTime: Long)

    @Query("Delete from staticcategoryref where archiveId in (select archiveId from staticcategoryref join archive on archiveId = archive.id where archive.updatedAt < :updateTime)")
    suspend fun removeOldCategoryReferences(updateTime: Long)

    @Query("Select * from archivecategory order by pinned")
    suspend fun getAllCategories() : List<ArchiveCategory>

    @Query("Select archivecategory.* from archivecategory join staticcategoryref on archiveId = :archiveId and archivecategory.id = categoryId")
    suspend fun getCategoryArchives(archiveId: String) : List<ArchiveCategory>

    @Query("Select archive.* from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId where not :onlyNew or archive.isNew order by archive.titleSortIndex asc")
    fun getStaticCategoryArchiveTitleAsc(categoryId: String, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select archive.* from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId where not :onlyNew or archive.isNew order by archive.titleSortIndex desc")
    fun getStaticCategoryArchiveTitleDesc(categoryId: String, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select archive.* from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId where not :onlyNew or archive.isNew order by archive.dateAdded asc")
    fun getStaticCategoryArchiveDateAsc(categoryId: String, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select archive.* from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId where not :onlyNew or archive.isNew order by archive.dateAdded desc")
    fun getStaticCategoryArchiveDateDesc(categoryId: String, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSearch(reference: SearchArchiveRef)

    @Query("Delete from search")
    suspend fun clearSearchCache()

    @Query("Select count(archiveId) from search where searchText = :search")
    suspend fun getCachedSearchCount(search: String) : Int

    @Query("Select * from archive join search on searchText = :search and archive.id = archiveId where not :onlyNew or isNew order by titleSortIndex asc")
    fun getSearchResultsTitleAscending(search: String, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive join search on searchText = :search and archive.id = archiveId where not :onlyNew or isNew order by titleSortIndex desc")
    fun getSearchResultsTitleDescending(search: String, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive join search on searchText = :search and archive.id = archiveId where not :onlyNew or isNew order by dateAdded asc")
    fun getSearchResultsDateAscending(search: String, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive join search on searchText = :search and archive.id = archiveId where not :onlyNew or isNew order by dateAdded desc")
    fun getSearchResultsDateDescending(search: String, onlyNew: Boolean) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive where id in (select id from archive join search on searchText = :search and archive.id = archiveId order by random() limit :count)")
    fun getSearchResultsRandom(search: String, count: Int) : PagingSource<Int, ArchiveBase>

    @Query("Select * from archive where id in (select id from archive order by random() limit :count)")
    fun getRandomSource(count: Int) : PagingSource<Int, ArchiveBase>

    @Query("Select archive.* from archive where archive.id in (select archive.id from archive join staticcategoryref on categoryId = :categoryId and archive.id = archiveId order by random() limit :count)")
    fun getRandomCategorySource(categoryId: String, count: Int) : PagingSource<Int, ArchiveBase>
}

class DatabaseTypeConverters {
    @TypeConverter
    fun fromMap(value: Map<String, List<String>>) : String {
        val builder = StringBuilder()
        for ((namespace, tags) in value) {
            if (namespace != "global") {
                for (tag in tags) {
                    with(builder) {
                        append(namespace)
                        append(':')
                        append(tag)
                        append(", ")
                    }
                }
            } else {
                for (tag in tags) {
                    with(builder) {
                        append(tag)
                        append(", ")
                    }
                }
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
}

