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
import android.graphics.Color
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import coil.load
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.utazukin.ichaival.database.DatabaseReader
import kotlinx.coroutines.launch
import java.text.DateFormat

private const val ARCHIVE_ID = "arcid"

class ArchiveDetailsFragment : Fragment(), TabRemovedListener, TabsClearedListener, TabAddedListener, AddCategoryListener, MenuProvider, ImageDownloadListener {
    private var archiveId = ""
    private lateinit var catFlexLayout: FlexboxLayout
    private lateinit var bookmarkButton: Button
    private lateinit var thumbView: ImageView
    private lateinit var downloadButton: Button
    private var archive: Archive? = null
    private var tagListener: TagInteractionListener? = null
    private val isLocalSearch by lazy {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.getBoolean(getString(R.string.local_search_key), false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archiveId = arguments?.getString(ARCHIVE_ID) ?: ""
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_archive_details, container, false)
        with(view) {
            catFlexLayout = findViewById(R.id.cat_flex)
            thumbView = findViewById(R.id.cover)
            downloadButton = findViewById(R.id.download_button)
        }
        ViewCompat.setTransitionName(thumbView, COVER_TRANSITION)

        with(requireActivity() as MenuHost) {
            addMenuProvider(this@ArchiveDetailsFragment, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }

        lifecycleScope.launch { setUpDetailView(view) }

        val addToCatButton: ImageButton = view.findViewById(R.id.add_to_cat_button)
        addToCatButton.isVisible = ServerManager.canEdit
        addToCatButton.setOnClickListener {
            val dialog = AddToCategoryDialogFragment.newInstance(listOf(archiveId))
            dialog.show(childFragmentManager, "add_category")
        }

        return view
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}
    override fun onMenuItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.refresh_thumb_item -> {
                if (archiveId.isNotEmpty()) {
                    lifecycleScope.launch {
                        val thumbFile = DatabaseReader.refreshThumbnail(archiveId, requireContext())
                        thumbFile?.let { file ->
                            thumbView.load(file) {
                                allowRgb565(true)
                                allowHardware(false)
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        ReaderTabHolder.registerRemoveListener(this)
        ReaderTabHolder.registerClearListener(this)
        ReaderTabHolder.registerAddListener(this)
        DownloadManager.addListener(this)
        tagListener = context as? TagInteractionListener
    }

    override fun onDetach() {
        super.onDetach()
        ReaderTabHolder.unregisterRemoveListener(this)
        ReaderTabHolder.unregisterClearListener(this)
        ReaderTabHolder.unregisterAddListener(this)
        DownloadManager.removeListener(this)
    }

    override fun onTabAdded(id: String) {
        if (id == archiveId)
            lifecycleScope.launch { bookmarkButton.text = getString(R.string.unbookmark) }
    }

    override fun onTabsAdded(ids: List<String>) {
        if (archiveId in ids)
            lifecycleScope.launch { bookmarkButton.text = getString(R.string.unbookmark) }
    }

    override fun onTabRemoved(id: String) {
        if (id == archiveId) {
            lifecycleScope.launch { bookmarkButton.text = getString(R.string.bookmark) }
        }
    }

    override fun onTabsCleared() {
        bookmarkButton.text = getString(R.string.bookmark)
    }

    private fun getSearchTag(tag: String, namespace: String) : String {
        return when {
            namespace == getString(R.string.other_namespace) -> "\"$tag\""
            isLocalSearch -> "$namespace:\"$tag\""
            else -> "\"$namespace:$tag\"$"
        }
    }

    private suspend fun setupCategories(view: View, archive: Archive) {
        val catLayout: LinearLayout = view.findViewById(R.id.cat_layout)
        val categories = CategoryManager.getStaticCategories(archive.id)
        if (categories.isNotEmpty() || ServerManager.canEdit) {
            catLayout.visibility = View.VISIBLE
            for (category in categories) {
                val catView = createCatView(category, archive.id)
                catFlexLayout.addView(catView)
            }
        } else catLayout.visibility = View.GONE
    }

    private fun setUpTags(view: View, archive: Archive) {
        val tagLayout: LinearLayout = view.findViewById(R.id.tag_layout)
        if (archive.dateAdded > 0) {
            val namespaceLayout = FlexboxLayout(context).apply {
                flexWrap = FlexWrap.WRAP
                flexDirection = FlexDirection.ROW
            }

            tagLayout.addView(namespaceLayout, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val namespaceView = createTagView(getString(R.string.date_added_tag), true)
            namespaceLayout.addView(namespaceView)
            namespaceLayout.addView(createTagView(DateFormat.getDateInstance(DateFormat.SHORT).format(archive.dateAdded * 1000)))
        }
        for ((namespace, tags) in archive.tags) {
            if (tags.isEmpty())
                continue

            val namespace = if (namespace == "global") getString(R.string.other_namespace) else namespace
            val namespaceLayout = FlexboxLayout(context).apply {
                flexWrap = FlexWrap.WRAP
                flexDirection = FlexDirection.ROW
            }
            tagLayout.addView(namespaceLayout, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val namespaceView = createTagView(namespace, true)
            namespaceLayout.addView(namespaceView)

            val isSource = namespace == "source"
            for (tag in tags) {
                val tagView = createTagView(tag)
                namespaceLayout.addView(tagView)

                if (!isSource) {
                    val searchTag = getSearchTag(tag, namespace)
                    tagView.setOnClickListener { tagListener?.onTagInteraction(searchTag) }
                } else {
                    tagView.linksClickable = true
                    Linkify.addLinks(tagView, Linkify.WEB_URLS)
                    tagView.movementMethod = LinkMovementMethod.getInstance()
                }
            }
        }
    }

    private fun createTagView(tag: String, isNamespace: Boolean = false) : TextView {
        return TextView(context).apply {
            text = tag
            background = if (!isNamespace)
                ContextCompat.getDrawable(requireContext(), R.drawable.tag_background)
            else
                ContextCompat.getDrawable(requireContext(), R.drawable.namespace_background)
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(10, 10, 10, 10)
            layoutParams = params
        }
    }

    private fun createCatView(category: ArchiveCategory, archiveId: String): Chip {
        val catView = Chip(context).apply {
            text = category.name
            textSize = 16f
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams = params
            isCloseIconVisible = ServerManager.canEdit
        }

        if (!ServerManager.canEdit)
            return catView

        catView.setOnCloseIconClickListener {
            val builder = MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(getString(R.string.category_remove_title))
                setMessage(getString(R.string.category_remove_message, category.name))
                setPositiveButton(R.string.yes) { dialog, _ ->
                    dialog.dismiss()
                    lifecycleScope.launch {
                        val success = WebHandler.removeFromCategory(requireContext(), category.id, archiveId)
                        if (success) {
                            catFlexLayout.removeView(it)
                            DatabaseReader.removeFromCategory(category.id, archiveId)
                            Snackbar.make(requireView(), getString(R.string.category_removed_message, category.name), Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }

                setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
            }
            val dialog = builder.create()
            dialog.show()
        }

        return catView
    }

    private suspend fun setUpDetailView(view: View) {
        bookmarkButton = view.findViewById(R.id.bookmark_button)
        with(bookmarkButton) {
            setOnClickListener {
                lifecycleScope.launch {
                    if (archiveId.isNotEmpty()) {
                        text = if (ReaderTabHolder.isTabbed(archiveId)) {
                            ReaderTabHolder.removeTab(archiveId)
                            ReaderTabHolder.resetServerProgress(archiveId)
                            getString(R.string.bookmark)
                        } else {
                            ReaderTabHolder.addTab(archiveId, 0)
                            getString(R.string.unbookmark)
                        }
                    }
                }
            }
            text = getString(if (ReaderTabHolder.isTabbed(archiveId)) R.string.unbookmark else R.string.bookmark)
        }

        val readButton: Button = view.findViewById(R.id.read_button)
        readButton.setOnClickListener { (activity as ArchiveDetails).startReaderActivityForResult() }

        val archive = DatabaseReader.getArchive(archiveId)?.also { this.archive = it } ?: return
        setUpTags(view, archive)
        setupCategories(view, archive)

        val titleView: TextView = view.findViewById(R.id.title)
        titleView.text = archive.title

        val downloadedCount = DownloadManager.getDownloadedPageCount(archiveId)
        if (downloadedCount == archive.numPages)
            downloadButton.text = resources.getString(R.string.download_button_downloaded)
        else if (DownloadManager.isDownloading(archiveId))
            downloadButton.text = if (downloadedCount > 0) resources.getString(R.string.download_button_downloading, downloadedCount, archive.numPages) else resources.getString(R.string.archive_extract_message)
        else if (downloadedCount > 0)
            downloadButton.text = getString(R.string.download_button_download_progress, downloadedCount, archive.numPages)

        downloadButton.setOnClickListener {
            if (!DownloadManager.isDownloading(archiveId)) {
                val pageCount = DownloadManager.getDownloadedPageCount(archiveId)
                if (pageCount == archive.numPages) {
                    val builder = MaterialAlertDialogBuilder(requireContext()).apply {
                        setTitle(R.string.delete_archive_item)
                        setMessage("Delete downloaded files?")
                        setPositiveButton(R.string.yes) { dialog, _ ->
                            dialog.dismiss()
                            DownloadManager.deleteArchive(archiveId)
                            downloadButton.text = resources.getString(R.string.download_button)
                        }
                        setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
                    }
                    builder.show()
                } else if (pageCount > 0) {
                    val builder = MaterialAlertDialogBuilder(requireContext()).apply {
                        setTitle("Download")
                        setMessage("Resume download?")
                        setPositiveButton(R.string.yes) { dialog, _ ->
                            dialog.dismiss()
                            DownloadManager.resumeDownload(archiveId, downloadedCount)
                            downloadButton.text = resources.getString(R.string.download_button)
                        }
                        setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
                    }
                    builder.show()
                } else DownloadManager.download(archiveId)
            }
            else {
                val builder = MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle("Cancel Download")
                    setMessage("Cancel Download?")
                    setPositiveButton(R.string.yes) { dialog, _ ->
                        dialog.dismiss()
                        DownloadManager.cancelDownload(archiveId)
                        downloadButton.text = resources.getString(R.string.download_button)
                    }
                    setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
                }
                builder.show()
            }
        }

        downloadButton.setOnLongClickListener {
            val pageCount = DownloadManager.getDownloadedPageCount(archiveId)
            if (!DownloadManager.isDownloading(archiveId) && pageCount != archive.numPages) {
                val builder = MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.delete_archive_item)
                    setMessage("Delete downloaded files?")
                    setPositiveButton(R.string.yes) { dialog, _ ->
                        dialog.dismiss()
                        DownloadManager.deleteArchive(archiveId)
                        downloadButton.text = resources.getString(R.string.download_button)
                    }
                    setNegativeButton(R.string.no) { dialog, _ -> dialog.dismiss() }
                }
                builder.show()
                true
            } else false
        }

        val thumbFile = DatabaseReader.getArchiveImage(archive, requireContext())
        thumbFile?.let {
            thumbView.load(it) {
                allowRgb565(true)
                allowHardware(false)
                listener(
                        onSuccess = { _, _ -> requireActivity().supportStartPostponedEnterTransition() },
                        onError = { _, _ -> requireActivity().supportStartPostponedEnterTransition() },
                        onCancel = { requireActivity().supportStartPostponedEnterTransition() }
                )
            }
        }
    }

    override fun onAddedToCategory(category: ArchiveCategory, archiveIds: List<String>) {
        val id = archiveIds.firstOrNull()
        if (id != archiveId || catFlexLayout.children.mapNotNull { it as? TextView }.any { it.text == category.name })
            return

        val catView = createCatView(category, id)
        catFlexLayout.addView(catView)

        Snackbar.make(requireView(), getString(R.string.category_add_message, category.name), Snackbar.LENGTH_SHORT).show()
    }

    interface TagInteractionListener {
        fun onTagInteraction(tag: String)
    }

    companion object {
        @JvmStatic
        fun createInstance(id: String) =
            ArchiveDetailsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARCHIVE_ID, id)
                }
            }
    }

    override fun onImageDownloaded(id: String, pagesDownloaded: Int) {
        if (id == archiveId) {
            archive?.let {
                downloadButton.text = when {
                    pagesDownloaded == 0 -> resources.getString(R.string.archive_extract_message)
                    pagesDownloaded < it.numPages -> resources.getString(R.string.download_button_downloading, pagesDownloaded, it.numPages)
                    else -> resources.getString(R.string.download_button_downloaded)
                }
            }
        }
    }
}
