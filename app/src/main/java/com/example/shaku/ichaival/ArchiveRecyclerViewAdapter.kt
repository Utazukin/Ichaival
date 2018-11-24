package com.example.shaku.ichaival

import android.support.v7.widget.CardView
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView


import com.example.shaku.ichaival.ArchiveListFragment.OnListFragmentInteractionListener

import kotlinx.android.synthetic.main.fragment_archive.view.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class ArchiveRecyclerViewAdapter(
    private val mListener: OnListFragmentInteractionListener?
) : RecyclerView.Adapter<ArchiveRecyclerViewAdapter.ViewHolder>() {

    private val mOnClickListener: View.OnClickListener

    private var mValuesCopy: List<Archive>

    private val mValues: MutableList<Archive> = mutableListOf()

    init {
        mOnClickListener = View.OnClickListener { v ->
            val item = v.tag as Archive
            // Notify the active callbacks interface (the activity, if the fragment is attached to
            // one) that an item has been selected.
            mListener?.onListFragmentInteraction(item)
        }
        mValuesCopy = mValues.toList()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_archive, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = mValues[position]
        holder.archiveName.text = item.title
        GlobalScope.launch(Dispatchers.Main) { holder.archiveImage.setImageBitmap(DatabaseReader.getArchiveImage(item, holder.mContentView.context.filesDir)) }

        with(holder.mView) {
            tag = item
            setOnClickListener(mOnClickListener)
        }
    }

    fun updateDataCopy(list: List<Archive>) {
        mValues.clear()
        mValues.addAll(list)
        mValuesCopy = mValues.toList()
        notifyDataSetChanged()
    }

    fun getRandomArchive() : Archive {
        return mValues.random()
    }

    fun filter(filter: String?) {
        if (filter == null)
            return

        mValues.clear()
        if (filter.isEmpty())
            mValues.addAll(mValuesCopy)
        else {
            val normalized = filter.toLowerCase()
            for (archive in mValuesCopy) {
                if (archive.title.toLowerCase().contains(normalized))
                    mValues.add(archive)
                else {
                   val terms = filter.split(Regex("\\s"))
                    for (term in terms) {
                        if (archive.containsTag(term))
                            mValues.add(archive)
                    }
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = mValues.size

    inner class ViewHolder(val mView: View) : RecyclerView.ViewHolder(mView) {
        val mContentView: CardView = mView.archive_card
        val archiveName: TextView = mContentView.findViewById(R.id.archive_label)
        val archiveImage: ImageView = mContentView.findViewById(R.id.archive_thumb)

        override fun toString(): String {
            return super.toString() + " '" + archiveName + "'"
        }
    }
}
