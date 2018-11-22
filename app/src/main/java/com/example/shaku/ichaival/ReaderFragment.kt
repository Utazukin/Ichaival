package com.example.shaku.ichaival


import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import kotlinx.android.synthetic.main.fragment_reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch


class ReaderFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_reader, container, false)
    }

    fun displayImage(image: String?) {
        Glide.with(this).asBitmap().load(image)
            .into(object : SimpleTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    main_image.setImageBitmap(resource)
                }
            })
    }

    private fun replaceImage(page: Int, archive: Archive?) {
        GlobalScope.launch(Dispatchers.Main) {
            if (archive != null && archive.hasPage(page)) {
                val image = async { archive.getPageImage(page) }.await()
                displayImage(image)
                ReaderTabHolder.instance.updatePageIfTabbed(archive.id, page)
            }
        }
    }



}
