/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2024 Utazukin
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

data class LanraragiVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<LanraragiVersion> {
    override fun compareTo(other: LanraragiVersion): Int {
        if (this == other)
            return 0

        if (major < other.major)
            return -1

        if (major == other.major && minor < other.minor)
            return -1

        if (major == other.major && minor == other.minor && patch < other.patch)
            return -1

        return 1
    }
}

object ServerManager {
    private const val serverInfoFilename = "info.json"
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
    private var version = LanraragiVersion(0, 0, 0)
    private val lowestVersion = LanraragiVersion(0, 8, 5)

    suspend fun init(context: Context, useCachedInfo: Boolean, force: Boolean = false) : Boolean? {
        if (initialized && !force)
            return checkVersionAtLeast(lowestVersion)

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

        initialized = true

        return serverInfo?.run {
            val lanraragiVersionString = getString("version")
            if (lanraragiVersionString.isNotBlank()) {
                val versionRegex = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")
                versionRegex.matchEntire(lanraragiVersionString)?.groupValues?.let {
                    version = LanraragiVersion(it[1].toInt(), it[2].toInt(), it[3].toInt())
                }
            }

            pageSize = getInt("archives_per_page")
            serverTracksProgress = optInt("server_tracks_progress", 1) == 1
            hasPassword = getInt("has_password") == 1
            serverName = getString("name")
            checkVersionAtLeast(lowestVersion)
        }
    }

    fun checkVersionAtLeast(major: Int, minor: Int, patch: Int) = checkVersionAtLeast(LanraragiVersion(major, minor, patch))

    private fun checkVersionAtLeast(lrrVersion: LanraragiVersion) = version >= lrrVersion

    suspend fun generateTagSuggestions() {
        if (tagSuggestions.isEmpty()) {
            withContext(Dispatchers.IO) {
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
    }
}