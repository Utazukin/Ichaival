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

    private val listeners = mutableSetOf<TabUpdateListener>()

    private val removeListeners = mutableSetOf<TabRemovedListener>()

    private val addListeners = mutableSetOf<TabAddedListener>()

    private val changeListeners = mutableSetOf<TabChangedListener>()

    private var needsReload = true

    fun getCurrentPage(id: String?) : Int {
        return if (id != null && openTabs.containsKey(id)) openTabs[id]!!.page else 0
    }

    fun updatePageIfTabbed(id: String, page: Int) {
        val tab = openTabs[id]

        if (tab != null) {
            tab.page = page
            val index = openTabs.keys.indexOf(id)
            updateListeners()
            updateChangeListeners(index)
        }
    }

    fun registerTabListener(listener: TabUpdateListener) = listeners.add(listener)

    fun unregisterTabListener(listener: TabUpdateListener) = listeners.remove(listener)

    fun registerRemoveListener(listener: TabRemovedListener) = removeListeners.add(listener)

    fun unregisterRemoveListener(listener: TabRemovedListener) = removeListeners.remove(listener)

    fun registerAddListener(listener: TabAddedListener) = addListeners.add(listener)

    fun unregisterAddListener(listener: TabAddedListener) = addListeners.remove(listener)

    fun registerChangeListener(listener: TabChangedListener) = changeListeners.add(listener)

    fun unregisterChangeListener(listener: TabChangedListener) = changeListeners.remove(listener)

    fun addTab(archive: Archive, page: Int) {
        openTabs[archive.id] = ReaderTab(archive, page)
        updateListeners()
        updateAddListeners(archive.id)
    }

    fun isTabbed(id: String?) : Boolean {
        return openTabs.containsKey(id)
    }

    fun removeTab(id: String) {
        val index = openTabs.keys.indexOf(id)
        openTabs.remove(id)
        updateListeners()
        updateRemoveListeners(index, id)
    }

    fun removeAll() {
        openTabs.clear()
        updateListeners(true)
    }

    fun restoreTabs(savedInstance: Bundle?) {
        if (needsReload) {
            savedInstance?.let {
                val ids = it.getStringArrayList(idKey) ?: return
                val titles = it.getStringArrayList(titleKey) ?: return
                val pages = it.getIntArray(pageKey) ?: return

                for (i in 0 until ids.size) {
                    openTabs[ids[i]] = ReaderTab(ids[i], titles[i], pages[i])
                }
                updateListeners(true)
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

    private fun updateListeners(restored: Boolean = false) {
        val updatedList = getTabList()
        for (listener in listeners)
            listener.onTabListUpdate(updatedList, restored)
    }

    private fun updateChangeListeners(index: Int) {
        for (listener in changeListeners)
            listener.onTabChanged(index)
    }

    fun getTabList() : List<ReaderTab> {
        return openTabs.values.toList()
    }
}

data class ReaderTab(val id: String, val title: String, var page: Int) {
    constructor(archive: Archive, page: Int) : this(archive.id, archive.title, page)
}

