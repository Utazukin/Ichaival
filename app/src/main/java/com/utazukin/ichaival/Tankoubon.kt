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
import androidx.room.Entity
import androidx.room.Ignore
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

data class Tankoubon(val tank: MetaArchive, val archives: List<MetaArchive>) : MetaArchive by tank {
    @delegate:Ignore
    override val isWebtoon by lazy { archives.any { it.isWebtoon } }

    @delegate:Ignore
    override val tags by lazy {
        buildMap<String, MutableList<String>> {
            for ((ns, tags) in tank.tags)
                put(ns, tags.toMutableList())

            for (archive in archives) {
                for ((namespace, tags) in archive.tags) {
                    val nsTags = getOrPut(namespace) { mutableListOf() }
                    for (tag in tags) {
                        if (tag !in nsTags)
                            nsTags.add(tag)
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
        tank.clearNewFlag()
        for (archive in archives)
            archive.clearNewFlag()
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
                        val total = archives.take(i).sumOf { arc -> arc.numPages }
                        add(result.flow.map { it + total })
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

@Entity(primaryKeys = ["tankId", "archiveId"])
data class TankoubonArchiveRef(val tankId: String, val archiveId: String, val order: Int, val updateTime: Long)

open class TankJsonBase(json: JsonObject, val updatedAt: Long) {
    val title: String = json.get("name").asString
    val id: String = json.get("id").asString
    val summary: String = json.get("summary").asString
    val tags: String = json.get("tags").asString
    var dateAdded = parseDateAdded(tags)
    var isNew = false
    var pageCount = 0
    val isTank = true
    @Ignore
    val archives: List<String> = json.get("archives").asJsonArray.map { it.asString }
}

class TankJson(json: JsonObject, updatedAt: Long): TankJsonBase(json, updatedAt) {
    val currentPage = json.get("progress").asInt - 1
}