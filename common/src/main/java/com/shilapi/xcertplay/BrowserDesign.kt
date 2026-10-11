package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** Shared native-View primitives for the browser setup and connection dashboard. */
internal object BrowserDesign {
    val background = Color.rgb(12, 17, 27)
    private val surface = Color.rgb(21, 30, 44)
    private val border = Color.rgb(42, 56, 75)
    private const val RIPPLE = 0x336F9FD9
    private const val CARD_RADIUS = 20
    private const val BUTTON_RADIUS = 14
    val theme = SettingsTheme.CARD

    data class OnboardingPalette(
        val background: Int,
        val ink: Int,
        val muted: Int,
        val line: Int,
        val field: Int,
        val accent: Int,
        val onAccent: Int,
        val wash: Int,
        val error: Int,
        val success: Int,
    )

    fun onboarding(context: Context) = OnboardingPalette(
        context.getColor(R.color.browser_paper), context.getColor(R.color.browser_ink),
        context.getColor(R.color.browser_muted), context.getColor(R.color.browser_line),
        context.getColor(R.color.browser_field), context.getColor(R.color.browser_accent),
        context.getColor(R.color.browser_on_accent), context.getColor(R.color.browser_wash),
        context.getColor(R.color.browser_error), context.getColor(R.color.browser_success),
    )

    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    fun text(context: Context, size: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size.toFloat()
        setTextColor(if (bold) theme.textPrimary else theme.textSecondary)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(context, 3).toFloat(), 1f)
    }

    fun setupText(context: Context, size: Int, bold: Boolean = false) = text(context, size, bold).apply {
        val palette = onboarding(context)
        setTextColor(if (bold) palette.ink else palette.muted)
    }

    fun setupCard(context: Context) = card(context).apply {
        val palette = onboarding(context)
        background = shape(context, palette.field, CARD_RADIUS).apply {
            setStroke(dp(context, 1), palette.line)
        }
    }

    fun setupButton(context: Context, title: String, primary: Boolean = false, click: () -> Unit) =
        button(context, title, primary, click).apply {
            val palette = onboarding(context)
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(palette.muted, if (primary) palette.onAccent else palette.accent),
            ))
            val enabled = shape(context, if (primary) palette.accent else Color.TRANSPARENT, BUTTON_RADIUS)
            val disabled = shape(context, if (primary) palette.line else Color.TRANSPARENT, BUTTON_RADIUS)
            background = RippleDrawable(ColorStateList.valueOf((palette.accent and 0x00FFFFFF) or 0x26000000),
                StateListDrawable().apply {
                    addState(intArrayOf(-android.R.attr.state_enabled), disabled)
                    addState(intArrayOf(), enabled)
                }, null)
            foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),
                    shape(context, Color.TRANSPARENT, BUTTON_RADIUS).apply {
                        setStroke(dp(context, 3), if (primary) palette.onAccent else palette.accent)
                    })
            }
        }

    fun card(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
        background = shape(context, surface, CARD_RADIUS).apply { setStroke(dp(context, 1), border) }
    }

    fun button(context: Context, title: String, primary: Boolean = false, click: () -> Unit) = Button(context).apply {
        text = title
        textSize = 16f
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(context, 52)
        minimumHeight = dp(context, 52)
        setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
        setTextColor(if (primary) theme.buttonText else theme.accent)
        background = RippleDrawable(ColorStateList.valueOf(RIPPLE),
            shape(context, if (primary) theme.accent else Color.TRANSPARENT, BUTTON_RADIUS), null)
        foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(context, Color.TRANSPARENT, BUTTON_RADIUS).apply {
                setStroke(dp(context, 3), if (primary) theme.buttonText else theme.accent)
            })
        }
        stateListAnimator = null
        gravity = Gravity.CENTER
        setOnClickListener { click() }
    }

    private fun shape(context: Context, color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radius).toFloat()
    }
}
