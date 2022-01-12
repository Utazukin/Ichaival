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

import android.graphics.Bitmap
import com.utazukin.ichaival.PageCompressFormat.Companion.toBitmapFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.Path
import kotlin.io.path.moveTo

object DualPageHelper {
    private const val mergedPagePath = "merged_pages"
    private const val mergedPageTrashPath = "merged_page_trash"
    private const val maxCacheSize = 250 * 1024 * 1024

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun getMergedPage(cacheDir: File, archiveId: String, page: Int, otherPage: Int, compressType: PageCompressFormat) : String? {
        val mergedDir = File(cacheDir, mergedPagePath)
        if (!mergedDir.exists()) {
            mergedDir.mkdir()
            return null
        }
        val filename = if (compressType == PageCompressFormat.PNG) "$archiveId-$page-$otherPage.png" else "$archiveId-$page-$otherPage.jpg"
        val file = File(mergedDir, filename)
        if (!file.exists()) {
            val trashDir = File(cacheDir, mergedPageTrashPath)
            if (!trashDir.exists())
                return null
            val trashFile = File(trashDir, filename)
            if (!trashFile.exists())
                return null
            val t = Path(trashFile.path)
            withContext(Dispatchers.IO) { t.moveTo(Path(file.path)) }
        }

        return file.path
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun saveMergedPath(cacheDir: File, image: Bitmap, archiveId: String, page: Int, otherPage: Int, compressType: PageCompressFormat) : String {
        val filename = if (compressType == PageCompressFormat.PNG) "$archiveId-$page-$otherPage.png" else "$archiveId-$page-$otherPage.jpg"
        val mergedDir = File(cacheDir, mergedPagePath)
        if (!mergedDir.exists())
            mergedDir.mkdir()

        var cacheSize = mergedDir.listFiles()?.filterNotNull()?.sumOf { it.length() } ?: 0

        val trashDir = File(cacheDir, mergedPageTrashPath)
        if (trashDir.exists()) {
            val trashFiles = trashDir.listFiles()
            trashFiles?.let {
                cacheSize += it.sumOf { f -> f.length() }

                if (cacheSize >= maxCacheSize) {
                    for (f in it)
                        f.deleteRecursively()
                }
            }
        }

        val file = File(mergedDir, filename)
        withContext(Dispatchers.IO) {
            FileOutputStream(file.path).use {
                image.compress(compressType.toBitmapFormat(), 100, it)
            }
        }

        return file.path
    }

    suspend fun trashMergedPages(cacheDir: File, archiveId: String) = withContext(Dispatchers.IO) {
        val trashDir = File(cacheDir, mergedPageTrashPath)
        if (!trashDir.exists())
            trashDir.mkdir()

        val mergedDir = File(cacheDir, mergedPagePath)
        if (!mergedDir.exists())
            return@withContext

        mergedDir.listFiles()?.forEach {
            if (it.name.contains(archiveId)) {
                val file = File(trashDir, it.name)
                val path = Path(it.path)
                path.moveTo(Path(file.path))
            }
        }
    }
    suspend fun trashMergedPages(cacheDir: File) = withContext(Dispatchers.IO) {
        val trashDir = File(cacheDir, mergedPageTrashPath)
        if (!trashDir.exists())
            trashDir.mkdir()

        val mergedDir = File(cacheDir, mergedPagePath)
        if (!mergedDir.exists())
            return@withContext

        mergedDir.listFiles()?.forEach {
            val file = File(trashDir, it.name)
            val path = Path(it.path)
            path.moveTo(Path(file.path))
        }
    }

    suspend fun clearMergedPages(cacheDir: File) = withContext(Dispatchers.IO) {
        val trashDir = File(cacheDir, mergedPageTrashPath)
        val mergedDir = File(cacheDir, mergedPagePath)

        if (trashDir.exists())
            trashDir.deleteRecursively()
        if (mergedDir.exists())
            mergedDir.deleteRecursively()
    }

    suspend fun getCacheSize(cacheDir: File) : Long {
        return withContext(Dispatchers.IO) {
            val trash = File(cacheDir, mergedPageTrashPath)
            val merged = File(cacheDir, mergedPagePath)
            var size = 0L
            if (trash.exists())
                size += trash.listFiles()?.sumOf { it.length() } ?: 0

            if (merged.exists())
                size += merged.listFiles()?.sumOf { it.length() } ?: 0
            size
        }
    }
}