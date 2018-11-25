package com.example.shaku.ichaival

import android.content.Context
import android.preference.EditTextPreference
import android.util.AttributeSet

class SummaryTextPreference : EditTextPreference {
    constructor(context: Context, set: AttributeSet) : super(context, set)

    constructor(context: Context) : super(context)

    override fun onDialogClosed(positiveResult: Boolean) {
        super.onDialogClosed(positiveResult)
        summary = summary
    }

    override fun getSummary(): CharSequence {
        return text
    }
}