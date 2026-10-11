package com.shilapi.xcertplay

import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import com.shilapi.xcertplay.browser.BrowserStreamOutput
import com.shilapi.xcertplay.browser.SharedCertificateSync
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h740dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserStartupReadinessTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private var lifecycle: ActivityController<DiPlayActivity>? = null

    @Before fun before() {
        CarPlayBackgroundSession.clear()
        app.getSharedPreferences("diplay", 0).edit().clear().commit()
        app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
    }

    @After fun after() {
        lifecycle?.pause()?.stop()?.destroy()
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", null)
        CarPlayBackgroundSession.clear()
    }

    @Test fun freshSetupPreparesHttpsWithoutPairingOrStartingCarplayAndClosesWhenHidden() {
        val first = mock(SharedCertificateSync::class.java)
        val second = mock(SharedCertificateSync::class.java)
        var created = 0
        val screen = create()
        screen.browserCertificateSyncFactory = { if (created++ == 0) first else second }
        // Authentication and connection setup must not be prerequisites for public HTTPS setup.
        ReflectionHelpers.setField(screen, "setupError", "Synthetic authentication not configured")
        lifecycle!!.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        verify(first).start()
        assertNull(DiPlayPreferences.phoneAddress(screen))
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertNull(DiPlaySessionService.currentBrowserOutput())
        assertNull(shadowOf(screen).nextStartedActivity)
        assertNull(shadowOf(app).nextStartedService)

        lifecycle!!.pause().stop()
        verify(first).close()
        assertNull(ReflectionHelpers.getField<SharedCertificateSync?>(screen, "browserCertificateSync"))
        lifecycle!!.restart().start().resume()
        verify(second).start()
        assertEquals(2, created)
        verifyNoMoreInteractions(first)
    }

    @Test fun runningBrowserServiceOwnsRenewalWhenTheLauncherReopens() {
        val output = mock(BrowserStreamOutput::class.java)
        `when`(output.running).thenReturn(true)
        ReflectionHelpers.setStaticField(DiPlaySessionService::class.java, "browser", output)
        val screen = create()
        screen.browserCertificateSyncFactory = { throw AssertionError("The running service already owns certificate renewal") }
        lifecycle!!.start().resume()
        assertNull(ReflectionHelpers.getField<SharedCertificateSync?>(screen, "browserCertificateSync"))
        assertSame(output, DiPlaySessionService.currentBrowserOutput())
        verify(output, never()).close()
    }

    @Test fun mergedManifestDeclaresAndroid17LocalNetworkPermission() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        assertTrue(requested.orEmpty().contains("android.permission.ACCESS_LOCAL_NETWORK"))
    }

    private fun create(): DiPlayActivity {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(app, DiPlayActivity::class.java).putExtra("page", "connection")).create()
        lifecycle = controller
        return controller.get().also { screen ->
            ReflectionHelpers.setField(screen, "mBase", object : ContextWrapper(screen.baseContext) {
                override fun getPackageName() = "com.shihab.diplay.browsertest"
            })
        }
    }
}
