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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
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

fun Archive.containsTag(tag: String, exact: Boolean) = containsTag(tag, exact, tags)
fun ArchiveBase.containsTag(tag: String, exact: Boolean) = containsTag(tag, exact, tags)

data class ArchiveBase(val id: String, val title: String, val pageCount: Int, val tags: Map<String, List<String>>)

data class ArchiveWithCategories(val archive: MetaArchive, val categories: List<ArchiveCategory>)

data class Tankoubon(val tank: MetaArchive, val archives: List<MetaArchive>) : MetaArchive by tank {
    @Ignore
    override val isWebtoon = archives.any { it.isWebtoon }
    @Ignore
    override var isNew = false

    @delegate:Ignore
    override val tags by lazy {
        buildMap {
            putAll(tank.tags)
            for (archive in archives) {
                for ((namespace, tags) in archive.tags) {
                    val existing = get(namespace)
                    if (existing == null)
                        put(namespace, tags.toList())
                    else {
                        val new = existing.toMutableList()
                        for (tag in tags) {
                            if (tag !in new)
                                new.add(tag)
                        }
                        put(namespace, new)
                    }
                }
            }
        }
    }

    @delegate:Ignore
    override val toc by lazy {
        channelFlow {
            coroutineScope {
                val allToc = archives.map { it.toc.stateIn(this, SharingStarted.Eagerly, emptyList()) }
                val flow = allToc.merge()
                flow.collectLatest { _ ->
                    val list = buildList {
                        var total = 0
                        for ((i, archive) in archives.withIndex()) {
                            add(ToCEntry(archive.title, total))
                            for (entry in allToc[i].value) {
                                add(ToCEntry(entry.name, entry.page + total))
                            }
                            total += archive.numPages
                        }
                    }
                    send(list)
                }
            }
        }
    }

    override fun getThumb(page: Int): String {
        val localThumb = DownloadManager.getDownloadedPage(id, page)
        if (localThumb != null)
            return localThumb

        val (archive, localPage) = getArchiveForPage(page)
        return archive.getThumb(localPage)
    }

    override fun invalidateCache() {
        for (archive in archives)
            archive.invalidateCache()
    }

    private fun getArchiveForPage(page: Int): Pair<MetaArchive, Int> {
        var total = 0
        for (archive in archives) {
            if (page < total + archive.numPages)
                return Pair(archive, page - total)
            total += archive.numPages
        }

        return Pair(archives[0], page)
    }

    override suspend fun clearNewFlag() {
        //Do nothing? maybe clear the new flag for all?
    }

    override suspend fun extract(context: Context, forceFull: Boolean, silent: Boolean) {
        var pageCount = 0
        var silent = silent
        for (archive in archives) {
            archive.extract(context, forceFull, silent)
            silent = true
            pageCount += archive.numPages
        }
        tank.numPages = pageCount
    }

    override suspend fun generateThumbs(): ThumbResult {
        val results = buildList {
            coroutineScope {
                for (archive in archives)
                    add(async { WebHandler.tryGenerateThumbs(archive.id) })
            }
        }.awaitAll()

        if (results.any { it is FailedThumbResult })
            return FailedThumbResult

        if (results.all { it is CompleteThumbResult })
            return CompleteThumbResult

        val flows = buildList {
            for ((i, result) in results.withIndex()) {
                when (result) {
                    is InProgressThumbResult -> {
                        add(result.flow.map { it + archives.take(i).sumOf { arc -> arc.numPages } })
                    }
                    is CompleteThumbResult -> {
                        val start = archives.take(i).sumOf { arc -> arc.numPages }
                        add((start until start + archives[i].numPages).asFlow())
                    }
                    else -> {}
                }
            }
        }
        return InProgressThumbResult(flows.merge())
    }

    override suspend fun getPageImage(context: Context, page: Int): String? {
        if (DownloadManager.isDownloaded(id))
            return DownloadManager.getDownloadedPage(id, page)

        val (archive, localPage) = getArchiveForPage(page)
        return archive.getPageImage(context, localPage)
    }

    override suspend fun addToCEntry(name: String, page: Int) {
        val (archive, localPage) = getArchiveForPage(page)
        archive.addToCEntry(name, localPage)
    }

    override suspend fun removeToCEntry(page: Int) {
        val (archive, localPage) = getArchiveForPage(page)
        archive.removeToCEntry(localPage)
    }

    override suspend fun getToCEntry(page: Int): ToCEntry? {
        val (archive, localPage) = getArchiveForPage(page)
        return archive.getToCEntry(localPage)
    }
}

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
    suspend fun extract(context: Context, forceFull: Boolean = false)
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

@Entity(primaryKeys = ["tankId", "archiveId"])
data class TankoubonArchiveRef(val tankId: String, val archiveId: String, val order: Int, val updateTime: Long)

open class ArchiveJsonBase(json: JsonObject, val updatedAt: Long, val titleSortIndex: Int) {
    val title: String = json.get("title").asString
    val id: String = json.get("arcid").asString
    val tags: String = json.get("tags").asString
    val pageCount = json.get("pagecount")?.asInt ?: 0
    val isNew = json.get("isnew").asString == "true"
    val summary = json.getOrNull("summary")?.asString
    val dateAdded: Long

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

    init {
        val timeStampIndex = tags.indexOf("date_added:")
        dateAdded = if (timeStampIndex < 0) 0L
        else {
            val tagStart = tags.indexOf(':', timeStampIndex) + 1
            var tagEnd = tags.indexOf(',', tagStart)
            if (tagEnd < 0)
                tagEnd = tags.length

            val dateTag = tags.substring(tagStart, tagEnd)
            dateTag.toLongOrNull() ?: 0L
        }
    }

}

open class ArchiveJson(json: JsonObject, updatedAt: Long, titleSortIndex: Int) : ArchiveJsonBase(json, updatedAt, titleSortIndex) {
    val currentPage = json.get("progress")?.asInt?.minus(1) ?: 0
}

open class TankJsonBase(json: JsonObject, val updatedAt: Long) {
    val title: String = json.get("name").asString
    val id: String = json.get("id").asString
    val summary: String = json.get("summary").asString
    val tags: String = json.get("tags").asString
    var dateAdded = 0L //Supplied from the contained archives
    val isNew = false
    var pageCount = 0
    val isTank = true
    @Ignore
    val archives: List<String> = json.get("archives").asJsonArray.map { it.asString }
}

class TankJson(json: JsonObject, updatedAt: Long): TankJsonBase(json, updatedAt) {
    val currentPage = json.get("progress").asInt - 1
}