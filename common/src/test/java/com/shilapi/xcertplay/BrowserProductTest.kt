package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserProductTest {
    @Test fun explicitProductFlagEnablesTheReleasePackageWithoutADebugSuffix() {
        assertTrue(browserCarPlayEnabled(application("com.shihab.diplay", enabled = true)))
    }

    @Test fun explicitFlagDoesNotDependOnTheApplicationId() {
        assertTrue(browserCarPlayEnabled(application("example.browser.product", enabled = true)))
    }

    @Test fun nativeApplicationsWithoutTheFlagRemainNative() {
        listOf("com.shihab.diplay", "com.diplay.home", "com.diplay.maphost",
            "example.native.browsertest").forEach {
            assertFalse(it, browserCarPlayEnabled(application(it)))
        }
    }

    @Test fun theExactLegacyTestInstallationRemainsCompatible() {
        assertTrue(browserCarPlayEnabled(application("com.shihab.diplay.browsertest")))
    }

    @Test fun anExplicitNativeFlagOverridesTheLegacyPackage() {
        assertFalse(browserCarPlayEnabled(application("com.shihab.diplay.browsertest", enabled = false)))
    }

    @Test fun unavailableApplicationMetadataCannotEnableAnUnrelatedPackage() {
        val manager = mock(PackageManager::class.java)
        `when`(manager.getApplicationInfo("example.native", PackageManager.GET_META_DATA))
            .thenThrow(PackageManager.NameNotFoundException())
        assertFalse(browserCarPlayEnabled(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = "example.native"
            override fun getPackageManager() = manager
        }))
    }

    private fun application(name: String, enabled: Boolean? = null): ContextWrapper {
        val manager = mock(PackageManager::class.java)
        val info = ApplicationInfo().apply {
            packageName = name
            metaData = enabled?.let { Bundle().apply { putBoolean(BROWSER_PRODUCT_METADATA, it) } }
        }
        `when`(manager.getApplicationInfo(name, PackageManager.GET_META_DATA)).thenReturn(info)
        return object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = name
            override fun getPackageManager() = manager
        }
    }
}
