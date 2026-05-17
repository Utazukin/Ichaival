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

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import com.utazukin.ichaival.Archive
import com.utazukin.ichaival.ArchiveBase
import com.utazukin.ichaival.ArchiveCategory
import com.utazukin.ichaival.ArchiveCategoryFull
import com.utazukin.ichaival.ArchiveFull
import com.utazukin.ichaival.ArchiveJson
import com.utazukin.ichaival.ArchiveJsonBase
import com.utazukin.ichaival.ReaderTab
import com.utazukin.ichaival.StaticCategoryRef
import com.utazukin.ichaival.ToCEntry
import com.utazukin.ichaival.ToCEntryFull
import com.utazukin.ichaival.ToCEntryUpdate
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

@Dao
@RewriteQueriesToDropUnusedColumns
interface ArchiveDao {
    @Query("Select * from archive limit :limit offset :offset")
    suspend fun getArchives(offset: Int, limit: Int): List<ArchiveBase>

    @Query("Select count(id) from archive")
    suspend fun getArchiveCount() : Int

    @Query("Select * from archive where not :onlyNew or isNew order by random() limit 1")
    suspend fun getRandom(onlyNew: Boolean) : Archive

    @Query("Select * from archive where id = :id limit 1")
    suspend fun getArchive(id: String) : Archive?

    @Query("update archive set pageCount = :pageCount where id = :id and pageCount != :pageCount")
    suspend fun updatePageCount(id: String, pageCount: Int)

    @Query("Update archive set isNew = :isNew where id = :id")
    suspend fun updateNewFlag(id: String, isNew: Boolean)

    @Query("Update archive set currentPage = :page where id = :id")
    suspend fun updateProgress(id: String, page: Int)

    @Query("Select count(id) from readertab")
    suspend fun getBookmarkCount() : Int

    @Query("Select * from readertab order by `index`")
    suspend fun getBookmarks() : List<ReaderTab>

    @Query("Select exists (select id from readertab where id = :id and currentPage = :page limit 1)")
    suspend fun isBookmarked(id: String, page: Int) : Boolean

    @Query("Select * from readertab where id = :id and currentPage = :page limit 1")
    suspend fun getBookmark(id: String, page: Int) : ReaderTab?

    @Query("Select * from readertab order by `index`")
    fun getDataBookmarks() : PagingSource<Int, ReaderTab>

    @Query("Delete from archive where id in (:ids)")
    suspend fun removeArchives(ids: Collection<String>)

    @Upsert(entity = ArchiveFull::class)
    suspend fun insertJson(archiveJson: ArchiveJson)

    @Upsert(entity = ArchiveFull::class)
    suspend fun insertJsonBase(archiveJsonBase: ArchiveJsonBase)

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
    suspend fun updateBookmarks(tabs: List<ReaderTab>)

    @Upsert
    suspend fun insertCategories(categories: Collection<ArchiveCategoryFull>)

    @Upsert
    suspend fun insertCategory(category: ArchiveCategoryFull)

    @Upsert
    suspend fun insertStaticCategories(references: Collection<StaticCategoryRef>)

    @Upsert
    suspend fun insertStaticCategory(reference: StaticCategoryRef)

    @Query("Delete from staticCategoryRef where categoryId = :categoryId and archiveId = :archiveId")
    suspend fun removeFromCategory(categoryId: String, archiveId: String)

    @Query("Delete from staticcategoryref where updatedAt < :updateTime")
    suspend fun removeOutdatedStaticCategories(updateTime: Long)

    @Query("Delete from archivecategory where updatedAt < :updateTime")
    suspend fun removeOutdatedCategories(updateTime: Long)

    @Query("Delete from staticcategoryref where archiveId in (select archiveId from staticcategoryref join archive on archiveId = archive.id where archive.updatedAt < :updateTime)")
    suspend fun removeOldCategoryReferences(updateTime: Long)

    @Query("Select * from archivecategory order by pinned")
    fun getAllCategories() : Flow<List<ArchiveCategory>>

    @Query("Select archivecategory.* from archivecategory join staticcategoryref on archiveId = :archiveId and archivecategory.id = categoryId")
    suspend fun getCategoryArchives(archiveId: String) : List<ArchiveCategory>

    @Query("Select exists(select * from staticcategoryref where categoryId = :categoryId and archiveId = :archiveId)")
    suspend fun isInCategory(categoryId: String, archiveId: String) : Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSearch(reference: SearchArchiveRef)

    @Query("Delete from search")
    suspend fun clearSearchCache()

    @Query("Select count(archiveId) from search where searchText = :search")
    suspend fun getCachedSearchCount(search: String) : Int

    @RawQuery(observedEntities = [ArchiveFull::class])
    fun getRandomSource(query: SupportSQLiteQuery) : PagingSource<Int, ArchiveBase>

    @RawQuery(observedEntities = [ArchiveFull::class])
    fun getSearchSource(query: SupportSQLiteQuery) : PagingSource<Int, ArchiveBase>

    @RawQuery(observedEntities = [ArchiveFull::class])
    suspend fun getRandom(query: SupportSQLiteQuery) : Archive?

    @Query("Select * from toc where archiveId = :archiveId order by page asc")
    fun getToC(archiveId: String) : Flow<List<ToCEntry>>

    @Upsert
    suspend fun addToc(entries: List<ToCEntryFull>)

    @Upsert(entity = ToCEntryFull::class)
    suspend fun updateToCEntry(entry: ToCEntryUpdate)

    @Query("Select * from toc where page = :page and archiveId = :archiveId")
    suspend fun getTocEntry(page: Int, archiveId: String): ToCEntry?

    @Query("Delete from toc where page = :page and archiveId = :archiveId")
    suspend fun removeToCEntry(page: Int, archiveId: String)

    @Query("Delete from toc where updateTime < :updateTime")
    suspend fun removeOldToC(updateTime: Long)

    @Query("Update archive set currentPage = pageCount - 1 where id = :id")
    suspend fun markCompleted(id: String)
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
                if (tag.isEmpty())
                    continue

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

@Database(entities =
    [
        ArchiveFull::class,
        ReaderTab::class,
        ArchiveCategoryFull::class,
        StaticCategoryRef::class,
        SearchArchiveRef::class,
        ToCEntryFull::class
    ], version = 12, exportSchema = false)
@TypeConverters(DatabaseTypeConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao
}

