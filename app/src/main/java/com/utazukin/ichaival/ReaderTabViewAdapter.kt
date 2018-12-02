/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2018 Utazukin
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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ReaderTabViewAdapter (
    private var openTabs: List<ReaderTab>,
    private val listener: OnTabInteractionListener?
) : RecyclerView.Adapter<ReaderTabViewAdapter.ViewHolder>(), TabUpdateListener {

    override fun getItemCount() = openTabs.size

    private val onClickListener: View.OnClickListener

    private val onLongClickListener: View.OnLongClickListener

    init {
        onClickListener = View.OnClickListener { v ->
            val item = v.tag as ReaderTab
            listener?.onTabInteraction(item, false)
        }

        onLongClickListener = View.OnLongClickListener { v ->
            val item = v.tag as ReaderTab
            listener?.onTabInteraction(item, true)
            true
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = openTabs[position]
        holder.titleView.text = item.title
        holder.pageView.text = (item.page + 1).toString()

        with(holder.view) {
            tag = item
            setOnClickListener(onClickListener)
            setOnLongClickListener(onLongClickListener)
        }
    }

    fun removeTab(position: Int) {
        ReaderTabHolder.removeTab(openTabs[position].id)
    }

    override fun onTabListUpdate(tabList: List<ReaderTab>) {
        openTabs = tabList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.reader_tab, parent, false)
        return ViewHolder(view)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        ReaderTabHolder.registerTabListener(this)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        ReaderTabHolder.unregisterTabListener(this)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val titleView: TextView = view.findViewById(R.id.archive_title)
        val pageView: TextView = view.findViewById(R.id.archive_page)
    }

    interface OnTabInteractionListener {
        fun onTabInteraction(tab: ReaderTab, longPress: Boolean)
    }
}