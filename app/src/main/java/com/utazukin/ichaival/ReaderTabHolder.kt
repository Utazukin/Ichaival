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

import android.os.Bundle

object ReaderTabHolder {
    private const val titleKey = "tabTitles"
    private const val idKey = "tabIds"
    private const val pageKey = "tabPages"

    private val openTabs = mutableMapOf<String, ReaderTab>()

    private val restoreListeners = mutableSetOf<TabsRestoredListener>()

    private val removeListeners = mutableSetOf<TabRemovedListener>()

    private val addListeners = mutableSetOf<TabAddedListener>()

    private val changeListeners = mutableSetOf<TabChangedListener>()

    private val clearListeners = mutableSetOf<TabsClearedListener>()

    private var needsReload = true

    private val _tabs = mutableListOf<ReaderTab>()
    val tabs: List<ReaderTab> = _tabs

    fun getCurrentPage(id: String) : Int {
        return openTabs[id]?.page ?: 0
    }

    fun updatePageIfTabbed(id: String, page: Int) {
        val tab = openTabs[id]

        if (tab != null) {
            tab.page = page
            val index = tabs.indexOf(tab)
            updateChangeListeners(index)
        }
    }

    fun registerRestoreListener(listener: TabsRestoredListener) = restoreListeners.add(listener)

    fun unregisterRestoreListener(listener: TabsRestoredListener) = restoreListeners.remove(listener)

    fun registerRemoveListener(listener: TabRemovedListener) = removeListeners.add(listener)

    fun unregisterRemoveListener(listener: TabRemovedListener) = removeListeners.remove(listener)

    fun registerAddListener(listener: TabAddedListener) = addListeners.add(listener)

    fun unregisterAddListener(listener: TabAddedListener) = addListeners.remove(listener)

    fun registerChangeListener(listener: TabChangedListener) = changeListeners.add(listener)

    fun unregisterChangeListener(listener: TabChangedListener) = changeListeners.remove(listener)

    fun registerClearListener(listener: TabsClearedListener) = clearListeners.add(listener)

    fun unregisterClearListener(listener: TabsClearedListener) = clearListeners.remove(listener)

    fun registerListener(listener: ReaderTabListener) {
        registerAddListener(listener)
        registerRemoveListener(listener)
        registerChangeListener(listener)
        registerRestoreListener(listener)
        registerClearListener(listener)
    }

    fun unregisterListener(listener: ReaderTabListener) {
        unregisterAddListener(listener)
        unregisterRemoveListener(listener)
        unregisterChangeListener(listener)
        unregisterRestoreListener(listener)
        unregisterClearListener(listener)
    }

    fun addTab(archive: ArchiveBase, page: Int) {
        if (!openTabs.containsKey(archive.id)) {
            val tab = ReaderTab(archive, page)
            openTabs[archive.id] = tab
            _tabs.add(tab)
            updateAddListeners(archive.id)
        }
    }

    fun isTabbed(id: String?) : Boolean {
        return openTabs.containsKey(id)
    }

    fun removeTab(id: String) {
        val tabIndex = tabs.indexOfFirst { it.id == id }
        if (tabIndex >= 0) {
            openTabs.remove(id)
            _tabs.removeAt(tabIndex)
            updateRemoveListeners(tabIndex, id)
        }
    }

    fun removeAll() {
        val size = openTabs.size
        openTabs.clear()
        _tabs.clear()
        updateClearListeners(size)
    }

    fun restoreTabs(savedInstance: Bundle?) {
        if (needsReload) {
            savedInstance?.let {
                val ids = it.getStringArrayList(idKey) ?: return
                val titles = it.getStringArrayList(titleKey) ?: return
                val pages = it.getIntArray(pageKey) ?: return

                _tabs.clear()
                for (i in 0 until ids.size) {
                    val tab = ReaderTab(ids[i], titles[i], pages[i])
                    openTabs[ids[i]] = tab
                    _tabs.add(tab)
                }
                updateRestoreListeners()
            }
            needsReload = false
        }
    }

    fun saveTabs(outState: Bundle) {
        if (openTabs.any()) {
            val ids = ArrayList<String>(openTabs.size)
            val titles = ArrayList<String>(openTabs.size)
            val pages = IntArray(openTabs.size)
            for ((index, tab) in openTabs.values.withIndex()) {
                ids.add(tab.id)
                titles.add(tab.title)
                pages[index] = tab.page
            }

            outState.putStringArrayList(titleKey, titles)
            outState.putStringArrayList(idKey, ids)
            outState.putIntArray(pageKey, pages)
        }
    }

    private fun updateRemoveListeners(index: Int, id: String) {
        for (listener in removeListeners)
            listener.onTabRemoved(index, id)
    }

    private fun updateAddListeners(id: String){
        val index = openTabs.size - 1
        for (listener in addListeners)
            listener.onTabAdded(index, id)
    }

    private fun updateRestoreListeners() {
        for (listener in restoreListeners)
            listener.onTabsRestored()
    }

    private fun updateChangeListeners(index: Int) {
        for (listener in changeListeners)
            listener.onTabChanged(index)
    }

    private fun updateClearListeners(oldSize: Int) {
        for (listener in clearListeners)
            listener.onTabsCleared(oldSize)
    }
}

data class ReaderTab(val id: String, val title: String, var page: Int) {
    constructor(archive: ArchiveBase, page: Int) : this(archive.id, archive.title, page)
}

