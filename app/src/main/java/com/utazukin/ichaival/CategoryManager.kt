/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2022 Utazukin
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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

interface CategoryListener {
    fun onCategoriesUpdated(categories: List<ArchiveCategory>?)
}

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

object CategoryManager {
    private const val categoriesFilename = "categories.json"
    private val listeners = mutableListOf<CategoryListener>()
    var categories: List<ArchiveCategory>? = null
        private set

    fun addUpdateListener(listener: CategoryListener) {
        listeners.add(listener)
        listener.onCategoriesUpdated(categories)
    }

    fun removeUpdateListener(listener: CategoryListener) = listeners.remove(listener)

    suspend fun updateCategories(categoryJson: JSONArray?, filesDir: File) {
        val categoriesFile = File(filesDir, categoriesFilename)
        categories = parseCategories(categoryJson, categoriesFile)
        withContext(Dispatchers.Main) { updateListeners() }
    }

    fun getStaticCategories(id: String) : List<StaticCategory>? {
        return categories?.mapNotNull { it as? StaticCategory }?.filter { it.archiveIds.contains(id) }
    }

    suspend fun createCategory(name: String, search: String? = null, pinned: Boolean = false) : ArchiveCategory? {
        val json = withContext(Dispatchers.IO) { WebHandler.createCategory(name, search, pinned) }
        return json?.run {
            if (search.isNullOrBlank())
                StaticCategory(name, getString("category_id"), pinned, emptyList())
            else
                DynamicCategory(name, getString("category_id"), pinned, search)
        }
    }

    private fun updateListeners() {
        for (listener in listeners)
            listener.onCategoriesUpdated(categories)
    }

    private fun parseCategories(categoryJson: JSONArray?, categoriesFile: File) : List<ArchiveCategory>? {
        var jsonCategories = categoryJson
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
                StaticCategory(name, id, pinned, List(archives.length()) { k -> archives.getString(k) } )
            }
        }
        list.sortBy { it.pinned }

        return list
    }
}