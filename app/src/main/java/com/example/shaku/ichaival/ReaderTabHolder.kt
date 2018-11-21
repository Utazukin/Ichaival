package com.example.shaku.ichaival

class ReaderTabHolder private constructor() {
    inner class ReaderTab(
        archive: Archive,
        var page: Int
    ) {
        val title = archive.title
        val id = archive.id
    }

    companion object {
        val instance by lazy { ReaderTabHolder() }
    }

    private val openTabs = mutableMapOf<String, ReaderTab>()

    private val listeners = mutableSetOf<TabUpdateListener>()

    fun getCurrentPage(id: String?) : Int {
        return if (id != null && openTabs.containsKey(id)) openTabs[id]!!.page else 0
    }

    fun updatePageIfTabbed(id: String, page: Int) {
        val tab = openTabs[id]

        if (tab != null) {
            tab.page = page
            updateListeners()
        }
    }

    fun registerTabListener(listener: TabUpdateListener) {
        listeners.add(listener)
    }

    fun unregisterTabListener(listener: TabUpdateListener) {
        listeners.remove(listener)
    }

    fun addTab(archive: Archive, page: Int) {
        openTabs[archive.id] = ReaderTab(archive, page)
        updateListeners()
    }

    fun isTabbed(id: String?) : Boolean {
        return openTabs.containsKey(id)
    }

    fun removeTab(id: String) {
        openTabs.remove(id)
        updateListeners()
    }

    private fun updateListeners() {
        val updatedList = getTabList()
        for (listener in listeners)
            listener.onTabListUpdate(updatedList)
    }

    fun getTabList() : List<ReaderTab> {
        return openTabs.values.toList()
    }
}