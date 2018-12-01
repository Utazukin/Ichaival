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

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.utazukin.ichaival.ReaderTabViewAdapter.OnTabInteractionListener

class ArchiveDetails : BaseActivity(), OnTabInteractionListener {
    private var archiveId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_details)
        setSupportActionBar(findViewById(R.id.toolbar))

        val bundle = intent.extras
        if (bundle != null) {
            archiveId = bundle.getString("id")
            setUpDetailView()
        }
    }

    private fun setUpDetailView() {
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
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(tabView)

        val pager: ViewPager = findViewById(R.id.details_pager)
        pager.adapter = DetailsPagerAdapter(supportFragmentManager)
        pager.addOnPageChangeListener(object: ViewPager.OnPageChangeListener{
            override fun onPageScrollStateChanged(state: Int) {
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
            }

            override fun onPageSelected(position: Int) {
                when(position) {
                    0 -> supportActionBar?.title = getString(R.string.details_title)
                    1 -> supportActionBar?.title = getString(R.string.thumbs_title)
                }
            }

        })
    }

    override fun onTabInteraction(tab: ReaderTab) {
        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", tab.id)
        intent.putExtras(bundle)
        startActivity(intent)
        finish()
    }

    inner class DetailsPagerAdapter(manager: FragmentManager) : FragmentPagerAdapter(manager) {
        override fun getItem(position: Int): Fragment {
            return when(position) {
                0 -> ArchiveDetailsFragment.createInstance(archiveId!!)
                1 -> GalleryPreviewFragment.createInstance(archiveId!!)
                else -> throw IllegalArgumentException("position")
            }
        }

        override fun getCount(): Int = 2
    }
}
