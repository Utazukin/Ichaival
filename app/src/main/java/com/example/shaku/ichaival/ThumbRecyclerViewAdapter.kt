package com.example.shaku.ichaival

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.coroutines.*

class ThumbRecyclerViewAdapter(
    private val listener: ThumbInteractionListener?,
    private val archive: Archive)
    : RecyclerView.Adapter<ThumbRecyclerViewAdapter.ViewHolder>() {

    private val onClickListener: View.OnClickListener
    private var maxThumbnails = 10
    val hasMorePreviews: Boolean
        get() = maxThumbnails < archive.numPages
    private val imageLoadingJobs: MutableMap<ViewHolder, Job> = mutableMapOf()

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
            val currentCount = itemCount
            maxThumbnails *= 2
            notifyItemRangeInserted(currentCount + 1, Math.min(maxThumbnails, archive.numPages))
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
        holder.pageNumView.text = (page + 1).toString()

        val job = GlobalScope.launch(Dispatchers.Main) {
            val image = async { archive.getPageImage(page) }.await()

            with(holder.thumbView) {
                tag = page
                setOnClickListener(onClickListener)
            }

            Glide.with(holder.view).asBitmap().load(image).into(object : SimpleTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    holder.pageNumView.visibility = View.GONE
                    holder.progressBar.visibility = View.GONE
                    holder.thumbView.setImageBitmap(resource)
                }
            })
        }
        imageLoadingJobs[holder] = job
    }

    override fun onViewRecycled(holder: ViewHolder) {
        imageLoadingJobs[holder]?.cancel()
        imageLoadingJobs.remove(holder)
        super.onViewRecycled(holder)
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