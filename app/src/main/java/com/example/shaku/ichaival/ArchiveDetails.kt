package com.example.shaku.ichaival

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.example.shaku.ichaival.ThumbRecyclerViewAdapter.ThumbInteractionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class ArchiveDetails : AppCompatActivity(), ThumbInteractionListener {
    private var archive: Archive? = null
    private lateinit var thumbAdapter: ThumbRecyclerViewAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_details)

        val bundle = intent.extras
        if (bundle != null) {
            val arcid = bundle.getString("id")
            if (arcid != null) {
                GlobalScope.launch(Dispatchers.Main) {
                    archive = async { DatabaseReader.getArchive(arcid, applicationContext.filesDir) }.await()
                    setUpDetailView()
                }
            }
        }
    }

    private fun setUpDetailView() {
        val listener: ThumbInteractionListener = this
        val listView: RecyclerView = findViewById(R.id.thumb_list)
        val loadPreviewsButton: Button = findViewById(R.id.load_thumbs_button)
        with(listView) {
            layoutManager = GridLayoutManager(context, 2)
            thumbAdapter = ThumbRecyclerViewAdapter(listener, archive!!, Glide.with(this))
            adapter = thumbAdapter
            isNestedScrollingEnabled = false
        }

        loadPreviewsButton.setOnClickListener {
            thumbAdapter.increasePreviewCount()
            if (!thumbAdapter.hasMorePreviews)
                loadPreviewsButton.visibility = View.GONE
        }

        val titleView: TextView = findViewById(R.id.title)
        titleView.text = archive?.title

        GlobalScope.launch(Dispatchers.Main) {
            val thumbView: ImageView = findViewById(R.id.cover)
            val thumb = async { DatabaseReader.getArchiveImage(archive!!, filesDir) }.await()
            Glide.with(thumbView).asBitmap().load(thumb).into(thumbView)

            //Replace the thumbnail with the full size image.
            val image = async { archive?.getPageImage(0) }.await()
            Glide.with(thumbView).asBitmap().load(image).into(object: SimpleTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    thumbView.setImageBitmap(resource)
                }
            })
        }
    }

    override fun onThumbSelection(page: Int) {
        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", archive?.id)
        bundle.putInt("page", page)
        intent.putExtras(bundle)
        startActivity(intent)
    }
}
