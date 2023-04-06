package com.likeminds.feedsx.utils

import android.content.res.ColorStateList
import android.view.View
import androidx.core.content.ContextCompat
import com.likeminds.feedsx.R
import com.likeminds.feedsx.branding.model.LMBranding
import com.likeminds.feedsx.databinding.ProgressBarBinding

object ProgressHelper {
    fun showProgress(
        progressBarBinding: ProgressBarBinding,
        enableBackground: Boolean = false
    ) {
        if (enableBackground) {
            progressBarBinding.root.setBackgroundColor(
                ContextCompat.getColor(
                    progressBarBinding.root.context,
                    R.color.background
                )
            )
        } else {
            progressBarBinding.root.background = null
        }
        if (LMBranding.currentPrimary != null || LMBranding.currentAdvanced != null) {
            progressBarBinding.progView.indeterminateTintList = ColorStateList.valueOf(
                LMBranding.currentPrimary ?: LMBranding.currentAdvanced!!.second
            )
        }
        progressBarBinding.root.visibility = View.VISIBLE
        progressBarBinding.root.setOnClickListener { }
    }

    fun isVisible(progressBarBinding: ProgressBarBinding): Boolean {
        return progressBarBinding.root.visibility == View.VISIBLE
    }

    fun hideProgress(progressBarBinding: ProgressBarBinding) {
        progressBarBinding.root.visibility = View.GONE
    }
}