package com.example.shaku.ichaival

import android.content.Intent
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.design.widget.NavigationView
import android.support.v4.widget.DrawerLayout
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.helper.ItemTouchHelper
import com.example.shaku.ichaival.ArchiveFragment.OnListFragmentInteractionListener

class ArchiveList : AppCompatActivity(), OnListFragmentInteractionListener, ReaderTabViewAdapter.OnTabInteractionListener {

    private lateinit var drawerLayout: DrawerLayout

    override fun onListFragmentInteraction(archive: Archive?) {
        if (archive != null)
            startReaderActivity(archive.id)
    }

    private fun startReaderActivity(id: String) {
        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_list)
        setSupportActionBar(findViewById(R.id.toolbar))

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
        DatabaseReader.updateServerLocation(prefs.getString(getString(R.string.server_address_preference), ""))

        drawerLayout = findViewById(R.id.drawer_layout)
        val navView: NavigationView = drawerLayout.findViewById(R.id.nav_view)
        val context = this
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    val intent = Intent(context, SettingsActivity::class.java)
                    startActivity(intent)
                    drawerLayout.closeDrawers()
                    true
                }
                else -> false
            }
        }

        val tabView: RecyclerView = findViewById(R.id.tab_view)
        val listener = this
        with(tabView) {
            layoutManager = LinearLayoutManager(context)
            adapter = ReaderTabViewAdapter(ReaderTabHolder.instance.getTabList(), listener)
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
    }

    override fun onTabInteraction(tab: ReaderTabHolder.ReaderTab) {
        startReaderActivity(tab.id)
    }
}
