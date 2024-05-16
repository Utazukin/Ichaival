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

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3Api::class)

package com.utazukin.ichaival

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.ui.theme.IchaivalTheme
import com.utazukin.ichaival.ui.theme.ThemeButton
import com.utazukin.ichaival.ui.theme.ThemeText
import kotlinx.coroutines.launch

data class DownloadedArchive(val archive: Archive, val thumb: String?, val count: Int, val cancelled: Boolean = false)

class DownloadsActivity : ComponentActivity(), DownloadListener {
    private val downloadedArchives = mutableStateListOf<DownloadedArchive>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                for (i in downloadedArchives.indices.reversed()) {
                    if (!DownloadManager.isDownloaded(downloadedArchives[i].archive.id))
                        downloadedArchives.removeAt(i)
                }
            }
        }

        lifecycleScope.launch {
            val downloadedArchiveIds = DownloadManager.getDownloadedArchives()
            with(downloadedArchives) {
                for (id in downloadedArchiveIds) {
                    val archive = DatabaseReader.getArchive(id)
                    if (archive != null && !any { it.archive.id == id }) {
                        val downloadCount = DownloadManager.getDownloadedPageCount(id)
                        add(DownloadedArchive(archive, DatabaseReader.getArchiveImage(archive, this@DownloadsActivity)?.path, downloadCount))
                    }
                }
            }
            setContent {
                val theme = getCustomTheme()
                val color = if (theme == getString(R.string.dark_theme)) Color.DarkGray else Color.Black
                IchaivalTheme(theme = theme) {
                    Surface(modifier = Modifier.fillMaxSize(), color = color) {
                        Scaffold(topBar = { AppBar(this@DownloadsActivity) }) {
                            DownloadList(archives = downloadedArchives, Modifier.padding(it))
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        DownloadManager.removeListener(this)
    }

    override fun onResume() {
        super.onResume()
        DownloadManager.addListener(this)
    }

    override fun onImageDownloaded(id: String, pagesDownloaded: Int) {
        for (i in downloadedArchives.indices) {
            if (downloadedArchives[i].archive.id == id) {
                downloadedArchives[i] = downloadedArchives[i].copy(count = pagesDownloaded)
                return
            }
        }

        lifecycleScope.launch {
            DatabaseReader.getArchive(id)?.also {
                val thumb = DatabaseReader.getArchiveImage(it, this@DownloadsActivity)?.path
                if (!downloadedArchives.any { d -> d.archive.id == id })
                    downloadedArchives.add(DownloadedArchive(it, thumb, pagesDownloaded))
            }
        }
    }

    override fun onDownloadRemoved(id: String) {
        val downloadIndex = downloadedArchives.indexOfFirst { it.archive.id == id }
        if (downloadIndex >= 0)
            downloadedArchives.removeAt(downloadIndex)
    }

    override fun onDownloadCanceled(id: String) {
        val downloadIndex = downloadedArchives.indexOfFirst { it.archive.id == id }
        if (downloadIndex >= 0) {
            val download = downloadedArchives[downloadIndex]
            downloadedArchives[downloadIndex] = download.copy(cancelled = true)
        }
    }
}

@Composable
fun AppBar(activity: DownloadsActivity) {
    val colors = when(activity.getCustomTheme()) {
        activity.getString(R.string.black_theme) -> TopAppBarDefaults.topAppBarColors().copy(containerColor = Color.Black)
        activity.getString(R.string.dark_theme) -> TopAppBarDefaults.topAppBarColors().copy(containerColor = Color(0xFF212121))
        else -> TopAppBarDefaults.topAppBarColors()
    }
    TopAppBar(title = { Text("Downloads") }, navigationIcon = {
        IconButton(onClick = { activity.finish() }) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        }
    }, colors = colors, modifier = Modifier.shadow(elevation = 5.dp))
}

@Composable
fun DownloadList(archives: List<DownloadedArchive>, modifier: Modifier) {
    val stateList = remember { archives }
    LazyColumn(modifier) {
        itemsIndexed(stateList) {i, item ->
            DownloadItem(download = item)
            if (i < stateList.lastIndex)
                HorizontalDivider()
        }
    }
}

@Composable
fun DownloadItem(download: DownloadedArchive) {
    val context = LocalContext.current
    val itemSize = 150
    Row(modifier =
    Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.background)
        .padding(vertical = 8.dp)
        .clickable { startDetailsActivity(download.archive.id, context) }) {
        val model = ImageRequest.Builder(LocalContext.current).data(download.thumb).size(getDpAdjusted(itemSize)).build()
        AsyncImage(model = model, contentDescription = null, modifier = Modifier
            .padding(end = 5.dp)
            .width(itemSize.dp))
        Text(text = download.archive.title, modifier = Modifier
            .height(itemSize.dp)
            .wrapContentHeight())
        Spacer(modifier = Modifier.weight(1f))
        val text = if (download.count > 0) "${download.count}/${download.archive.numPages}" else context.getString(R.string.archive_extract_message)
        Text(text = text, modifier = Modifier
            .height(itemSize.dp)
            .wrapContentHeight()
            .padding(end = 8.dp))
        ThemeButton(onClick = {
            if (DownloadManager.isDownloading(download.archive.id))
                DownloadManager.cancelDownload(download.archive.id)
            else
                DownloadManager.deleteArchive(download.archive.id)
        }, modifier = Modifier
            .height(itemSize.dp)
            .padding(end = 8.dp)
            .wrapContentHeight(), content = { ThemeText(text = if (DownloadManager.isDownloading(download.archive.id)) "Cancel" else "Delete") })
    }
}

private fun startDetailsActivity(id: String, context: Context) {
    val intent = Intent(context, ArchiveDetails::class.java).also {
        it.putExtras(Bundle().apply { putString("id", id) })
    }
    context.startActivity(intent)
}
