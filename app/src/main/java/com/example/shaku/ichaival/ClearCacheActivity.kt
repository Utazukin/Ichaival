package com.example.shaku.ichaival

import android.app.Activity
import android.os.Bundle

class ClearCacheActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DatabaseReader.clearCache(this)
        finish()
    }
}