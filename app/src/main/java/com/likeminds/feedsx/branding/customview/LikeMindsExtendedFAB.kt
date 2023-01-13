package com.likeminds.feedsx.branding.customview

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.likeminds.feedsx.branding.model.BrandingData
import com.likeminds.feedsx.R
import com.likeminds.feedsx.branding.util.BrandingUtil

internal class LikeMindsExtendedFAB : ExtendedFloatingActionButton {
    constructor(context: Context) : super(context) {
        initiate(null)
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        initiate(attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle
    ) {
        initiate(attrs)
    }

    private fun initiate(attrs: AttributeSet?) {
        // fonts
        val array = context.obtainStyledAttributes(attrs, R.styleable.LikeMindsExtendedFAB)
        val fontStyle = array.getString(R.styleable.LikeMindsExtendedFAB_font_Style)
        this.typeface = BrandingUtil.getTypeFace(context, fontStyle)
        array.recycle()

        // color
        this.backgroundTintList = ColorStateList.valueOf(BrandingData.getButtonsColor())
    }
}