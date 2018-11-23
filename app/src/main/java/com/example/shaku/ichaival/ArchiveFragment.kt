package com.example.shaku.ichaival

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SearchView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class ArchiveFragment : Fragment() {

    private var listener: OnListFragmentInteractionListener? = null
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var listView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_archive_list, container, false)
        listView = view.findViewById(R.id.list)
        lateinit var listAdapter: ArchiveRecyclerViewAdapter


        // Set the adapter
        with(listView) {
            layoutManager = GridLayoutManager(context, 2)
            val temp = ArchiveRecyclerViewAdapter(listener)
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
        randomButton.setOnClickListener { p0 ->
            startDetailsActivity(listAdapter.getRandomArchive().id, p0?.context)
        }

        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        swipeRefreshLayout.setOnRefreshListener { forceArchiveListUpdate() }

        GlobalScope.launch(Dispatchers.Main) {
            listAdapter.updateDataCopy(DatabaseReader.readArchiveList(context!!.filesDir))
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
        } else {
            throw RuntimeException(context.toString() + " must implement OnListFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    private fun forceArchiveListUpdate() {
        GlobalScope.launch(Dispatchers.Main) {
            val newList = async { DatabaseReader.readArchiveList(context!!.filesDir, true) }.await()
            val adapter = listView.adapter as ArchiveRecyclerViewAdapter
            adapter.updateDataCopy(newList)
            swipeRefreshLayout.isRefreshing = false
        }
    }

    interface OnListFragmentInteractionListener {
        fun onListFragmentInteraction(archive: Archive?)
    }
}
