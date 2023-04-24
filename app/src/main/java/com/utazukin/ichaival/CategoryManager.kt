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
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import com.google.gson.stream.JsonReader
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.io.File
import java.io.InputStream
import java.util.*

interface CategoryListener {
    fun onCategoriesUpdated(categories: List<ArchiveCategory>?, firstUpdate: Boolean)
}

@Entity(tableName = "archiveCategory")
data class ArchiveCategoryFull(
    val name: String,
    @PrimaryKey val id: String,
    val search: String? = null,
    val pinned: Boolean,
    val updatedAt: Long)

@Parcelize
data class ArchiveCategory(
    val name: String,
    val id: String,
    val search: String? = null) : Parcelable {
    @Ignore @IgnoredOnParcel
    val isStatic = search.isNullOrBlank()

    constructor(name: String, id: String) : this(name, id, "")
}

@Entity(primaryKeys = ["categoryId", "archiveId"])
data class StaticCategoryRef(val categoryId: String, val archiveId: String, val updatedAt: Long)

object CategoryManager {
    private const val categoriesFilename = "categories.json"
    private val listeners = mutableListOf<CategoryListener>()

    fun addUpdateListener(listener: CategoryListener) {
        listeners.add(listener)
    }

    suspend inline fun getAllCategories() = DatabaseReader.getAllCategories()

    fun removeUpdateListener(listener: CategoryListener) = listeners.remove(listener)

    suspend fun updateCategories(categoryJson: InputStream?, filesDir: File) {
        val categoriesFile = File(filesDir, categoriesFilename)
        withContext(Dispatchers.IO) { categoryJson?.use { categoriesFile.outputStream().use { f -> it.copyTo(f) } } }
        if (categoriesFile.exists()) {
            parseCategories(categoriesFile)
            updateListeners()
        }
    }

    suspend inline fun getStaticCategories(id: String) = DatabaseReader.getCategoryArchives(id)

    suspend fun addArchivesToCategory(categoryId: String, archiveIds: Collection<String>) {
        val references = buildList {
            for (id in archiveIds) {
                add(StaticCategoryRef(categoryId, id, 0))
            }
        }

        DatabaseReader.insertStaticCategories(references)
        updateListeners()
    }

    suspend fun createCategory(context: Context, name: String, search: String? = null, pinned: Boolean = false): ArchiveCategory? {
        val json = WebHandler.createCategory(context, name, search, pinned)
        val category = json?.run { ArchiveCategory(name, getString("category_id"), search) }
        if (category != null)
            DatabaseReader.insertCategory(category)
        return category
    }

    private suspend fun updateListeners() {
        val categories = getAllCategories()
        withContext(Dispatchers.Main.immediate) {
            for (listener in listeners)
                listener.onCategoriesUpdated(categories, false)
        }
}

    private suspend fun parseCategories(categoriesFile: File) = withContext(Dispatchers.IO) {
        DatabaseReader.withTransaction {
            val categories = ArrayList<ArchiveCategoryFull>(DatabaseReader.MAX_WORKING_ARCHIVES)
            val staticRefs = ArrayList<StaticCategoryRef>(DatabaseReader.MAX_WORKING_ARCHIVES)
            val currentTime = Calendar.getInstance().timeInMillis
            JsonReader(categoriesFile.bufferedReader(Charsets.UTF_8)).use { reader ->
                val archives = mutableListOf<String>()
                reader.beginArray()
                while (reader.hasNext()) {
                    var name = ""
                    var id = ""
                    var search = ""
                    var pinned = false
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
                            staticRefs.add(StaticCategoryRef(id, archive, currentTime))

                            if (staticRefs.size == DatabaseReader.MAX_WORKING_ARCHIVES) {
                                DatabaseReader.insertStaticCategories(staticRefs)
                                staticRefs.clear()
                            }
                        }
                        archives.clear()
                    }

                    categories.add(ArchiveCategoryFull(name, id, search, pinned, currentTime))
                    if (categories.size == DatabaseReader.MAX_WORKING_ARCHIVES) {
                        DatabaseReader.insertCategories(categories)
                        categories.clear()
                    }
                }

                reader.endArray()
            }

            if (categories.isNotEmpty())
                DatabaseReader.insertCategories(categories)
            if (staticRefs.isNotEmpty())
                DatabaseReader.insertStaticCategories(staticRefs)
            
            DatabaseReader.removeOutdatedCategories(currentTime)
        }
    }
}