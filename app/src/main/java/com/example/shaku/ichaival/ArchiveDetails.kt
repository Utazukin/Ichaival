package com.example.shaku.ichaival

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.GridLayoutManager
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.example.shaku.ichaival.ReaderTabViewAdapter.OnTabInteractionListener
import com.example.shaku.ichaival.ThumbRecyclerViewAdapter.ThumbInteractionListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

class ArchiveDetails : AppCompatActivity(), ThumbInteractionListener, OnTabInteractionListener {
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

        val bookmarkButton: Button = findViewById(R.id.bookmark_button)
        with(bookmarkButton) {
            setOnClickListener {
                val copy = archive
                if (copy != null) {
                    if (ReaderTabHolder.isTabbed(copy.id)) {
                        ReaderTabHolder.removeTab(copy.id)
                        text = getString(R.string.bookmark)
                    } else {
                        ReaderTabHolder.addTab(copy, 0)
                        text = getString(R.string.unbookmark)
                    }
                }
            }
            text = getString(if (ReaderTabHolder.isTabbed(archive?.id)) R.string.unbookmark else R.string.bookmark)
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

        val tabView: RecyclerView = findViewById(R.id.tab_view)
        val tabListener = this
        with(tabView) {
            layoutManager = LinearLayoutManager(context)
            adapter = ReaderTabViewAdapter(ReaderTabHolder.getTabList(), tabListener)
        }

        val swipeHandler = object: ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(p0: RecyclerView, p1: RecyclerView.ViewHolder, p2: RecyclerView.ViewHolder): Boolean {
                return false
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, p1: Int) {
                val adapter = tabView.adapter as ReaderTabViewAdapter
                adapter.removeTab(holder.adapterPosition)
                bookmarkButton.text = getString((R.string.bookmark))
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(tabView)
    }

    override fun onTabInteraction(tab: ReaderTab) {
        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", tab.id)
        intent.putExtras(bundle)
        startActivity(intent)
        finish()
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
