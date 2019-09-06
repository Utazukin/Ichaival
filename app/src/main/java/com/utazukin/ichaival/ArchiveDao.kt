/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2019 Utazukin
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

import androidx.room.*
import org.json.JSONArray
import org.json.JSONObject

@Dao
interface ArchiveDao {
    @Query("Select * from dataarchive")
    fun getAll() : List<DataArchive>

    @Query("Select * from dataarchive order by :sortField desc")
    fun getAllDescending(sortField: String) : List<DataArchive>

    @Query("Select * from dataarchive order by :sortField asc")
    fun getAllAscending(sortField: String) : List<DataArchive>

    @Query("Select * from dataarchive where id like :id limit 1")
    fun getArchive(id: String) : DataArchive?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(vararg archives: DataArchive)

    @Delete
    fun removeArchive(archive: DataArchive)

    @Update
    fun updateArchive(archive: DataArchive)
}

class DatabaseTypeConverters {
    @TypeConverter
    fun fromMap(value: Map<String, List<String>>) : String {
        val jsonObject = JSONObject()
        for (pair in value)
            jsonObject.put(pair.key, JSONArray(pair.value))
        return jsonObject.toString()
    }

    @TypeConverter
    fun fromString(json: String) : Map<String, List<String>> {
        val jsonObject = JSONObject(json)
        val map = mutableMapOf<String, List<String>>()
        for (key in jsonObject.keys()) {
            val tagsArray = jsonObject.getJSONArray(key)
            val tags = mutableListOf<String>()
            for (i in 0 until tagsArray.length())
                tags.add(tagsArray.getString(i))
            map[key] = tags
        }

        return map
    }
}

@Database(entities = [DataArchive::class], version = 1)
@TypeConverters(DatabaseTypeConverters::class)
abstract class ArchiveDatabase : RoomDatabase() {
    abstract fun archiveDao(): ArchiveDao

    fun getAll(method: SortMethod, descending: Boolean) : List<ArchiveBase> {
        val sortField = when (method) {
            SortMethod.Date -> "dateAdded"
            SortMethod.Alpha -> "title"
        }

        return if (descending) archiveDao().getAllDescending(sortField) else archiveDao().getAllAscending(sortField)
    }

    fun insertOrUpdate(archives: List<Archive>) {
        for (archive in archives) {
            val dataArchive = archiveDao().getArchive(archive.id)
            if (dataArchive == null)
                convertFromArchive(archive)
            else {
                val converted = DataArchive(archive.id, archive.title, archive.dateAdded, archive.isNew, archive.tags)
                archiveDao().updateArchive(converted)
            }
        }

        val toRemove = archiveDao().getAll().filter { !archives.any { a -> a.id == it.id } }
        for (archive in toRemove)
            archiveDao().removeArchive(archive)
    }

    private fun convertFromArchive(archive: Archive) {
        val converted = DataArchive(archive.id, archive.title, archive.dateAdded, archive.isNew, archive.tags)
        archiveDao().insertAll(converted)
    }
}