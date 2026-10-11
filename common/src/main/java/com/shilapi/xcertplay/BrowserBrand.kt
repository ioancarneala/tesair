package com.shilapi.xcertplay

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

internal object BrowserBrand {
    fun header(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = BrowserDesign.dp(context, 48)
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_browser_brand)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(BrowserDesign.dp(context, 36), BrowserDesign.dp(context, 36)).apply {
            marginEnd = BrowserDesign.dp(context, 10)
        })
        addView(BrowserDesign.setupText(context, 17, true).apply {
            text = context.getString(R.string.browser_product_name)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
}
