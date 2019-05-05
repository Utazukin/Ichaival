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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TagSuggestionViewAdapter(
    private val tagListener: ((tag: String, add: Boolean) -> Unit)?
) : RecyclerView.Adapter<TagSuggestionViewAdapter.ViewHolder>() {
    private var hidden = true

    private fun show(show: Boolean) {
        hidden = !show
        notifyDataSetChanged()
    }

    fun toggle() = show(hidden)

    override fun getItemCount(): Int = if (hidden) 0 else DatabaseReader.tagSuggestions.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = DatabaseReader.tagSuggestions[position]

        holder.tagView.text = item
        holder.tagView.setOnClickListener { tagListener?.invoke(item, false) }
        holder.addView.setOnClickListener { tagListener?.invoke(item, true) }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.tagView.setOnClickListener(null)
        holder.addView.setOnClickListener(null)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.tag_list_item, parent, false)
        return ViewHolder(view)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val tagView: TextView = view.findViewById(R.id.tag_name)
        val addView: ImageView = view.findViewById(R.id.add_tag)
    }
}