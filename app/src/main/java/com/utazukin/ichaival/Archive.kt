/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2021 Utazukin
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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject
import java.util.*

@Entity
data class Archive (
    @PrimaryKey val id: String,
    @ColumnInfo val title: String,
    @ColumnInfo var dateAdded: Int,
    @ColumnInfo var isNew: Boolean,
    @ColumnInfo val tags: Map<String, List<String>>,
    @ColumnInfo var currentPage: Int,
    @ColumnInfo var pageCount: Int
) {

    constructor(id: String, title: String, dateAdded: Int, isNew: Boolean, tags: Map<String, List<String>>)
            : this(id, title, dateAdded, isNew, tags, -1, 0)

    constructor(jsonArchive: ArchiveJson)
            : this(jsonArchive.id, jsonArchive.title, jsonArchive.dateAdded, jsonArchive.isNew, jsonArchive.tags, jsonArchive.currentPage, jsonArchive.pageCount)

    val numPages: Int
        get() = if (ServerManager.checkVersionAtLeast(0, 7, 7)) pageCount else DatabaseReader.getPageCount(id)

    suspend fun extract() {
        DatabaseReader.getPageList(id)
    }

    fun invalidateCache() {
        DatabaseReader.invalidateImageCache(id)
    }

    fun hasPage(page: Int) : Boolean {
        return numPages <= 0 || (page in 0 until numPages)
    }

    suspend fun getPageImage(page: Int) : String? {
        return downloadPage(page)
    }

    private suspend fun downloadPage(page: Int) : String? {
        val pages = DatabaseReader.getPageList(id)
        return if (page < pages.size) WebHandler.getRawImageUrl(pages[page]) else null
    }

    fun containsTag(tag: String) : Boolean {
        if (tag.contains(":")) {
            val split = tag.split(":")
            val namespace = split[0].trim()
            var normalized = split[1].trim().replace("_", " ").toLowerCase(Locale.ROOT)
            val exact = normalized.startsWith("\"") && normalized.endsWith("\"")
            if (exact)
                normalized = normalized.removeSurrounding("\"")
            val nTags = getTags(namespace)
            return nTags != null && nTags.any {
                if (exact) it.toLowerCase(Locale.ROOT) == normalized else it.toLowerCase(Locale.ROOT).contains(normalized)
            }
        }
        else {
            val normalized = tag.trim().replace("_", " ").toLowerCase(Locale.ROOT)
            for (pair in tags) {
                if (pair.value.any { it.toLowerCase(Locale.ROOT).contains(normalized)})
                    return true
            }
        }
        return false
    }

    private fun getTags(namespace: String) : List<String>? {
        return if (tags.containsKey(namespace)) tags[namespace] else null
    }
}

class ArchiveJson(json: JSONObject) {
    val title: String = json.getString("title")
    val id: String = json.getString("arcid")
    val tags: Map<String, List<String>>
    val pageCount = json.optInt("pagecount")
    val currentPage = if (json.has("progress")) json.getInt("progress") - 1 else 0
    var isNew = false
    var dateAdded = 0

    init {
        val tagString: String = json.getString("tags")
        val tagList: List<String> = tagString.split(",")
        val mutableTags = mutableMapOf<String, MutableList<String>>()
        for (tag: String in tagList) {
            val trimmed = tag.trim()
            if (trimmed.contains(":")) {
                val split = trimmed.split(":")
                val namespace = split[0]
                if (namespace == "date_added")
                    dateAdded = split[1].toInt()
                else {
                    if (!mutableTags.containsKey(namespace))
                        mutableTags[namespace] = mutableListOf()
                    mutableTags[namespace]!!.add(split[1])
                }
            }
            else if (tag.isNotEmpty()) {
                if (!mutableTags.containsKey("global"))
                    mutableTags["global"] = mutableListOf()
                mutableTags["global"]!!.add(trimmed)
            }
        }
        tags = mutableTags

        val isNewString = json.getString("isnew")
        isNew = isNewString == "block" || isNewString == "true"
    }

}