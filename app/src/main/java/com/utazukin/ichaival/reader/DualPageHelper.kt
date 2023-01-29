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

package com.utazukin.ichaival.reader

import android.graphics.*
import android.os.Build
import android.util.Size
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.utazukin.ichaival.reader.PageCompressFormat.Companion.toBitmapFormat
import com.utazukin.ichaival.toRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileOutputStream
import kotlin.io.path.moveTo
import kotlin.math.max

data class MergeInfo(val imgSize: Size,
                     val otherImgSize: Size,
                     val imgFile: File,
                     val otherImgFile: File,
                     val page: Int,
                     val otherPage: Int,
                     val compressType: PageCompressFormat,
                     val archiveId: String,
                     val isLTR: Boolean)

object DualPageHelper {
    private const val mergedPagePath = "merged_pages"
    private const val mergedPageTrashPath = "merged_page_trash"
    private const val maxCacheSize = 250 * 1024 * 1024
    private val moveMutex by lazy { Mutex() }
    private val trashMutex by lazy { Mutex() }
    private val mergeSemaphore by lazy { Semaphore(3) }

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

    suspend fun mergeBitmaps(mergeInfo: MergeInfo, cacheDir: File, pool: BitmapPool, updateProgress: (Int) -> Unit) : String {
        return mergeSemaphore.withPermit { mergeBitmapsInternal(mergeInfo, cacheDir, pool, updateProgress) }
    }

    //Mostly from TachiyomiJ2K
    private suspend fun mergeBitmapsInternal(mergeInfo: MergeInfo, cacheDir: File, pool: BitmapPool, updateProgress: (Int) -> Unit): String {
        val (imgSize, otherImgSize, imgFile, otherImgFile, page, otherPage, compressType, archiveId, isLTR) = mergeInfo
        val height = imgSize.height
        val width = imgSize.width

        val height2 = otherImgSize.height
        val width2 = otherImgSize.width
        val maxHeight = max(height, height2)
        val result = pool.get(width + width2, maxHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        val upperPart = Rect(
            if (isLTR) 0 else width2,
            0,
            (if (isLTR) 0 else width2) + width,
            height + (maxHeight - height) / 2
        )

        try {
            var bitmap = decodeBitmap(imgFile, imgSize)
            canvas.drawBitmap(bitmap, imgSize.toRect(), upperPart, null)
            pool.put(bitmap)
            yield()
            withContext(Dispatchers.Main) { updateProgress(95) }
            val bottomPart = Rect(
                if (!isLTR) 0 else width,
                0,
                (if (!isLTR) 0 else width) + width2,
                height2 + (maxHeight - height2) / 2
            )
            bitmap = decodeBitmap(otherImgFile, otherImgSize)
            canvas.drawBitmap(bitmap, otherImgSize.toRect(), bottomPart, null)
            pool.put(bitmap)
            yield()
            withContext(Dispatchers.Main) { updateProgress(99) }

            return saveMergedPath(cacheDir, result, archiveId, page, otherPage, !isLTR, compressType)
        } finally {
            pool.put(result)
        }
    }

    private fun decodeBitmap(file: File, size: Size) : Bitmap {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = android.graphics.ImageDecoder.createSource(file)
            android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = true
                if (info.size.height > size.height || info.size.width > size.width)
                    decoder.setTargetSize(size.width, size.height)
            }
        } else {
            var bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap.height > size.height || bitmap.width > size.width)
                bitmap = Bitmap.createScaledBitmap(bitmap, size.width, size.height, true).also { bitmap.recycle() }
            bitmap
        }
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
            moveFile(from, toFile)
        }
    }

    private fun moveFile(from: File, to: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            from.toPath().moveTo(to.toPath(), true)
        else {
            from.copyTo(to, true)
            from.delete()
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun saveMergedPath(cacheDir: File, image: Bitmap, archiveId: String, page: Int, otherPage: Int, rtol: Boolean, compressType: PageCompressFormat) : String {
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