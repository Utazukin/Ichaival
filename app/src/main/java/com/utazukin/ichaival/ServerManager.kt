/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2023 Utazukin
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object ServerManager {
    private const val serverInfoFilename = "info.json"
    var majorVersion = 0
        private set
    var minorVersion = 0
        private set
    var patchVersion = 0
        private set
    var pageSize = 50
        private set
    var tagSuggestions: List<TagSuggestion> = emptyList()
        private set
    var serverTracksProgress = false
        private set
    var serverName: String? = null
        private set
    val canEdit
        get() = !hasPassword || WebHandler.apiKey.isNotBlank()
    private var initialized = false
    private var hasPassword = false

    suspend fun init(context: Context, useCachedInfo: Boolean, force: Boolean = false) : Boolean {
        if (initialized && !force)
            return checkVersionAtLeast(0, 8, 2)

        val infoFile = File(context.filesDir, serverInfoFilename)
        val serverInfo = withContext(Dispatchers.IO) {
            if (!useCachedInfo || force) {
                val info = WebHandler.getServerInfo(context)
                if (info == null) {
                    if (infoFile.exists())
                        JSONObject(infoFile.readText())
                    else
                        null
                } else {
                    infoFile.writeText(info.toString())
                    info
                }
            } else if (infoFile.exists())
                JSONObject(infoFile.readText())
            else
                null
        }

        if (serverInfo != null) {
            val lanraragiVersionString = serverInfo.getString("version")
            if (lanraragiVersionString.isNotBlank()) {
                val versionRegex = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")
                versionRegex.matchEntire(lanraragiVersionString)?.let {
                    majorVersion = Integer.parseInt(it.groupValues[1])
                    minorVersion = Integer.parseInt(it.groupValues[2])
                    patchVersion = Integer.parseInt(it.groupValues[3])
                }
            }

            pageSize = serverInfo.getInt("archives_per_page")
            serverTracksProgress = serverInfo.optInt("server_tracks_progress", 1) == 1
            hasPassword = serverInfo.getInt("has_password") == 1
            serverName = serverInfo.getString("name")
        }

        parseCategories(context)

        initialized = true

        return checkVersionAtLeast(0, 8, 2)
    }

    fun checkVersionAtLeast(major: Int, minor: Int, patch: Int) : Boolean {
        if (majorVersion < major)
            return false

        if (majorVersion == major && minorVersion < minor)
            return false

        if (majorVersion == major && minorVersion == minor && patchVersion < patch)
            return false

        return true
    }

    suspend fun generateTagSuggestions() {
        if (tagSuggestions.isEmpty()) {
            WebHandler.generateSuggestionList()?.let {
                val length = it.length()
                tagSuggestions = buildList(length) {
                    for (i in 0 until length) {
                        val item = it.getJSONObject(i)
                        val namespace = item.getString("namespace")
                        if (namespace != "date_added" && namespace != "source")
                            add(TagSuggestion(item.getString("text"), namespace, item.getInt("weight")))
                    }
                    sortByDescending { tag -> tag.weight }
                }
            }
        }
    }

    suspend fun parseCategories(context: Context) {
        WebHandler.getCategories()?.let { CategoryManager.updateCategories(it, context.filesDir) }
    }

}