package com.example.shaku.ichaival

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class ClearCacheActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DatabaseReader.clearCache(this)
        Toast.makeText(applicationContext, "Cleared archive cache", Toast.LENGTH_SHORT).show()
        finish()
    }
}
