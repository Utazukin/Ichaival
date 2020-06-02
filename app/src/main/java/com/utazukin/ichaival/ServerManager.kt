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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

interface ArchiveCategory {
    val name: String
    val id: String
    val pinned: Boolean
}

class DynamicCategory(override val name: String,
                      override val id: String,
                      override val pinned: Boolean,
                      val search: String) : ArchiveCategory
class StaticCategory(override val name: String,
                     override val id: String,
                     override val pinned: Boolean,
                     val archiveIds: List<String>) : ArchiveCategory

object ServerManager {
    private const val serverInfoFilename = "info.json"
    private const val categoriesFilename = "categories.json"
    var majorVersion = 0
        private set
    var minorVersion = 0
        private set
    var patchVersion = 0
        private set
    var pageSize = 50
        private set
    var tagSuggestions: Array<TagSuggestion> = arrayOf()
        private set
    var categories: List<ArchiveCategory>? = null
        private set
    private var initialized = false

    fun init(context: Context, useCachedInfo: Boolean, force: Boolean = false) {
        if (initialized && !force)
            return

        val infoFile = File(context.filesDir, serverInfoFilename)
        val serverInfo = if (!useCachedInfo || force) {
            val info = WebHandler.getServerInfo()
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

        val lanraragiVersionString = serverInfo?.getString("version") ?: ""
        if (lanraragiVersionString.isNotBlank()) {
            val versionRegex = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)")
            versionRegex.matchEntire(lanraragiVersionString)?.let {
                majorVersion = Integer.parseInt(it.groupValues[1])
                minorVersion = Integer.parseInt(it.groupValues[2])
                patchVersion = Integer.parseInt(it.groupValues[3])
            }
        }

        pageSize = when {
            majorVersion > 0 || minorVersion >= 7 -> {
                serverInfo!!.getInt("archives_per_page")
            }
            else -> {
                val prefManager = PreferenceManager.getDefaultSharedPreferences(context)
                prefManager.castStringPrefToInt(context.getString(R.string.search_page_key), 50)
            }
        }

        if (majorVersion > 0 || minorVersion >= 7)
            categories = parseCategories(context.filesDir)

        initialized = true
    }

    fun generateTagSuggestions() {
        if (tagSuggestions.isEmpty()) {
            WebHandler.generateSuggestionList()?.let {
                tagSuggestions = Array(it.length()) { i ->
                    val item = it.getJSONObject(i)
                    TagSuggestion(
                        item.getString("text"),
                        item.getString("namespace"),
                        item.getInt("weight")
                    )
                }
                tagSuggestions.sortByDescending { tag -> tag.weight }
            }
        }
    }

    private fun parseCategories(fileDir: File) : List<ArchiveCategory>? {
        val categoriesFile = File(fileDir, categoriesFilename)
        var jsonCategories: JSONArray? = WebHandler.getCategories()
        when {
            jsonCategories != null -> categoriesFile.writeText(jsonCategories.toString())
            categoriesFile.exists() -> jsonCategories = JSONArray(categoriesFile.readText())
            else -> return null
        }

        val list =  MutableList(jsonCategories.length()) { i ->
            val category = jsonCategories.getJSONObject(i)
            val search = category.getString("search")
            val name = category.getString("name")
            val id = category.getString("id")
            val pinned = category.getInt("pinned") == 1
            if (search.isNotBlank())
                DynamicCategory(name, id, pinned, category.getString("search"))
            else {
                val archives = category.getJSONArray("archives")
                StaticCategory(name, id, pinned, MutableList(archives.length()) { k -> archives.getString(k) } )
            }
        }
        list.sortBy { it.pinned }

        return list
    }
}