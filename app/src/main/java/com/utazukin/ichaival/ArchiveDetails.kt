/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2026 Utazukin
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

import android.animation.LayoutTransition
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.utazukin.ichaival.ArchiveDetailsFragment.TagInteractionListener
import com.utazukin.ichaival.ThumbRecyclerViewAdapter.ThumbInteractionListener
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.reader.ReaderActivity
import kotlinx.coroutines.launch
import kotlin.math.max

const val FROM_READER_PAGE = "READER_PAGE"

class ArchiveDetails : BaseActivity(), TagInteractionListener, ThumbInteractionListener, ChapterEditListener {
    private var archiveId: String? = null
    private var pageCount = -1
    private var readerPage = -1
    private lateinit var pager: ViewPager2
    private lateinit var toolbar: Toolbar
    private val resultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK)
            finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_details)
        supportPostponeEnterTransition()

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_arrow_back_white_24dp)
        }

        intent.extras?.run {
            archiveId = getString("id")
            readerPage = getInt(FROM_READER_PAGE, -1)
            setUpDetailView()
        }

        archiveId?.let { launch { extractArchive(it) } }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.archive_details_menu, menu)

        archiveId?.let {
            launch {
                DatabaseReader.getArchive(it)?.run {
                    toolbar.menu.findItem(R.id.mark_read_item)?.isVisible = isNew || currentPage < numPages - 1
                }
            }
        }

        menu.findItem(R.id.delete_archive_item)?.isVisible = ServerManager.canEdit

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.random_item -> {
                launch {
                    DatabaseReader.getRandomArchive().let {
                        startDetailsActivity(it.id)
                        supportFinishAfterTransition()
                    }
                }
            }
            R.id.mark_read_item -> {
                archiveId?.let {
                    launch {
                        DatabaseReader.setArchiveNewFlag(it)
                        DatabaseReader.getArchive(it)?.run {
                            DatabaseReader.updateProgress(it, numPages - 1)
                            WebHandler.updateProgress(it, numPages - 1)
                        }
                        item.isVisible = false
                    }
                }
            }
            R.id.delete_archive_item -> {
                archiveId?.let {
                    launch {
                        DatabaseReader.getArchive(it)?.let { arc ->
                            val builder = AlertDialog.Builder(this@ArchiveDetails).apply {
                                setTitle(R.string.delete_archive_item)
                                setMessage(getString(R.string.delete_archive_prompt, arc.title))
                                setPositiveButton(R.string.yes) { dialog, _ ->
                                    dialog.dismiss()
                                    lifecycleScope.launch {
                                        val success = WebHandler.deleteArchive(it)
                                        if (success) {
                                            Toast.makeText(applicationContext, getString(R.string.deleted_archive, arc.title), Toast.LENGTH_SHORT).show()
                                            DatabaseReader.deleteArchive(it)
                                            finish()
                                        }
                                    }
                                }

                                setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
                            }
                            val dialog = builder.create()
                            dialog.show()
                        }
                    }
                }
            }
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    suspend fun extractArchive(id: String) {
        DatabaseReader.getArchive(id)?.let {
            if (it.numPages <= 0)
                it.extract(this@ArchiveDetails)
            toolbar.menu.findItem(R.id.mark_read_item)?.isVisible = it.isNew || it.currentPage < it.numPages - 1
            pageCount = it.numPages
            if (pager.currentItem == 1)
                supportActionBar?.subtitle = resources.getQuantityString(R.plurals.page_count, pageCount, pageCount)
        }
    }

    private fun setUpDetailView() {
        pager = findViewById(R.id.details_pager)

        archiveId?.let {
            pager.adapter = DetailsPagerAdapter(it)
            pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {}
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}
                override fun onPageSelected(position: Int) {
                    when (position) {
                        0 -> supportActionBar?.run {
                            title = getString(R.string.details_title)
                            subtitle = null
                            toolbar.layoutTransition = LayoutTransition()
                        }
                        1 -> supportActionBar?.run {
                            title = getString(R.string.thumbs_title)
                            subtitle = if (pageCount >= 0) resources.getQuantityString(R.plurals.page_count, pageCount, pageCount) else null
                        }
                    }
                }
            })
        }

        val tabLayout: TabLayout = findViewById(R.id.details_tabs)
        val mediator = TabLayoutMediator(tabLayout, pager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.details_title)
                1 -> getString(R.string.thumbs_title)
                else -> null
            }
        }
        mediator.attach()
    }

    override fun onTagInteraction(tag: String) {
        val intent = Intent(this, ArchiveSearch::class.java)
        val bundle = Bundle()
        bundle.putString(TAG_SEARCH, tag)
        intent.putExtras(bundle)
        resultLauncher.launch(intent)
    }

    override fun onLongPressTab(tab: ReaderTab): Boolean {
        val tagFragment = TagDialogFragment.newInstance(tab.id)
        tagFragment.setDetailsButtonListener {
            if (it != archiveId) {
                startDetailsActivity(it)
                finish()
            }
        }
        tagFragment.show(supportFragmentManager, "tag_popup")
        return true
    }

    override fun onThumbSelection(page: Int) {
        startReaderActivityForResult(page)
    }

    override fun onThumbLongPress(page: Int): Boolean {
        archiveId?.let {
            AlertDialog.Builder(this).apply {
                val choices = arrayOf(resources.getString(R.string.use_thumb), resources.getString(R.string.add_edit_chapter))
                setItems(choices) { dialog, i ->
                    when (i) {
                        0 -> {
                            launch {
                                DatabaseReader.refreshThumbnail(it, this@ArchiveDetails, page)
                                Toast.makeText(this@ArchiveDetails, getString(R.string.update_thumbnail_message), Toast.LENGTH_SHORT).show()
                            }
                            dialog.dismiss()
                        }
                        1 -> {
                            val editDialog = EditChapterDialogFragment.newInstance(page, it)
                            editDialog.show(supportFragmentManager, "chapter_dialog")
                        }
                    }
                }
                show()
            }
        }
        return true
    }

    override fun onChapterEdit(name: String, page: Int, delete: Boolean) {
        archiveId?.let {
            launch {
                if (!delete) {
                    val chapter = ToCEntryUpdate(name, page, it)
                    if (WebHandler.addToCEntry(chapter)) {
                        DatabaseReader.updateToCEntry(chapter)
                        getThumbFragment()?.updateToCButton(firstThumb = page)
                    }
                }
                else if (WebHandler.removeToCEntry(it, page)) {
                    val toc = DatabaseReader.getToC(it)
                    val prev = max(toc.indexOfFirst { x -> x.page == page } - 1, 0)
                    DatabaseReader.removeToCEntry(page, it)
                    getThumbFragment()?.updateToCButton(firstThumb = prev)
                }
            }
        }
    }

    private fun getThumbFragment(): GalleryPreviewFragment? {
        val fragmentId = pager.adapter?.getItemId(1) ?: return null
        return supportFragmentManager.findFragmentByTag("f$fragmentId") as? GalleryPreviewFragment
    }

    fun startReaderActivityForResult(page: Int = -1) {
        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle().apply {
            putString("id", archiveId)
            if (page >= 0)
                putInt("page", page)
        }

        intent.putExtras(bundle)
        resultLauncher.launch(intent)
    }

    override fun onTabInteraction(tab: ReaderTab) {
        if (tab.id != archiveId) {
            super.onTabInteraction(tab)
            finish()
        }
    }

    override fun startDetailsActivity(id: String) {
        if (id != archiveId)
            super.startDetailsActivity(id)
    }

    override fun addIntentFlags(intent: Intent, id: String) {
        super.addIntentFlags(intent, id)
        if (id != archiveId)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    inner class DetailsPagerAdapter(private val archiveId: String) : FragmentStateAdapter(this) {
        override fun createFragment(position: Int): Fragment {
            return when(position) {
                0 -> ArchiveDetailsFragment.createInstance(archiveId)
                1 -> GalleryPreviewFragment.createInstance(archiveId, readerPage)
                else -> throw IllegalArgumentException("position")
            }
        }

        override fun getItemCount(): Int = 2
    }
}
