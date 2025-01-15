/*
 * Ichaival - Android client for LANraragi https://github.com/Utazukin/Ichaival/
 * Copyright (C) 2025 Utazukin
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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.utazukin.ichaival.database.DatabaseReader
import com.utazukin.ichaival.ui.theme.IchaivalTheme
import com.utazukin.ichaival.ui.theme.ThemeAlertDialog
import com.utazukin.ichaival.ui.theme.ThemeButton
import com.utazukin.ichaival.ui.theme.ThemeText
import com.utazukin.ichaival.ui.theme.ThemeTextButton
import kotlinx.coroutines.launch

data class DownloadedArchive(val archive: Archive?, val id: String, val thumb: String?, val count: Int, val cancelled: Boolean = false) {
    val complete = count == archive?.numPages
}

data class ButtonOption(val download: DownloadedArchive, val title: String, val message: String, val onConfirm: (DownloadedArchive, MutableState<ButtonOption?>) -> Unit)

class DownloadsActivity : ComponentActivity(), DownloadListener {
    private val downloadedArchives = mutableStateListOf<DownloadedArchive>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                for (i in downloadedArchives.indices.reversed()) {
                    if (!DownloadManager.isDownloaded(downloadedArchives[i].id))
                        downloadedArchives.removeAt(i)
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
            if (downloadedArchives[i].id == id) {
                downloadedArchives[i] = downloadedArchives[i].copy(count = pagesDownloaded)
                return
            }
        }

        lifecycleScope.launch {
            DatabaseReader.getArchive(id)?.also {
                val thumb = DatabaseReader.getArchiveImage(it, this@DownloadsActivity)?.path
                if (!downloadedArchives.any { d -> d.id == id })
                    downloadedArchives.add(DownloadedArchive(it, it.id, thumb, pagesDownloaded))
            }
        }
    }

    override fun onDownloadRemoved(id: String) {
        val downloadIndex = downloadedArchives.indexOfFirst { it.id == id }
        if (downloadIndex >= 0)
            downloadedArchives.removeAt(downloadIndex)
    }

    override fun onDownloadCanceled(id: String) {
        val downloadIndex = downloadedArchives.indexOfFirst { it.id == id }
        if (downloadIndex >= 0) {
            val download = downloadedArchives[downloadIndex]
            downloadedArchives[downloadIndex] = download.copy(cancelled = true)
        }
    }

    override fun onDownloadsAdded(downloads: List<Pair<String, Int>>) {
        lifecycleScope.launch {
            for ((id, pagesDownloaded) in downloads) {
                val archive = DatabaseReader.getArchive(id)
                if (archive != null) {
                    val thumb = DatabaseReader.getArchiveImage(archive, this@DownloadsActivity)?.path
                    if (!downloadedArchives.any { d -> d.id == id })
                        downloadedArchives.add(DownloadedArchive(archive, id, thumb, pagesDownloaded))
                } else if (!downloadedArchives.any { d -> d.id ==id })
                    downloadedArchives.add(DownloadedArchive(null, id, null, pagesDownloaded))
            }
        }
    }
}

@Composable
private fun AppBar(activity: DownloadsActivity) {
    val colors = when(activity.getCustomTheme()) {
        activity.getString(R.string.black_theme) -> TopAppBarDefaults.topAppBarColors().copy(containerColor = Color.Black)
        activity.getString(R.string.dark_theme) -> TopAppBarDefaults.topAppBarColors().copy(containerColor = Color(0xFF212121))
        activity.getString(R.string.white_theme) -> TopAppBarDefaults.topAppBarColors().copy(containerColor = MaterialTheme.colorScheme.primary)
        else -> TopAppBarDefaults.topAppBarColors()
    }
    TopAppBar(title = { Text("Downloads") }, navigationIcon = {
        IconButton(onClick = { activity.finish() }) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        }
    }, colors = colors, modifier = Modifier.shadow(elevation = 5.dp))
}

@Composable
private fun DownloadList(archives: List<DownloadedArchive>, modifier: Modifier) {
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
private fun DownloadItem(download: DownloadedArchive) {
    val context = LocalContext.current
    val itemSize = 150
    val openDialog = remember { mutableStateOf<ButtonOption?>(null) }

    openDialog.value?.run {
        ThemeAlertDialog(onDismissRequest = { openDialog.value = null },
                confirmButton = { ThemeTextButton(onClick = { onConfirm(download, openDialog) }) { Text(context.getString(R.string.yes)) } },
                dismissButton = { ThemeTextButton(onClick = { openDialog.value = null }) { Text(context.getString(R.string.no)) } },
                text = { Text(message) },
                title = { Text(title) })
    }

    ConstraintLayout(modifier = Modifier
        .fillMaxWidth()
        .height(itemSize.dp)
        .padding(vertical = 8.dp)
        .clickable { download.archive?.let { startDetailsActivity(it.id, context) } }) {
        val (image, title, progress, button) = createRefs()

        if (download.archive != null) {
            val model = ImageRequest.Builder(LocalContext.current).data(download.thumb).size(getDpAdjusted(itemSize)).build()
            AsyncImage(model = model, contentDescription = null, modifier =
            Modifier
                .width(itemSize.dp)
                .constrainAs(image) { start.linkTo(anchor = parent.start) }
            )
        }
        Text(text = download.archive?.title ?: "Missing", overflow = TextOverflow.Ellipsis, modifier = Modifier
            .height(itemSize.dp)
            .wrapContentHeight()
            .constrainAs(title) {
                val link = if (download.archive == null) parent.start else image.end
                start.linkTo(link, margin = 5.dp)
                width = Dimension.fillToConstraints
                end.linkTo(progress.start)
            }
        )

        if (download.archive != null) {
            val text = if (download.count > 0) "${download.count}/${download.archive.numPages}" else context.getString(R.string.archive_extract_message)
            Text(text = text, modifier = Modifier
                .height(itemSize.dp)
                .wrapContentHeight()
                .constrainAs(progress) { end.linkTo(button.start, margin = 4.dp) })
        }
        ThemeButton(onClick = { handleButtonClick(download, context, openDialog) }, modifier = Modifier
            .height(itemSize.dp)
            .wrapContentHeight()
            .constrainAs(button) { end.linkTo(parent.end, margin = 8.dp) },
                content = {
                    val text = when {
                        download.archive == null -> context.getString(R.string.delete_button)
                        DownloadManager.isDownloading(download.id) -> context.getString(android.R.string.cancel)
                        !download.complete -> context.getString(R.string.resume_button)
                        else -> context.getString(R.string.delete_button)
                    }
                    ThemeText(text = text)
                })
    }
}

private fun handleButtonClick(download: DownloadedArchive, context: Context, openDialog: MutableState<ButtonOption?>) {
    val archive = download.archive
    if (archive == null) {
        openDialog.value = ButtonOption(download, context.getString(R.string.delete_archive_item), context.getString(R.string.delete_downloads_message)) { dl, dialog ->
            dialog.value = null
            DownloadManager.deleteArchive(dl.id)
        }
    } else if (!DownloadManager.isDownloading(download.id)) {
        if (download.count == archive.numPages) {
            openDialog.value = ButtonOption(download, context.getString(R.string.delete_archive_item), context.getString(R.string.delete_downloads_message)) { dl, dialog ->
                dialog.value = null
                DownloadManager.deleteArchive(dl.id)
            }
        } else if (download.count > 0) {
            openDialog.value = ButtonOption(download, context.getString(R.string.download_button), context.getString(R.string.resume_download_message)) { dl, dialog ->
                dialog.value = null
                DownloadManager.resumeDownload(dl.id, dl.count)
            }
        }
    } else DownloadManager.cancelDownload(archive.id)
}

private fun startDetailsActivity(id: String, context: Context) {
    val intent = Intent(context, ArchiveDetails::class.java).also {
        it.putExtras(Bundle().apply { putString("id", id) })
    }
    context.startActivity(intent)
}
