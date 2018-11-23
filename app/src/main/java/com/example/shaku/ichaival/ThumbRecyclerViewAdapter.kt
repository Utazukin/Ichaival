package com.example.shaku.ichaival

import android.graphics.Bitmap
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class ThumbRecyclerViewAdapter(private val listener: ThumbInteractionListener?, private val archive: Archive)
    : RecyclerView.Adapter<ThumbRecyclerViewAdapter.ViewHolder>() {

    private val onClickListener: View.OnClickListener

    init {
        onClickListener = View.OnClickListener { v ->
            val item = v.tag as Int
            listener?.onThumbSelection(item)
        }
        GlobalScope.launch(Dispatchers.Main) {
            launch(Dispatchers.IO) { archive.loadImageUrls() }.join()
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.thumb_layout, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = archive.numPages

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val page = position
        GlobalScope.launch(Dispatchers.Main) {
            val image = async { archive.getPageImage(page) }.await()
            holder.pageNumView.text = (page + 1).toString()
            Glide.with(holder.thumbView).asBitmap().load(image).into(object: SimpleTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    holder.pageNumView.visibility = View.GONE
                    holder.progressBar.visibility = View.GONE
                    holder.thumbView.setImageBitmap(resource)
                }

            })
        }

        with(holder.thumbView) {
            tag = page
            setOnClickListener(onClickListener)
        }
    }

    interface ThumbInteractionListener {
        fun onThumbSelection(page: Int)
    }

    inner class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val thumbView: PhotoView = view.findViewById(R.id.small_thumb)
        val pageNumView: TextView = view.findViewById(R.id.page_num)
        val progressBar: ProgressBar = view.findViewById(R.id.progressBar)
    }
}