package com.example.shaku.ichaival

import android.graphics.Bitmap
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import com.bumptech.glide.RequestManager
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class ThumbRecyclerViewAdapter(private val listener: ThumbInteractionListener?,
                               private val archive: Archive,
                               private val glide: RequestManager)
    : RecyclerView.Adapter<ThumbRecyclerViewAdapter.ViewHolder>() {

    private val onClickListener: View.OnClickListener
    private var maxThumbnails = 10
    val hasMorePreviews: Boolean
        get() = maxThumbnails < archive.numPages

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

    fun increasePreviewCount() {
        if (maxThumbnails < archive.numPages) {
            maxThumbnails *= 2
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.thumb_layout, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = if (archive.numPages > maxThumbnails) maxThumbnails else archive.numPages

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val page = position
        GlobalScope.launch(Dispatchers.Main) {
            val image = async { archive.getPageImage(page) }.await()
            holder.pageNumView.text = (page + 1).toString()

            with(holder.thumbView) {
                tag = page
                setOnClickListener(onClickListener)
            }

            glide.asBitmap().load(image).into(object: SimpleTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    holder.pageNumView.visibility = View.GONE
                    holder.progressBar.visibility = View.GONE
                    holder.thumbView.setImageBitmap(resource)
                }
            })
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