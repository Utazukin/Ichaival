/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2022 Utazukin
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
import androidx.room.PrimaryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Entity
data class Archive (
    @PrimaryKey val id: String,
    @ColumnInfo val title: String,
    @ColumnInfo val dateAdded: Long,
    @ColumnInfo var isNew: Boolean,
    @ColumnInfo val tags: Map<String, List<String>>,
    @ColumnInfo var currentPage: Int,
    @ColumnInfo var pageCount: Int
) {

    constructor(id: String, title: String, dateAdded: Long, isNew: Boolean, tags: Map<String, List<String>>)
            : this(id, title, dateAdded, isNew, tags, -1, 0)
    val numPages: Int
        get() = if (ServerManager.checkVersionAtLeast(0, 7, 7) && pageCount > 0) pageCount else DatabaseReader.getPageCount(id)

    suspend fun extract(context: Context) {
        val pages = DatabaseReader.getPageList(context, id)
        if (pageCount <= 0)
            pageCount = pages.size
    }

    fun invalidateCache() {
        DatabaseReader.invalidateImageCache(id)
    }

    fun hasPage(page: Int) : Boolean {
        return numPages <= 0 || (page in 0 until numPages)
    }

    suspend fun clearNewFlag() = withContext(Dispatchers.IO) {
        DatabaseReader.setArchiveNewFlag(id)
        isNew = false
    }

    suspend fun getPageImage(context: Context, page: Int) : String? {
        return downloadPage(context, page)
    }

    suspend fun getThumb(context: Context, page: Int) = WebHandler.downloadThumb(id, page) ?: downloadPage(context, page)

    private suspend fun downloadPage(context: Context, page: Int) : String? {
        val pages = DatabaseReader.getPageList(context.applicationContext, id)
        return if (page < pages.size) WebHandler.getRawImageUrl(pages[page]) else null
    }

    fun containsTag(tag: String, exact: Boolean) : Boolean {
        if (':' in tag) {
            val split = tag.split(":")
            val namespace = split[0].trim()
            val normalized = split[1].trim().replace("_", " ")
            val nTags = tags[namespace]
            return nTags?.any { if (exact) it.equals(normalized, ignoreCase = true) else it.contains(normalized, ignoreCase = true) } == true
        }
        else {
            val normalized = tag.trim().replace("_", " ")
            for ((_, t) in tags) {
                if (t.any { it.contains(normalized, ignoreCase = true)})
                    return true
            }
        }
        return false
    }
}

class ArchiveJson(json: JSONObject) {
    val title: String = json.getString("title")
    val id: String = json.getString("arcid")
    val tags: Map<String, List<String>>
    val pageCount = json.optInt("pagecount")
    val currentPage = if (json.has("progress")) json.getInt("progress") - 1 else 0
    val isNew: Boolean
    val dateAdded: Long

    init {
        val tagString = json.getString("tags")
        val tagList = tagString.split(",")
        tags = buildMap<String, MutableList<String>> {
            var timestamp = 0L
            for (tag in tagList.map { it.trim() }) {
                if (':' in tag) {
                    val split = tag.split(":")
                    val namespace = split[0]
                    if (namespace == "date_added")
                        timestamp = split[1].toLong()
                    else {
                        val namespaceList = getOrPut(namespace) { mutableListOf() }
                        namespaceList.add(split[1])
                    }
                } else if (tag.isNotEmpty()) {
                    val global = getOrPut("global") { mutableListOf() }
                    global.add(tag)
                }
            }
            dateAdded = timestamp
        }

        json.getString("isnew").let { isNew = it == "block" || it == "true" }
    }

}