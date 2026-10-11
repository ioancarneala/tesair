package com.shilapi.xcertplay

import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class BrowserCarPlayViewportPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearViewport() { context.getSharedPreferences("browser_display", 0).edit().clear().commit() }

    @Test fun firstConnectionDefaultsToFullHdAndNextSessionKeepsBrowserSize() {
        assertEquals(1920 to 1080, BrowserCarPlayViewport.load(context))
        BrowserCarPlayViewport.save(context, 1601, 901)
        assertEquals(1600 to 900, BrowserCarPlayViewport.load(context))
    }

    @Test fun corruptStoredDimensionsRecoverToFullHd() {
        context.getSharedPreferences("browser_display", 0).edit().putInt("width", -1).commit()
        assertEquals(1920 to 1080, BrowserCarPlayViewport.load(context))
    }

    @Test fun unconfiguredManifestsKeepTheLegacyPrivatePackageFallback() {
        fun named(name: String) = object : ContextWrapper(context) { override fun getPackageName() = name }
        assertTrue(browserCarPlayEnabled(named("com.shihab.diplay.browsertest")))
        assertFalse(browserCarPlayEnabled(named("com.shihab.diplay")))
        assertFalse(browserCarPlayEnabled(named("com.shilapi.xcertplay.home")))
    }
}
