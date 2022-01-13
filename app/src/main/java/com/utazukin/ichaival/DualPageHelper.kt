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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.Path
import kotlin.io.path.moveTo

object DualPageHelper {
    private const val mergedPagePath = "merged_pages"
    private const val mergedPageTrashPath = "merged_page_trash"
    private const val maxCacheSize = 250 * 1024 * 1024
    private val moveMutex by lazy { Mutex() }
    private val trashMutex by lazy { Mutex() }

    suspend fun getMergedPage(cacheDir: File, archiveId: String, page: Int, otherPage: Int, rtol: Boolean, compressType: PageCompressFormat) : String? {
        val mergedDir = File(cacheDir, mergedPagePath)
        if (!mergedDir.exists()) {
            mergedDir.mkdir()
            return null
        }

        val archiveDir = File(mergedDir, archiveId)
        val filename = getFilename(page, otherPage, rtol, compressType)
        val file = File(archiveDir, filename)
        moveMutex.withLock {
            if (!file.exists()) {
                val trashDir = File(cacheDir, mergedPageTrashPath)
                if (!trashDir.exists())
                    return null
                val trashArcDir = File(trashDir, archiveId)
                if (!trashArcDir.exists())
                    return null

                val trashFile = File(trashArcDir, filename)
                if (!trashFile.exists())
                    return null
                withContext(Dispatchers.IO) { moveDir(trashArcDir, archiveDir) }
            }
        }

        return file.path
    }

    private fun getFilename(page: Int, otherPage: Int, rtol: Boolean, compressType: PageCompressFormat) : String {
        return when {
            compressType == PageCompressFormat.JPEG && rtol -> "$page-$otherPage.jpg"
            compressType == PageCompressFormat.PNG && rtol -> "$page-$otherPage.png"
            compressType == PageCompressFormat.JPEG -> "$otherPage-$page.jpg"
            compressType == PageCompressFormat.PNG -> "$otherPage-$page.png"
            else -> "$page-$otherPage.jpg"
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private fun moveDir(from: File, to: File) {
        if (!from.exists())
            return

        if (!to.exists())
            to.mkdirs()

        if (from.isDirectory) {
            from.listFiles()?.forEach { moveDir(it, to) }
            from.deleteRecursively()
        } else {
            val toFile = File(to, from.name)
            Path(from.path).moveTo(Path(toFile.path))
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun saveMergedPath(cacheDir: File, image: Bitmap, archiveId: String, page: Int, otherPage: Int, rtol: Boolean, compressType: PageCompressFormat) : String {
        val filename = getFilename(page, otherPage, rtol, compressType)
        val mergedDir = File(cacheDir, mergedPagePath)
        if (!mergedDir.exists())
            mergedDir.mkdir()

        val trashDir = File(cacheDir, mergedPageTrashPath)
        if (trashDir.exists()) {
            trashMutex.withLock {
                var cacheSize = mergedDir.listFiles()?.filterNotNull()?.sumOf { calculateCacheSize(it) } ?: 0
                val trashFiles = trashDir.listFiles()
                trashFiles?.let {
                    cacheSize += it.sumOf { f -> calculateCacheSize(f) }

                    if (cacheSize >= maxCacheSize) {
                        for (f in it)
                            f.deleteRecursively()
                    }
                }
            }
        }

        val archiveDir = File(mergedDir, archiveId)
        if (!archiveDir.exists())
            archiveDir.mkdir()

        val file = File(archiveDir, filename)
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

        val archiveDir = File(mergedDir, archiveId)
        if (!archiveDir.exists())
            return@withContext

        val trashArchiveDir = File(trashDir, archiveId)
        moveDir(archiveDir, trashArchiveDir)
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
            moveDir(it, file)
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
                size += calculateCacheSize(trash)

            if (merged.exists())
                size += calculateCacheSize(merged)
            size
        }
    }

    private fun calculateCacheSize(file: File?) : Long {
        return when {
            file == null -> 0
            !file.isDirectory -> file.length()
            else -> file.listFiles()?.sumOf { calculateCacheSize(it) } ?: 0
        }
    }
}