package com.example.shaku.ichaival


import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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
        GlobalScope.launch(Dispatchers.Main) {
            archive = async { DatabaseReader.getArchive(archiveId!!, context!!.filesDir) }.await()
            setUpDetailView(view)
        }
        return view
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
