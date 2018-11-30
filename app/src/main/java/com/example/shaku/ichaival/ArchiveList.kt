package com.example.shaku.ichaival

import android.content.Intent
import android.os.Bundle
import android.preference.PreferenceManager
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.shaku.ichaival.ArchiveListFragment.OnListFragmentInteractionListener
import com.google.android.material.navigation.NavigationView

class ArchiveList : AppCompatActivity(), OnListFragmentInteractionListener, ReaderTabViewAdapter.OnTabInteractionListener, TabAddedListener {
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView

    override fun onListFragmentInteraction(archive: Archive?) {
        if (archive != null)
            startDetailsActivity(archive.id)
    }

    private fun startReaderActivity(id: String) {
        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", id)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    private fun startDetailsActivity(id: String){
        val intent = Intent(this, ArchiveDetails::class.java)
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
        navView = drawerLayout.findViewById(R.id.nav_view)
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
            adapter = ReaderTabViewAdapter(ReaderTabHolder.getTabList(), listener)
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

    override fun onStart() {
        super.onStart()
        ReaderTabHolder.registerAddListener(this)
    }

    override fun onStop() {
        super.onStop()
        ReaderTabHolder.unregisterAddListener(this)
    }

    override fun onTabAdded(index: Int, id: String) {
        drawerLayout.openDrawer(navView, true)
    }

    override fun onTabInteraction(tab: ReaderTab) {
        startReaderActivity(tab.id)
        drawerLayout.closeDrawers()
    }
}
