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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ArchiveListFragment : Fragment() {

    private var listener: OnListFragmentInteractionListener? = null
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var listView: RecyclerView
    private lateinit var activityScope: CoroutineScope

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_archive_list, container, false)
        listView = view.findViewById(R.id.list)
        lateinit var listAdapter: ArchiveRecyclerViewAdapter


        // Set the adapter
        with(listView) {
            post {
                val dpWidth = getDpWidth(width)
                val columns = Math.floor(dpWidth / 300.0).toInt()
                layoutManager = if (columns > 1) GridLayoutManager(
                    context,
                    columns
                ) else LinearLayoutManager(context)
            }
            //layoutManager = GridLayoutManager(context, 2)
            val temp = ArchiveRecyclerViewAdapter(listener, activityScope)
            listAdapter = temp
            adapter = temp
        }

        val searchView: SearchView = view.findViewById(R.id.archive_search)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(p0: String?): Boolean {
                listAdapter.filter(p0)
                return true
            }

            override fun onQueryTextChange(p0: String?): Boolean {
                listAdapter.filter(p0)
                return true
            }
        })

        val randomButton: Button = view.findViewById(R.id.random_button)
        randomButton.setOnClickListener { v ->
            val archive = listAdapter.getRandomArchive()
            if (archive != null)
                startDetailsActivity(archive.id, v?.context)
        }

        randomButton.setOnLongClickListener {
            val archive = listAdapter.getRandomArchive()
            if (archive != null && !ReaderTabHolder.isTabbed(archive.id))
                ReaderTabHolder.addTab(archive, 0)
            true
        }

        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        swipeRefreshLayout.setOnRefreshListener { forceArchiveListUpdate() }

        activityScope.launch {
            val updatedList = withContext(Dispatchers.Default) { DatabaseReader.readArchiveList(context!!.filesDir) }
            listAdapter.updateDataCopy(updatedList)
        }
        return view
    }

    private fun startDetailsActivity(id: String, context:Context?){
        val intent = Intent(context, ArchiveDetails::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnListFragmentInteractionListener) {
            listener = context
            activityScope = context as CoroutineScope
        } else {
            throw RuntimeException(context.toString() + " must implement OnListFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    fun forceArchiveListUpdate() {
        activityScope.launch {
            val newList = withContext(Dispatchers.Default) { DatabaseReader.readArchiveList(context!!.filesDir, true) }
            val adapter = listView.adapter as ArchiveRecyclerViewAdapter
            adapter.updateDataCopy(newList)
            swipeRefreshLayout.isRefreshing = false
        }
    }

    interface OnListFragmentInteractionListener {
        fun onListFragmentInteraction(archive: Archive?)

        fun onFragmentLongPress(archive: Archive?, view: View) : Boolean
    }
}
