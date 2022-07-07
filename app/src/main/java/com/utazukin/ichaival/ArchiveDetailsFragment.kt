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


import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

private const val ARCHIVE_ID = "arcid"

class ArchiveDetailsFragment : Fragment(), TabRemovedListener, TabsClearedListener, TabAddedListener, AddCategoryListener {
    private var archiveId: String? = null
    private lateinit var tagLayout: LinearLayout
    private lateinit var catLayout: LinearLayout
    private lateinit var catFlexLayout: FlexboxLayout
    private lateinit var addToCatButton: ImageButton
    private lateinit var bookmarkButton: Button
    private lateinit var thumbView: ImageView
    private var thumbLoadJob: Job? = null
    private var tagListener: TagInteractionListener? = null
    private val isLocalSearch by lazy {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        prefs.getBoolean(getString(R.string.local_search_key), false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            archiveId = it.getString(ARCHIVE_ID)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_archive_details, container, false)
        tagLayout = view.findViewById(R.id.tag_layout)
        catLayout = view.findViewById(R.id.cat_layout)
        catFlexLayout = view.findViewById(R.id.cat_flex)
        addToCatButton = view.findViewById(R.id.add_to_cat_button)
        thumbView = view.findViewById(R.id.cover)
        ViewCompat.setTransitionName(thumbView, COVER_TRANSITION)

        with(requireActivity() as MenuHost) {
            addMenuProvider(object: MenuProvider {
                override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}
                override fun onMenuItemSelected(item: MenuItem): Boolean {
                    when (item.itemId) {
                        R.id.refresh_thumb_item -> {
                            archiveId?.let {
                                thumbLoadJob?.cancel()
                                thumbView.setImageDrawable(null)
                                lifecycleScope.launch {
                                    val (thumbPath, modifiedTime) = withContext(Dispatchers.IO) { DatabaseReader.refreshThumbnail(archiveId, requireContext()) }
                                    thumbPath?.let { path ->
                                        Glide.with(thumbView).load(path).format(DecodeFormat.PREFER_RGB_565).signature(ObjectKey(modifiedTime)).into(thumbView)
                                    }
                                }
                            }
                        }
                    }
                    return false
                }
            }, viewLifecycleOwner, Lifecycle.State.RESUMED)
        }

        lifecycleScope.launch {
            val archive = withContext(Dispatchers.Default) { DatabaseReader.getArchive(archiveId!!) }
            setUpDetailView(view, archive)

        }

        addToCatButton.isVisible = ServerManager.canEdit
        addToCatButton.setOnClickListener {
            val dialog = AddToCategoryDialogFragment.newInstance(listOf(archiveId!!))
            dialog.show(childFragmentManager, "add_category")
        }

        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        ReaderTabHolder.registerRemoveListener(this)
        ReaderTabHolder.registerClearListener(this)
        ReaderTabHolder.registerAddListener(this)
        tagListener = context as? TagInteractionListener
    }

    override fun onDetach() {
        super.onDetach()
        ReaderTabHolder.unregisterRemoveListener(this)
        ReaderTabHolder.unregisterClearListener(this)
        ReaderTabHolder.unregisterAddListener(this)
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

    override fun onDestroy() {
        thumbLoadJob?.cancel()
        super.onDestroy()
    }

    private fun getSearchTag(tag: String, namespace: String) : String {
        return when {
            namespace == requireContext().getString(R.string.other_namespace) -> "\"$tag\""
            isLocalSearch -> "$namespace:\"$tag\""
            else -> "\"$namespace:$tag\"$"
        }
    }

    private fun setupCategories(archive: Archive) {
        val categories = CategoryManager.getStaticCategories(archive.id)
        if (categories != null) {
            catLayout.visibility = View.VISIBLE
            for (category in categories) {
                val catView = createCatView(category, archive.id)
                catFlexLayout.addView(catView)
            }
        } else catLayout.visibility = View.GONE
    }

    private fun setUpTags(archive: Archive) {
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
            tagLayout.addView(
                namespaceLayout,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
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
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(10, 10, 10, 10)
            layoutParams = params
        }
    }

    private fun createCatView(category: ArchiveCategory, archiveId: String): TextView {
        val catView = TextView(context).apply {
            text = category.name
            background = ContextCompat.getDrawable(requireContext(), R.drawable.tag_background)
            setTextColor(Color.WHITE)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(10)
            layoutParams = params
            gravity = Gravity.CENTER_VERTICAL
            if (ServerManager.canEdit)
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, ContextCompat.getDrawable(requireContext(), R.drawable.ic_clear_black_24dp), null)
        }

        if (!ServerManager.canEdit)
            return catView

        catView.setOnClickListener {
            val builder = AlertDialog.Builder(requireContext()).apply {
                setTitle(getString(R.string.category_remove_title))
                setMessage(getString(R.string.category_remove_message, category.name))
                setPositiveButton(R.string.yes) { dialog, _ ->
                    dialog.dismiss()
                    lifecycleScope.launch {
                        val success = withContext(Dispatchers.IO) { WebHandler.removeFromCategory(requireContext(), category.id, archiveId) }
                        if (success) {
                            catFlexLayout.removeView(catView)
                            Snackbar.make(requireView(), getString(R.string.category_removed_message, category.name), Snackbar.LENGTH_SHORT).show()
                            ServerManager.parseCategories(requireContext())
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

    private fun setUpDetailView(view: View, archive: Archive?) {
        bookmarkButton = view.findViewById(R.id.bookmark_button)
        with(bookmarkButton) {
            setOnClickListener {
                lifecycleScope.launch {
                    archiveId?.let {
                        if (ReaderTabHolder.isTabbed(it)) {
                            ReaderTabHolder.removeTab(it)
                            ReaderTabHolder.resetServerProgress(it)
                            text = getString(R.string.bookmark)
                        } else {
                            ReaderTabHolder.addTab(it, 0)
                            text = getString(R.string.unbookmark)
                        }
                    }
                }
            }
            archiveId?.let { lifecycleScope.launch { text = getString(if (ReaderTabHolder.isTabbed(it)) R.string.unbookmark else R.string.bookmark) }
            }
        }

        val readButton: Button = view.findViewById(R.id.read_button)
        readButton.setOnClickListener { (activity as ArchiveDetails).startReaderActivityForResult() }

        if (archive == null) return

        setUpTags(archive)
        setupCategories(archive)

        val titleView: TextView = view.findViewById(R.id.title)
        titleView.text = archive.title

        thumbLoadJob = lifecycleScope.launch {
            val (thumbPath, modifiedTime) = withContext(Dispatchers.Default) { DatabaseReader.getArchiveImage(archive, requireContext()) }
            thumbPath?.let {
                Glide.with(thumbView).load(it).format(DecodeFormat.PREFER_RGB_565).
                    addListener(object: RequestListener<Drawable> {
                        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                            requireActivity().supportStartPostponedEnterTransition()
                            return false
                        }

                        override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                            requireActivity().supportStartPostponedEnterTransition()
                            return false
                        }

                    })
                    .signature(ObjectKey(modifiedTime)).into(thumbView)
            }
        }
    }

    override fun onAddedToCategory(category: ArchiveCategory, archiveIds: List<String>) {
        val id = archiveIds.firstOrNull() ?: return
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
}
