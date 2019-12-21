/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2019 Utazukin
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

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProviders
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ReaderTabHolder {
    private var initialized = false
    private var tabCount = 0

    private val removeListeners = mutableSetOf<TabRemovedListener>()

    private val addListeners = mutableSetOf<TabAddedListener>()

    private val clearListeners = mutableSetOf<TabsClearedListener>()

    fun updatePageIfTabbed(id: String, page: Int) {
        DatabaseReader.updateBookmark(id, page)
    }

    fun registerRemoveListener(listener: TabRemovedListener) = removeListeners.add(listener)

    fun unregisterRemoveListener(listener: TabRemovedListener) = removeListeners.remove(listener)

    fun registerAddListener(listener: TabAddedListener) = addListeners.add(listener)

    fun unregisterAddListener(listener: TabAddedListener) = addListeners.remove(listener)

    fun registerClearListener(listener: TabsClearedListener) = clearListeners.add(listener)

    fun unregisterClearListener(listener: TabsClearedListener) = clearListeners.remove(listener)

    fun addTab(archive: Archive, page: Int) {
        if (archive.currentPage < 0) {
            val tab = ReaderTab(archive.id, archive.title, tabCount, page)
            archive.currentPage = page
            DatabaseReader.addBookmark(tab)
            updateAddListeners(archive.id)
        }
    }

    suspend fun addTab(id: String, page: Int) {
        val archive = DatabaseReader.getArchive(id)
        if (archive != null)
            addTab(archive, page)
    }

    fun initialize(context: FragmentActivity) {
        if (!initialized) {
            val viewModel = ViewModelProviders.of(context).get(ReaderTabViewModel::class.java)
            viewModel.bookmarks.observeForever { tabCount = it.size }
            initialized = true
        }
    }

    fun isTabbed(archive: Archive?) = if (archive != null) archive.currentPage >= 0 else false

    suspend fun isTabbed(id: String) : Boolean {
        val archive = withContext(Dispatchers.IO) { DatabaseReader.getArchive(id) }
        return isTabbed(archive)
    }

    fun removeTab(id: String) {
        GlobalScope.launch {
            if (DatabaseReader.removeBookmark(id))
                updateRemoveListeners(id)
        }
    }

    fun removeAll() {
        DatabaseReader.clearBookmarks()
        updateClearListeners()
    }

    private fun updateRemoveListeners(id: String) {
        for (listener in removeListeners)
            listener.onTabRemoved(id)
    }

    private fun updateAddListeners(id: String){
        for (listener in addListeners)
            listener.onTabAdded(id)
    }

    private fun updateClearListeners() {
        for (listener in clearListeners)
            listener.onTabsCleared()
    }
}

@Entity
data class ReaderTab(
    @PrimaryKey val id: String,
    @ColumnInfo val title: String,
    @ColumnInfo var index: Int,
    @ColumnInfo(name = "currentPage") var page: Int)


