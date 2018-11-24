package com.example.shaku.ichaival


import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch


private const val ARCHIVE_ID = "arcid"

class ArchiveDetailsFragment : Fragment() {
    private var archiveId: String? = null
    private var archive: Archive? = null
    private lateinit var tagLayout: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            archiveId = it.getString(ARCHIVE_ID)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_archive_details, container, false)
        tagLayout = view.findViewById(R.id.tag_layout)

        GlobalScope.launch(Dispatchers.Main) {
            archive = async { DatabaseReader.getArchive(archiveId!!, context!!.filesDir) }.await()
            setUpDetailView(view)
        }
        return view
    }

    private fun setUpTags() {
        val archiveCopy = archive ?: return

        for (pair in archiveCopy.tags) {
            val namespace = if (pair.key == "global") "Other:" else "${pair.key}:"
            val namespaceLayout = LinearLayout(context)
            namespaceLayout.orientation = LinearLayout.HORIZONTAL
            tagLayout.addView(namespaceLayout, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            val namespaceView = createTagView(namespace)
            namespaceLayout.addView(namespaceView)

            for (tag in pair.value) {
                val tagView = createTagView(tag)
                namespaceLayout.addView(tagView)
            }
        }
    }

    private fun createTagView(tag: String) : TextView {
        val tagView = TextView(context)
        tagView.text = tag
        //tagView.setPadding(10, 10,10 ,10)
        tagView.background = ContextCompat.getDrawable(context!!, R.drawable.tag_gray_background)
        tagView.setTextColor(Color.WHITE)
        val layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        layoutParams.setMargins(10, 10, 10, 10)
        tagView.layoutParams = layoutParams
        return tagView
    }

    private fun setUpDetailView(view: View) {
        val bookmarkButton: Button = view.findViewById(R.id.bookmark_button)
        with(bookmarkButton) {
            setOnClickListener {
                val copy = archive
                if (copy != null) {
                    if (ReaderTabHolder.isTabbed(copy.id)) {
                        ReaderTabHolder.removeTab(copy.id)
                        text = getString(R.string.bookmark)
                    } else {
                        ReaderTabHolder.addTab(copy, 0)
                        text = getString(R.string.unbookmark)
                    }
                }
            }
            text = getString(if (ReaderTabHolder.isTabbed(archive?.id)) R.string.unbookmark else R.string.bookmark)
        }

        setUpTags()

        val readButton: Button = view.findViewById(R.id.read_button)
        readButton.setOnClickListener {
            val intent = Intent(activity, ReaderActivity::class.java)
            val bundle = Bundle()
            bundle.putString("id", archiveId)
            intent.putExtras(bundle)
            startActivity(intent)
        }

        val titleView: TextView = view.findViewById(R.id.title)
        titleView.text = archive?.title

        GlobalScope.launch(Dispatchers.Main) {
            val thumbView: ImageView = view.findViewById(R.id.cover)
            val thumb = async { DatabaseReader.getArchiveImage(archive!!, context!!.filesDir) }.await()
            Glide.with(thumbView).asBitmap().load(thumb).into(thumbView)

            //Replace the thumbnail with the full size image.
            val image = async { archive?.getPageImage(0) }.await()
            Glide.with(thumbView).asBitmap().load(image).into(object: SimpleTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    thumbView.setImageBitmap(resource)
                }
            })
        }
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
