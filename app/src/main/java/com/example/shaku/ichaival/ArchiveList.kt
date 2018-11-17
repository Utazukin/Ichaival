package com.example.shaku.ichaival

import android.content.Intent
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import com.example.shaku.ichaival.ArchiveFragment.OnListFragmentInteractionListener

class ArchiveList : AppCompatActivity(), OnListFragmentInteractionListener {

    override fun onListFragmentInteraction(archive: Archive?) {
        if (archive == null)
            return

        val intent = Intent(this, ReaderActivity::class.java)
        val bundle = Bundle()
        bundle.putString("id", archive.id)
        intent.putExtras(bundle)
        startActivity(intent)
        //finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_archive_list)
    }
}
