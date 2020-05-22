/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2020 Utazukin
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

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.io.File

object ServerManager {
    private const val serverInfoFilename = "info.json"
    private var lanraragiVersionString = ""
    private var majorVersion = 0
    private var minorVersion = 0
    private var patchVersion = 0
    var pageSize = 50
        private set

    fun init(context: Context) {
        val infoFile = File(context.filesDir, serverInfoFilename)
        var serverInfo = WebHandler.getServerInfo()
        if (serverInfo == null) {
            if (infoFile.exists())
                serverInfo = JSONObject(infoFile.readText())
        }
        else
            infoFile.writeText(serverInfo.toString())

        lanraragiVersionString = serverInfo?.getString("version") ?: ""
        if (!lanraragiVersionString.isBlank()) {
            val versionRegex = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")
            versionRegex.matchEntire(lanraragiVersionString)?.let {
                majorVersion = Integer.parseInt(it.groupValues[0])
                minorVersion = Integer.parseInt(it.groupValues[1])
                patchVersion = Integer.parseInt(it.groupValues[2])
            }
        }

        pageSize = when {
            majorVersion > 0 || minorVersion >= 7 -> serverInfo!!.getInt("archives_per_page")
            else -> {
                val prefManager = PreferenceManager.getDefaultSharedPreferences(context)
                prefManager.castStringPrefToInt(context.getString(R.string.search_page_key), 50)
            }
        }
    }
}