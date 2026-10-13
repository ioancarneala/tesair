package com.shilapi.xcertplay

import androidx.annotation.StringRes
import com.shilapi.xcertplay.host.R

/** Friendly choices over the existing receiver preferences; opening settings never normalizes them. */
internal object BrowserPicturePresets {
    data class Preset(val value: Int, @StringRes val title: Int)

    val interfaceSizes = listOf(
        Preset(300, R.string.settings_browser_preferences_compact),
        Preset(200, R.string.settings_browser_preferences_comfortable),
        Preset(150, R.string.settings_browser_preferences_large),
        Preset(100, R.string.settings_browser_preferences_extra_large),
    )
    val videoQualities = listOf(
        Preset(100, R.string.settings_browser_preferences_balanced),
        Preset(125, R.string.settings_browser_preferences_sharper),
        Preset(160, R.string.settings_browser_preferences_sharpest),
    )
    val smoothness = listOf(
        Preset(30, R.string.s_30_fps_lighter_load),
        Preset(60, R.string.s_60_fps_smoother_motion),
    )

    fun title(presets: List<Preset>, current: Int, @StringRes custom: Int) =
        presets.firstOrNull { it.value == current }?.title ?: custom

    fun includingSaved(presets: List<Preset>, current: Int, @StringRes keepCurrent: Int) =
        if (presets.any { it.value == current }) presets else presets + Preset(current, keepCurrent)
}
