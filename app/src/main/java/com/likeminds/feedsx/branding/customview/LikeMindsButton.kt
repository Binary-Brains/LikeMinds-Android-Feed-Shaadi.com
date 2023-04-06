package com.likeminds.feedsx.branding.customview

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import com.google.android.material.button.MaterialButton
import com.likeminds.feedsx.R
import com.likeminds.feedsx.branding.model.LMBranding
import com.likeminds.feedsx.branding.util.BrandingUtil

internal class LikeMindsButton : MaterialButton {
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
        val array = context.obtainStyledAttributes(attrs, R.styleable.LikeMindsButton)
        val fontStyle = array.getString(R.styleable.LikeMindsButton_font_type)
        val buttonType = array.getString(R.styleable.LikeMindsButton_button_type)
        this.typeface = BrandingUtil.getTypeFace(context, fontStyle)
        array.recycle()

        // sets text color
        setTextColor(LMBranding.getButtonsColor())

        // applies button color to button drawables
        compoundDrawables.forEach {
            it?.setTintList(ColorStateList.valueOf(LMBranding.getButtonsColor()))
        }

        // color
        if (!buttonType.equals("normal"))
            this.backgroundTintList = ColorStateList.valueOf(LMBranding.getButtonsColor())
    }
}