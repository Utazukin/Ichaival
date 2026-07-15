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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

object ReaderTabHolder {
    private val scope by lazy { MainScope() }
    private val removeListeners = mutableSetOf<TabRemovedListener>()
    private val addListeners = mutableSetOf<TabAddedListener>()
    private val clearListeners = mutableSetOf<TabsClearedListener>()

    fun registerRemoveListener(listener: TabRemovedListener) = removeListeners.add(listener)

    fun unregisterRemoveListener(listener: TabRemovedListener) = removeListeners.remove(listener)

    fun registerAddListener(listener: TabAddedListener) = addListeners.add(listener)

    fun unregisterAddListener(listener: TabAddedListener) = addListeners.remove(listener)

    fun registerClearListener(listener: TabsClearedListener) = clearListeners.add(listener)

    fun unregisterClearListener(listener: TabsClearedListener) = clearListeners.remove(listener)

    suspend fun addTab(archive: MetaArchive, page: Int) {
        if (!isTabbed(archive.id, page)) {
            val tabCount = DatabaseReader.getBookmarkCount()
            val tab = ReaderTab(archive.id, archive.title, tabCount, page)
            DatabaseReader.addBookmark(tab)
            updateAddListeners(archive.id, tab.page)
        }
    }

    fun insertTab(tab: ReaderTab) {
        scope.launch { DatabaseReader.insertBookmark(tab) }
        updateAddListeners(tab.id, tab.page)
    }

    fun addTabs(archives: List<ArchiveListEntry>) {
        scope.launch {
            var tabCount = DatabaseReader.getBookmarkCount()
            val ids = buildList(archives.size) {
                for (archive in archives) {
                    if (!isTabbed(archive.id, -1)) {
                        val tab = ReaderTab(archive.id, archive.title, tabCount++, -1)
                        DatabaseReader.addBookmark(tab)
                        add(archive.id)
                    }
                }
            }

            updateAddListeners(ids)
        }
    }

    fun addReaderTabs(tabs: List<ReaderTab>) {
        scope.launch {
            for (tab in tabs)
                DatabaseReader.addBookmark(tab)
        }
        updateAddListeners(tabs.map { it.id })
    }

    suspend fun addTab(id: String, page: Int) {
        DatabaseReader.getArchive(id)?.let { addTab(it, page) }
    }

    suspend fun isTabbed(id: String, page: Int) = DatabaseReader.isBookmarked(id, page)

    suspend fun getTab(id: String, page: Int) = DatabaseReader.getBookmark(id, page)

    fun removeTab(tab: ReaderTab) {
        scope.launch {
            DatabaseReader.removeBookmark(tab)
            updateRemoveListeners(tab.id, tab.page)
        }
    }

    fun removeAll() {
        scope.launch { DatabaseReader.clearBookmarks() }
        updateClearListeners()
    }

    private fun updateRemoveListeners(id: String, page: Int) {
        for (listener in removeListeners)
            listener.onTabRemoved(id, page)
    }

    private fun updateAddListeners(id: String, page: Int){
        for (listener in addListeners)
            listener.onTabAdded(id, page)
    }

    private fun updateAddListeners(ids: List<String>) {
        for (listener in addListeners)
            listener.onTabsAdded(ids)
    }

    private fun updateClearListeners() {
        for (listener in clearListeners)
            listener.onTabsCleared()
    }
}

@Entity(primaryKeys = ["id", "currentPage"],
        foreignKeys = [
            ForeignKey(
                    parentColumns = ["id"],
                    childColumns = ["id"],
                    entity = ArchiveFull::class,
                    onDelete = ForeignKey.CASCADE
            )
        ])
data class ReaderTab(
    @ColumnInfo val id: String,
    @ColumnInfo val title: String,
    @ColumnInfo var index: Int,
    @ColumnInfo(name = "currentPage") val page: Int)


