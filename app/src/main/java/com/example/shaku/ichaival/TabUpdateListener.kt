package com.example.shaku.ichaival

interface TabUpdateListener {
    fun onTabListUpdate(tabList: List<ReaderTab>)
}

interface TabRemovedListener {
    fun onTabRemoved(id: String)
}

interface TabAddedListener {
    fun onTabAdded(index: Int, id: String)
}