/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2021 Utazukin
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

import android.app.Activity
import androidx.preference.PreferenceManager
import java.io.File
import java.io.IOException

class CrashLogger private constructor(private val activity: Activity) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        var trace = e.stackTrace
        var report = e.toString() + "\n\n"
        report += "-------Stack Trace --------\n\n"
        for (element in trace)
            report += "   $element\n"
        report += "-------------------------------\n\n"

        report += "----------- Cause ------------\n\n"
        e.cause?.let {
            report += "$it\n\n"
            trace = it.stackTrace
            for (element in trace)
                report += "    $element\n"
        }
        report += "--------------------------------\n\n"

        try {
            val file = File(activity.noBackupFilesDir, "crash.log")
            file.writeText(report)
        } catch (e: IOException) {}

        defaultHandler?.uncaughtException(t, e)
    }

    companion object {
        @JvmStatic
        fun createCrashLogger(activity: Activity) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
            val crashLogEnabled = prefs.getBoolean(activity.getString(R.string.log_pref), false)
            if (crashLogEnabled)
                Thread.setDefaultUncaughtExceptionHandler(CrashLogger(activity))
        }
    }
}