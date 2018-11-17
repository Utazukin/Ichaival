package com.example.shaku.ichaival

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.support.annotation.UiThread
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class Archive(json: JSONObject) {
    val title: String = json.getString("title")
    val id: String = json.getString("arcid")
    val tags: Map<String, List<String>>
    private val imageUrls = mutableListOf<String>()
    private lateinit var imageLocations: Array<String>

    init {
        val tagString: String = json.getString("tags")
        val tagList: List<String> = tagString.split(",")
        val mutableTags = mutableMapOf<String, MutableList<String>>()
        for (tag: String in tagList) {
            val trimmed = tag.trim()
            if (trimmed.contains(":")) {
                val split = trimmed.split(":")
                if (!mutableTags.containsKey(split[0]))
                    mutableTags[split[0]] = mutableListOf()
                mutableTags[split[0]]?.add(split[1])
            }
            else {
                if (!mutableTags.containsKey("global"))
                    mutableTags["global"] = mutableListOf()
                mutableTags["global"]?.add(trimmed)
            }
        }
        tags = mutableTags
    }

    private fun getImageUrls() : Boolean {
        if (imageUrls.size > 0)
            return true

        val jsonPages = DatabaseReader.extractArchive(id)?.getJSONArray("pages") ?: return false

        val count = jsonPages.length()
        imageLocations = Array(count) { "$$" }
        for (i in 0..(count - 1)) {
            var path = jsonPages.getString(i)
            path = path.substring(1)
            imageUrls.add(path)
        }
        return true
    }

    private fun extractArchive(parentDir: File) : Boolean {
        val gotImages = getImageUrls()
        if (parentDir.exists() && parentDir.listFiles().size == imageUrls.size) {
            val files = parentDir.listFiles()
            imageLocations = Array(files.size) { "" }
            for (i in 0..(files.size - 1))
                imageLocations[i] = files[i].path
            return true
        }

        return gotImages
    }

    fun hasPage(page: Int) : Boolean {
        return page >= 0 && page < imageUrls.size
    }

    private suspend fun getPage(page: Int, parentDir: File, preload: Boolean) : File? {
        val archiveCache = File(parentDir, "temp/$id")
        if (!archiveCache.exists())
            archiveCache.mkdirs()

        if (!extractArchive(archiveCache))
            return null

        var imageFile = File(parentDir, imageLocations[page])
        if (!imageFile.exists()) {

            val bytes = GlobalScope.async { downloadPage(page) }.await()
            if (bytes != null) {
                imageFile = File(archiveCache, imageUrls[page].substring(imageUrls[page].lastIndexOf("/") + 1))
                imageFile.createNewFile()
                imageFile.writeBytes(bytes)
                imageLocations[page] = imageFile.path
            }
        }

        if (preload) {
            GlobalScope.launch {
                if (page < imageUrls.size - 1) {
                    for (i in (page + 1)..(page + 4)) {
                        if (i >= imageUrls.size)
                            break
                        getPage(i, parentDir, false)
                    }
                }
            }
        }

        return imageFile
    }

    @UiThread
    suspend fun getPageImage(page: Int, parentDir: File) : Bitmap? {
        val imageFile = getPage(page, parentDir, true)

        if (imageFile != null)
            return BitmapFactory.decodeFile(imageFile.path)
        return null
    }

    private fun downloadPage(page: Int) : ByteArray? {
        getImageUrls()
        return DatabaseReader.downloadPage(imageUrls[page])
    }

    fun containsTag(tag: String) : Boolean {
        if (tag.contains(":")) {
            val split = tag.split(":")
            val namespace = split[0].trim()
            val tag = split[1].trim().replace("_", " ")
            val nTags = getTags(namespace)
            return nTags != null && nTags.contains(tag)
        }
        else {
            val normalized = tag.trim().replace("_", " ")
            for (pair in tags) {
                if (pair.value.contains(normalized))
                    return true
            }
        }
        return false
    }

    private fun getTags(namespace: String) : List<String>? {
        return if (tags.containsKey(namespace)) tags[namespace] else null
    }
}