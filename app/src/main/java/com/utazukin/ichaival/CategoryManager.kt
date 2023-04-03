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
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import com.google.gson.stream.JsonReader
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

interface CategoryListener {
    fun onCategoriesUpdated(categories: List<ArchiveCategory>?)
}

@Entity
data class ArchiveCategory(
    val name: String,
    @PrimaryKey val id: String,
    val search: String? = null,
    val pinned: Boolean) {
    @Ignore val isStatic = search.isNullOrBlank()
}

@Entity(primaryKeys = ["categoryId", "archiveId"])
data class StaticCategoryRef(val categoryId: String, val archiveId: String)

object CategoryManager {
    private const val categoriesFilename = "categories.json"
    private val listeners = mutableListOf<CategoryListener>()
    private val scope = MainScope()
    var hasCategories = false
        private set

    fun addUpdateListener(listener: CategoryListener) {
        listeners.add(listener)
        scope.launch {
            listener.onCategoriesUpdated(getAllCategories())
        }
    }

    suspend inline fun getAllCategories() = DatabaseReader.database.archiveDao().getAllCategories()

    fun removeUpdateListener(listener: CategoryListener) = listeners.remove(listener)

    suspend fun updateCategories(categoryJson: InputStream?, filesDir: File) {
        if (categoryJson == null)
            return

        val categoriesFile = File(filesDir, categoriesFilename)
        categoryJson.use { categoriesFile.outputStream().use { f -> it.copyTo(f) } }
        parseCategories(categoriesFile)
        updateListeners()
    }

    suspend inline fun getStaticCategories(id: String) = DatabaseReader.database.archiveDao().getCategoryArchives(id)

    suspend fun createCategory(context: Context, name: String, search: String? = null, pinned: Boolean = false): ArchiveCategory? {
        val json = WebHandler.createCategory(context, name, search, pinned)
        return json?.run { ArchiveCategory(name, getString("category_id"), search, pinned) }
    }

    private fun updateListeners() {
        scope.launch {
            val categories = getAllCategories()
            for (listener in listeners)
                listener.onCategoriesUpdated(categories)
        }
    }

    private suspend fun parseCategories(categoriesFile: File) = withContext(Dispatchers.IO) {
        DatabaseReader.database.withTransaction {
            val categories = ArrayList<ArchiveCategory>(DatabaseReader.MAX_WORKING_ARCHIVES)
            val staticRefs = ArrayList<StaticCategoryRef>(DatabaseReader.MAX_WORKING_ARCHIVES)
            var insertedCategories = false
            JsonReader(categoriesFile.bufferedReader()).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    var name = ""
                    var id = ""
                    var search = ""
                    var pinned = false
                    val archives = mutableListOf<String>()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "name" -> name = reader.nextString()
                            "id" -> id = reader.nextString()
                            "pinned" -> pinned = reader.nextInt() == 1
                            "search" -> search = reader.nextString()
                            "archives" -> {
                                archives.clear()
                                reader.beginArray()
                                while (reader.hasNext())
                                    archives.add(reader.nextString())
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()

                    if (archives.isNotEmpty()) {
                        for (archive in archives) {
                            staticRefs.add(StaticCategoryRef(id, archive))

                            if (staticRefs.size == DatabaseReader.MAX_WORKING_ARCHIVES) {
                                insertedCategories = true
                                DatabaseReader.database.archiveDao().insertStaticCategories(staticRefs)
                                staticRefs.clear()
                            }
                        }
                        archives.clear()
                    }

                    categories.add(ArchiveCategory(name, id, search, pinned))
                    if (categories.size == DatabaseReader.MAX_WORKING_ARCHIVES) {
                        DatabaseReader.database.archiveDao().insertCategories(categories)
                        categories.clear()
                    }
                }

                reader.endArray()
            }

            hasCategories = insertedCategories || categories.isNotEmpty()
            if (categories.isNotEmpty())
                DatabaseReader.database.archiveDao().insertCategories(categories)
            if (staticRefs.isNotEmpty())
                DatabaseReader.database.archiveDao().insertStaticCategories(staticRefs)
        }
    }
}