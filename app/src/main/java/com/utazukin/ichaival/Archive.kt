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

package com.utazukin.ichaival

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.google.gson.JsonObject
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Entity(tableName = "archive")
data class ArchiveFull(
    @PrimaryKey val id: String,
    @ColumnInfo val title: String,
    @ColumnInfo val dateAdded: Long,
    @ColumnInfo val isNew: Boolean,
    @ColumnInfo val tags: Map<String, List<String>>,
    @ColumnInfo(defaultValue = "0") val currentPage: Int,
    @ColumnInfo val pageCount: Int,
    @ColumnInfo val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val titleSortIndex: Int,
    @ColumnInfo val summary: String?,
    @ColumnInfo(defaultValue = "0") val isTank: Boolean = false
)

private fun containsTag(tag: String, exact: Boolean, tags: Map<String, List<String>>) : Boolean {
    val colonIndex = tag.indexOf(':')
    if (colonIndex > 0) {
        val namespace = tag.substring(0, colonIndex)
        val normalized = tag.substring(colonIndex + 1)
        val nTags = tags[namespace] ?: return false
        return if (exact) nTags.any { it.equals(normalized, ignoreCase = true) } else nTags.any { it.contains(normalized, ignoreCase = true) }
    }
    else {
        for (t in tags.values) {
            if (t.any { it.contains(tag, ignoreCase = true)})
                return true
        }
    }
    return false
}

fun MetaArchive.containsTag(tag: String, exact: Boolean) = containsTag(tag, exact, tags)
fun ArchiveBase.containsTag(tag: String, exact: Boolean) = containsTag(tag, exact, tags)

data class ArchiveBase(val id: String, val title: String, val pageCount: Int, val tags: Map<String, List<String>>)

data class ArchiveListEntry(val id: String, val title: String, val pageCount: Int)

data class ArchiveWithCategories(val archive: MetaArchive, val categories: List<ArchiveCategory>)

interface MetaArchive {
    val id: String
    val title: String
    val summary: String?
    var numPages: Int
    var currentPage: Int
    val isWebtoon: Boolean
    var isNew: Boolean
    val dateAdded: Long
    val tags: Map<String, List<String>>
    val toc: Flow<List<ToCEntry>>
    fun getThumb(page: Int): String
    fun invalidateCache()
    fun hasPage(page: Int) = numPages <= 0 || (page in 0 until numPages)
    suspend fun generateThumbs(): ThumbResult
    suspend fun getPageImage(context: Context, page: Int): String?
    suspend fun clearNewFlag()
    suspend fun extract(context: Context, forceFull: Boolean = false, silent: Boolean = false)
    suspend fun addToCEntry(name: String, page: Int)
    suspend fun removeToCEntry(page: Int)
    suspend fun getToCEntry(page: Int): ToCEntry?
}

data class Archive (
    override val id: String,
    override val title: String,
    override val dateAdded: Long,
    override var isNew: Boolean,
    override val tags: Map<String, List<String>>,
    override var currentPage: Int,
    override val summary: String?,
    @ColumnInfo(name = "pageCount") override var numPages: Int): MetaArchive {

    @delegate:Ignore
    override val isWebtoon by lazy { containsTag("webtoon", false) }

    @delegate:Ignore
    override val toc by lazy { DatabaseReader.getToC(id) }

    override suspend fun extract(context: Context, forceFull: Boolean, silent: Boolean) {
        val pages = DatabaseReader.getPageList(id, forceFull, silent)
        if (numPages > 0)
            numPages = pages.size
    }

    override fun invalidateCache() = DatabaseReader.invalidateImageCache(id)

    override suspend fun clearNewFlag() = withContext(Dispatchers.IO) {
        WebHandler.setArchiveNewFlag(id)
        DatabaseReader.setArchiveNewFlag(id)
        isNew = false
    }

    override suspend fun getPageImage(context: Context, page: Int) : String? {
        return downloadPage(context, page)
    }

    override fun getThumb(page: Int) = WebHandler.getThumbUrl(id, page)

    override suspend fun generateThumbs() = WebHandler.tryGenerateThumbs(id)

    override suspend fun addToCEntry(name: String, page: Int) {
        val entry = ToCEntryUpdate(name, page, id)
        if (WebHandler.addToCEntry(entry))
            DatabaseReader.updateToCEntry(entry)
    }

    override suspend fun removeToCEntry(page: Int) {
        if (WebHandler.removeToCEntry(id, page))
            DatabaseReader.removeToCEntry(page, id)
    }

    override suspend fun getToCEntry(page: Int) = DatabaseReader.getToCEntry(page, id)

    private suspend fun downloadPage(context: Context, page: Int) : String? {
        val downloadPath = DownloadManager.getDownloadedPage(id, page)
        if (downloadPath != null)
            return downloadPath

        val pages = DatabaseReader.getPageList(id)
        return if (page < pages.size) WebHandler.getRawImageUrl(pages[page]) else null
    }
}

@Entity(tableName = "toc", primaryKeys = ["archiveId", "page"])
data class ToCEntryFull(val name: String, val page: Int, @ColumnInfo(defaultValue = "0") val updateTime: Long, val archiveId: String)
data class ToCEntryUpdate(val name: String, val page: Int, val archiveId: String)
data class ToCEntry(val name: String, val page: Int)

open class ArchiveJsonBase(json: JsonObject, val updatedAt: Long, val titleSortIndex: Int) {
    val title: String = json.get("title").asString
    val id: String = json.get("arcid").asString
    val tags: String = json.get("tags").asString
    val pageCount = json.get("pagecount")?.asInt ?: 0
    val isNew = json.get("isnew").asString == "true"
    val summary = json.getOrNull("summary")?.asString
    val dateAdded = parseDateAdded(tags)

    @Ignore
    val toc = json.getOrNull("toc")?.asJsonArray?.let {
        List(it.size()) { i ->
            val entry = it.get(i).asJsonObject
            ToCEntryFull(
                    entry.get("name").asString,
                    entry.get("page").asInt - 1,
                    updatedAt,
                    id
            )
        }
    }
}

open class ArchiveJson(json: JsonObject, updatedAt: Long, titleSortIndex: Int) : ArchiveJsonBase(json, updatedAt, titleSortIndex) {
    val currentPage = json.get("progress")?.asInt?.minus(1) ?: 0
}

