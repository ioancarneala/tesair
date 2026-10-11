package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager

internal const val BROWSER_PRODUCT_METADATA = "com.shilapi.xcertplay.BROWSER_PRODUCT"

/** Product selection belongs to the application manifest, independently of signing/build type. */
internal fun browserCarPlayEnabled(context: Context): Boolean {
    val metadata = try {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)?.metaData
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }
    if (metadata?.containsKey(BROWSER_PRODUCT_METADATA) == true) {
        return metadata.getBoolean(BROWSER_PRODUCT_METADATA, false)
    }
    // Preserve the original private installation and its existing test contexts only.
    return context.packageName == "com.shihab.diplay.browsertest"
}
