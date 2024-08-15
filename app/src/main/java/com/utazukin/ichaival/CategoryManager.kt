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
import java.io.InputStream
import java.util.Calendar

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
    private val listeners = mutableListOf<CategoryListener>()

    fun addUpdateListener(listener: CategoryListener) {
        listeners.add(listener)
    }

    suspend inline fun getAllCategories() = DatabaseReader.getAllCategories()

    fun removeUpdateListener(listener: CategoryListener) = listeners.remove(listener)

    suspend inline fun isInCategory(categoryId: String, archiveId: String) = DatabaseReader.isInCategory(categoryId, archiveId)

    suspend fun updateCategories() {
        WebHandler.getCategories()?.let {
            parseCategories(it)
            updateListeners()
        }
    }

    suspend inline fun getStaticCategories(id: String) = DatabaseReader.getCategoryArchives(id)

    suspend fun addArchivesToCategory(categoryId: String, archiveIds: Collection<String>) {
        val references = buildList(archiveIds.size) {
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

    private suspend fun parseCategories(categoriesStream: InputStream) = DatabaseReader.withTransaction {
        val currentTime = Calendar.getInstance().timeInMillis
        JsonReader(categoriesStream.bufferedReader(Charsets.UTF_8)).use { reader ->
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
                    for (archive in archives)
                        DatabaseReader.insertStaticCategory(StaticCategoryRef(id, archive, currentTime))
                    archives.clear()
                }

                DatabaseReader.insertCategory(ArchiveCategoryFull(name, id, search, pinned, currentTime))
            }

            reader.endArray()
        }

        DatabaseReader.removeOutdatedCategories(currentTime)
    }
}