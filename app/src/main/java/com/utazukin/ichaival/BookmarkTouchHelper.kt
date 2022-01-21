/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2022 Utazukin
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
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ItemTouchHelper.LEFT
import androidx.recyclerview.widget.ItemTouchHelper.RIGHT
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.roundToInt

private typealias DetailsListener = (String, Int) -> Unit

class BookmarkTouchHelper(context: Context, private val leftSwipeListener: DetailsListener?) : ItemTouchHelper.SimpleCallback(0, RIGHT or LEFT) {
    private val infoDrawable: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_info_black_24dp)!!
    private val margin: Int = context.resources.getDimension(R.dimen.ic_clear_margin).roundToInt()

    override fun onMove(recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                        target: RecyclerView.ViewHolder) = false

    override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
        val tab = holder.itemView.tag as? ReaderTab ?: return
        when (direction) {
            RIGHT -> ReaderTabHolder.removeTab(tab.id)
            LEFT -> leftSwipeListener?.invoke(tab.id, holder.absoluteAdapterPosition)
        }
    }

    override fun onChildDraw(c: Canvas,
                             recyclerView: RecyclerView,
                             viewHolder: RecyclerView.ViewHolder,
                             dX: Float,
                             dY: Float,
                             actionState: Int,
                             isCurrentlyActive: Boolean) {
        if (dX < 0 && abs(dX) > margin + infoDrawable.intrinsicWidth) {
            val itemView = viewHolder.itemView

            val cellHeight = itemView.bottom - itemView.top
            val top = itemView.top + (cellHeight - infoDrawable.intrinsicHeight) / 2
            val bottom = top + infoDrawable.intrinsicHeight
            val left = itemView.right - margin - infoDrawable.intrinsicWidth
            val right = itemView.right - margin
            infoDrawable.bounds = Rect(left, top, right, bottom)

            infoDrawable.draw(c)
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}