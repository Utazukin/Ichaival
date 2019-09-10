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

object ReaderTabHolder {
    private var openTabs: Map<String, ReaderTab>? = null
    private var initialized = false

    private val removeListeners = mutableSetOf<TabRemovedListener>()

    private val addListeners = mutableSetOf<TabAddedListener>()

    private val clearListeners = mutableSetOf<TabsClearedListener>()

    fun updatePageIfTabbed(id: String, page: Int) {
        val tab = openTabs?.get(id)

        if (tab != null) {
            tab.page = page
            DatabaseReader.updateBookmark(tab)
        }
    }

    fun registerRemoveListener(listener: TabRemovedListener) = removeListeners.add(listener)

    fun unregisterRemoveListener(listener: TabRemovedListener) = removeListeners.remove(listener)

    fun registerAddListener(listener: TabAddedListener) = addListeners.add(listener)

    fun unregisterAddListener(listener: TabAddedListener) = addListeners.remove(listener)

    fun registerClearListener(listener: TabsClearedListener) = clearListeners.add(listener)

    fun unregisterClearListener(listener: TabsClearedListener) = clearListeners.remove(listener)

    fun addTab(archive: Archive, page: Int) = addTab(archive.id, archive.title, page)

    private fun addTab(id: String, title: String, page: Int) {
        if (openTabs?.containsKey(id) != true) {
            val tab = ReaderTab(id, title, openTabs?.size ?: 0, page)
            DatabaseReader.updateBookmark(tab)
            updateAddListeners(id)
        }
    }

    fun initialize(context: FragmentActivity) {
        if (!initialized) {
            val viewModel = ViewModelProviders.of(context).get(ReaderTabViewModel::class.java)
            viewModel.bookmarkMap.observeForever { openTabs = it }
            initialized = true
        }
    }

    fun isTabbed(id: String?) : Boolean {
        return openTabs?.containsKey(id) == true
    }

    fun removeTab(id: String) {
        val tabToRemove = openTabs?.get(id)
        if (tabToRemove != null) {
            openTabs?.values?.filter { it.index > tabToRemove.index }?.let {
                for (tab in it)
                    tab.index--

                DatabaseReader.removeBookmark(tabToRemove, it)
                updateRemoveListeners(id)
            }
        }
    }

    fun removeAll() {
        openTabs?.let {
            DatabaseReader.clearBookmarks(it.values.toList())
            updateClearListeners()
        }
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


