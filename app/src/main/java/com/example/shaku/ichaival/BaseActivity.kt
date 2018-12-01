package com.example.shaku.ichaival

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

abstract class BaseActivity : AppCompatActivity(), DatabaseErrorListener {
    override fun onStart() {
        super.onStart()
        DatabaseReader.errorListener = this
    }

    override fun onStop() {
        super.onStop()
        DatabaseReader.errorListener = null
    }

    override fun onError(error: String) {
        Toast.makeText(this, "Error: $error", Toast.LENGTH_LONG).show()
    }
}