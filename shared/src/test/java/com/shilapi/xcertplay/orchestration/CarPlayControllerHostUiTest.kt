package com.shilapi.xcertplay.orchestration

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.hud.BydClusterMapPause
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayControllerHostUiTest {
    private val controllers = mutableListOf<CarPlayController>()
    private val sessions = mutableListOf<AirPlaySession>()

    @After fun tearDown() {
        sessions.forEach(AirPlaySession::close)
        controllers.forEach { it.close(); assertTrue(it.awaitClosed(2_000)) }
        BydNavigationOutputs.endNow()
    }

    @Test fun browserCarIconDoesNotOpenTheServerPhoneOrNotifyAnAndroidUi() {
        val context = RecordingContext("com.shihab.diplay.browsertest")
        var hostRequests = 0
        val (controller, session) = create(context) { hostRequests++ }

        hostListener(controller).onHostUiRequested(session)

        assertTrue(context.activities.isEmpty())
        assertEquals(0, hostRequests)
        assertFalse(controller.isClosed())
        assertFalse(session.isClosed)
    }

    @Test fun nativeHeadUnitCarIconStillOpensHomeAndNotifiesItsUi() {
        val context = RecordingContext("com.shihab.diplay")
        var requestedSession: AirPlaySession? = null
        val (controller, session) = create(context) { requestedSession = it }

        hostListener(controller).onHostUiRequested(session)

        val home = context.activities.single()
        assertEquals(Intent.ACTION_MAIN, home.action)
        assertTrue(home.hasCategory(Intent.CATEGORY_HOME))
        assertTrue(home.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertSame(session, requestedSession)
        assertFalse(controller.isClosed())
        assertFalse(session.isClosed)
    }

    @Test fun releaseBrowserProductDoesNotOpenHomeOrEnableNativeHeadUnitOutputs() {
        val context = RecordingContext("co.tesair.app", browserProduct = true)
        var hostRequests = 0
        val (controller, session) = create(context) { hostRequests++ }

        hostListener(controller).onHostUiRequested(session)

        assertTrue(context.activities.isEmpty())
        assertEquals(0, hostRequests)
        assertNull(BydClusterMapPause.streamControl)
        assertFalse(controller.isClosed())
        assertFalse(session.isClosed)
    }

    @Test fun explicitNativeProductOverridesTheLegacyBrowserPackage() {
        val context = RecordingContext("com.shihab.diplay.browsertest", browserProduct = false)
        var hostRequests = 0
        val (controller, session) = create(context) { hostRequests++ }

        hostListener(controller).onHostUiRequested(session)

        assertTrue(context.activities.single().hasCategory(Intent.CATEGORY_HOME))
        assertEquals(1, hostRequests)
    }

    @Test fun unrelatedDebugSuffixWithoutProductMetadataRemainsNative() {
        val context = RecordingContext("example.native.browsertest")
        var hostRequests = 0
        val (controller, session) = create(context) { hostRequests++ }

        hostListener(controller).onHostUiRequested(session)

        assertTrue(context.activities.single().hasCategory(Intent.CATEGORY_HOME))
        assertEquals(1, hostRequests)
    }

    private fun create(context: Context, onHostUi: (AirPlaySession) -> Unit): Pair<CarPlayController, AirPlaySession> {
        val listener = object : AirPlaySessionListener {
            override fun onHostUiRequested(session: AirPlaySession) = onHostUi(session)
        }
        val config = AirPlayConfig("Test", "02:00:00:00:00:01", "02:00:00:00:00:01", "1",
            AirPlayDisplayConfig(800, 480))
        val identity = AirPlayIdentity.generate()
        val pairings = PairingStore()
        val media = object : AirPlayMediaHandler {}
        val controller = CarPlayController(context,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig("Test", "Test", "Test", "test", "1", "1", 3)),
            config, identity, pairings, listener, media, {}).also(controllers::add)
        val session = AirPlaySession(Socket(), config, identity, pairings, null, listener, media).also(sessions::add)
        return controller to session
    }

    private fun hostListener(controller: CarPlayController): AirPlaySessionListener =
        ReflectionHelpers.getField(controller, "sessionListener")

    private class RecordingContext(private val name: String, browserProduct: Boolean? = null) :
        ContextWrapper(RuntimeEnvironment.getApplication()) {
        init {
            if (browserProduct != null) {
                shadowOf(packageManager).installPackage(PackageInfo().apply {
                    packageName = name
                    applicationInfo = ApplicationInfo().apply {
                        packageName = name
                        metaData = Bundle().apply { putBoolean("com.shilapi.xcertplay.BROWSER_PRODUCT", browserProduct) }
                    }
                })
            }
        }
        val activities = mutableListOf<Intent>()
        override fun getPackageName() = name
        override fun getApplicationContext(): Context = this
        override fun startActivity(intent: Intent) { activities += intent }
    }
}
