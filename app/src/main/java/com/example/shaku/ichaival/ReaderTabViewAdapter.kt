package com.example.shaku.ichaival

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

class ReaderTabViewAdapter (
    private var openTabs: List<ReaderTabHolder.ReaderTab>,
    private val listener: OnTabInteractionListener?
) : RecyclerView.Adapter<ReaderTabViewAdapter.ViewHolder>(), TabUpdateListener {

    override fun getItemCount() = openTabs.size

    private val onClickListener: View.OnClickListener

    init {
        onClickListener = View.OnClickListener { v ->
            val item = v.tag as ReaderTabHolder.ReaderTab
            // Notify the active callbacks interface (the activity, if the fragment is attached to
            // one) that an item has been selected.
            listener?.onTabInteraction(item)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = openTabs[position]
        holder.titleView.text = item.title
        holder.pageView.text = item.page.toString()

        with(holder.view) {
            tag = item
            setOnClickListener(onClickListener)
        }
    }

    fun removeTab(position: Int) {
        ReaderTabHolder.instance.removeTab(openTabs[position].id)
    }

    override fun onTabListUpdate(tabList: List<ReaderTabHolder.ReaderTab>) {
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
        ReaderTabHolder.instance.registerTabListener(this)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        ReaderTabHolder.instance.unregisterTabListener(this)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val titleView: TextView = view.findViewById(R.id.archive_title)
        val pageView: TextView = view.findViewById(R.id.archive_page)
    }

    interface OnTabInteractionListener {
        fun onTabInteraction(tab: ReaderTabHolder.ReaderTab)
    }
}