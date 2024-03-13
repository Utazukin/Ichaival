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

package com.utazukin.ichaival

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.google.gson.JsonObject
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Entity(tableName = "archive")
data class ArchiveFull(
    @PrimaryKey val id: String,
    @ColumnInfo val title: String,
    @ColumnInfo val dateAdded: Long,
    @ColumnInfo val isNew: Boolean,
    @ColumnInfo val tags: Map<String, List<String>>,
    @ColumnInfo val currentPage: Int,
    @ColumnInfo val pageCount: Int,
    @ColumnInfo val updatedAt: Long,
    @ColumnInfo val titleSortIndex: Int
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

data class ArchiveBase(val id: String, val title: String, val tags: Map<String, List<String>>)

data class Archive (
    val id: String,
    val title: String,
    val dateAdded: Long,
    var isNew: Boolean,
    val tags: Map<String, List<String>>,
    var currentPage: Int,
    @ColumnInfo(name = "pageCount") var numPages: Int) {

    @delegate:Ignore
    val isWebtoon by lazy { containsTag("webtoon", false) }

    suspend fun extract(context: Context, forceFull: Boolean = false) {
        val pages = DatabaseReader.getPageList(context, id, forceFull)
        if (numPages <= 0)
            numPages = pages.size
    }

    fun invalidateCache() = DatabaseReader.invalidateImageCache(id)

    fun hasPage(page: Int) = numPages <= 0 || (page in 0 until numPages)

    suspend fun clearNewFlag() = withContext(Dispatchers.IO) {
        DatabaseReader.setArchiveNewFlag(id)
        isNew = false
    }

    suspend fun getPageImage(context: Context, page: Int) : String? {
        return downloadPage(context, page)
    }

    suspend fun getThumb(context: Context, page: Int) = WebHandler.getThumbUrl(id, page) ?: downloadPage(context, page)

    private suspend fun downloadPage(context: Context, page: Int) : String? {
        val pages = DatabaseReader.getPageList(context.applicationContext, id)
        return if (page < pages.size) WebHandler.getRawImageUrl(pages[page]) else null
    }
}

class ArchiveJson(json: JsonObject, val updatedAt: Long, val titleSortIndex: Int) {
    val title: String = json.get("title").asString
    val id: String = json.get("arcid").asString
    val tags: String = json.get("tags").asString
    val pageCount = json.get("pagecount")?.asInt ?: 0
    val currentPage = json.get("progress")?.asInt?.minus(1) ?: 0
    val isNew = json.get("isnew").asString == "true"
    val dateAdded: Long

    init {
        val timeStampIndex = tags.indexOf("date_added:")
        dateAdded = if (timeStampIndex < 0) 0L
        else {
            val tagStart = tags.indexOf(':', timeStampIndex) + 1
            var tagEnd = tags.indexOf(',', tagStart)
            if (tagEnd < 0)
                tagEnd = tags.length

            val dateTag = tags.substring(tagStart, tagEnd)
            if (dateTag.isNotBlank()) dateTag.toLong() else 0L
        }
    }

}