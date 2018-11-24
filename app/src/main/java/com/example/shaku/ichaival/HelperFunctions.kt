package com.example.shaku.ichaival

import android.content.res.Resources

fun getDpWidth(pxWidth: Int) : Int {
    val metrics = Resources.getSystem().displayMetrics
    return (pxWidth / metrics.density).toInt()
}