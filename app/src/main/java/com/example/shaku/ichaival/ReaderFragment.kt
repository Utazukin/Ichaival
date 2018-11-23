package com.example.shaku.ichaival


import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.android.synthetic.main.fragment_reader.*


class ReaderFragment : Fragment() {
    private var listener: OnFragmentInteractionListener? = null
    private var imageToDisplay: String? = null
    private var isAttached = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view =  inflater.inflate(R.layout.fragment_reader, container, false)

        val mainImage: PhotoView = view.findViewById(R.id.main_image)
        mainImage.setOnPhotoTapListener { _, _, _ -> listener?.onFragmentTap() }
        mainImage.setOnOutsidePhotoTapListener{ listener?.onFragmentTap() }

        return view
    }

    fun displayImage(image: String?) {
        if (!isAttached)
           imageToDisplay = image
        else {
            Glide.with(this).asBitmap().load(image)
                .into(object : SimpleTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        main_image.setImageBitmap(resource)
                    }
                })
        }
    }

    override fun onDetach() {
        super.onDetach()
        isAttached = false
    }

    override fun onAttach(context: Context?) {
        super.onAttach(context)
        if (context is OnFragmentInteractionListener) {
            isAttached = true
            listener = context

            if (imageToDisplay != null) {
                val image = imageToDisplay
                imageToDisplay = null
                displayImage(image)
            }
        } else {
            throw RuntimeException(context.toString() + " must implement OnFragmentInteractionListener")
        }
    }

    interface OnFragmentInteractionListener {
        fun onFragmentTap()
    }

}
