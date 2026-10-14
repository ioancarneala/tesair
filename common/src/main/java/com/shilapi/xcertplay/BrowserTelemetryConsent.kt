package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R

/** The optional pilot choice is independent of the receiver's required permissions. */
internal object BrowserTelemetryConsent {
    fun show(activity: Activity, onDone: () -> Unit = {}): AlertDialog {
        val content = BrowserDesign.setupCard(activity)
        content.addView(BrowserDesign.setupText(activity, 24, true).apply {
            text = activity.getString(R.string.browser_telemetry_title)
            isAccessibilityHeading = true
        })
        content.addView(BrowserDesign.setupText(activity, 16).apply {
            text = activity.getString(R.string.browser_telemetry_description)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = BrowserDesign.dp(activity, 12) })
        content.addView(BrowserDesign.setupText(activity, 14).apply {
            text = activity.getString(R.string.browser_telemetry_content_private)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = BrowserDesign.dp(activity, 12) })
        return AlertDialog.Builder(activity).setView(content)
            .setPositiveButton(R.string.browser_telemetry_participate) { _, _ ->
                BrowserTelemetry.setEnabled(activity, true)
                onDone()
            }
            .setNegativeButton(R.string.browser_telemetry_decline) { _, _ ->
                BrowserTelemetry.setEnabled(activity, false)
                onDone()
            }
            .setOnCancelListener {
                BrowserTelemetry.setEnabled(activity, false)
                onDone()
            }.create().also { it.show() }
    }
}
