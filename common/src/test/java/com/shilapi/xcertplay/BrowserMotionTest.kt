package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserMotionTest {
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var container: FrameLayout
    private lateinit var view: View
    private val motion = BrowserMotion()

    @Before fun setUp() {
        durationScale(1f)
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        container = FrameLayout(host.get())
        view = View(host.get())
        container.addView(view, FrameLayout.LayoutParams(200, 100))
        host.get().setContentView(container)
        // Robolectric attaches the decor, but does not deliver the window manager's app-visibility event.
        val root = ReflectionHelpers.callInstanceMethod<Any>(view, "getViewRootImpl")
        ReflectionHelpers.callInstanceMethod<Unit>(root, "handleAppVisibility",
            ReflectionHelpers.ClassParameter.from(java.lang.Boolean.TYPE, true))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.isAttachedToWindow)
        assertTrue(view.isShown)
        assertEquals(View.VISIBLE, view.windowVisibility)
        assertTrue(ValueAnimator.areAnimatorsEnabled())
        assertEquals(Lifecycle.State.RESUMED, host.get().lifecycle.currentState)
    }

    @After fun tearDown() {
        motion.cancel()
        host.pause().stop().destroy()
        durationScale(1f)
    }

    @Test fun firstRenderIsStaticAndOnlyAChangedStepStartsATransition() {
        motion.update(view, "network")
        assertSettled(view)
        assertNull(animator())

        motion.update(view, "iphone")
        val transition = requireNotNull(animator())
        transition.currentPlayTime = 70
        val position = view.translationY
        assertTrue(position > 0f)
        assertTrue(view.alpha < 1f)

        motion.update(view, "iphone")
        assertSame(transition, animator())
        assertEquals(position, view.translationY, 0f)

        transition.end()
        assertNull(animator())
        assertSettled(view)
    }

    @Test fun reducedMotionSkipsTransitionsAndCancelsAnExistingOneWithoutReplayingIt() {
        motion.update(view, "network")
        durationScale(0f)
        assertFalse(ValueAnimator.areAnimatorsEnabled())
        motion.update(view, "iphone")
        assertNull(animator())
        assertSettled(view)

        durationScale(1f)
        motion.update(view, "iphone")
        assertNull(animator())
        motion.update(view, "browser")
        assertNotNull(animator())

        durationScale(0f)
        motion.update(view, "browser")
        assertNull(animator())
        assertSettled(view)
    }

    @Test fun detachingCancelsAndReattachingDoesNotReplayTheLastStep() {
        motion.update(view, "network")
        motion.update(view, "iphone")
        assertNotNull(animator())

        container.removeView(view)
        assertNull(animator())
        assertSettled(view)
        container.addView(view)
        motion.update(view, "iphone")
        assertNull(animator())
        assertSettled(view)
    }

    @Test fun stoppingTheHostCancelsAndBackgroundUpdatesRemainStatic() {
        motion.update(view, "network")
        motion.update(view, "iphone")
        assertNotNull(animator())

        host.pause().stop()
        assertNull(animator())
        assertSettled(view)
        motion.update(view, "browser")
        assertNull(animator())
        assertSettled(view)
        host.start().resume()
        motion.update(view, "browser")
        assertNull(animator())
    }

    @Test fun replacingTheRenderedViewResetsThePreviousViewAndUsesTheNewStep() {
        motion.update(view, "network")
        motion.update(view, "iphone")
        val oldAnimation = requireNotNull(animator())
        val replacement = View(host.get())
        container.addView(replacement, FrameLayout.LayoutParams(200, 100))

        motion.update(replacement, "browser")

        assertSettled(view)
        assertFalse(oldAnimation.isStarted)
        assertNotSame(oldAnimation, animator())
        assertNotNull(animator())
        motion.cancel()
        assertSettled(replacement)
    }

    @Test fun dashboardPollingDoesNotRestartMotionWhenOnlyDiagnosticDataChanges() {
        val dashboard = BrowserDashboardView(host.get(), allowBackground = {}, enableRoute = {},
            toggleRoute = {}, retry = {}, settings = {})
        host.get().setContentView(dashboard)
        val dashboardMotion = ReflectionHelpers.getField<BrowserMotion>(dashboard, "motion")
        dashboard.render(dashboardState(iphoneActive = false, diagnostic = "Starting"))
        assertNull(animator(dashboardMotion))
        dashboard.render(dashboardState(iphoneActive = true, diagnostic = "Connected"))
        val transition = requireNotNull(animator(dashboardMotion))
        dashboard.render(dashboardState(iphoneActive = true, diagnostic = "Still connected"))
        assertSame(transition, animator(dashboardMotion))
        dashboardMotion.cancel()
    }

    private fun animator(owner: BrowserMotion = motion): ValueAnimator? =
        ReflectionHelpers.getField(owner, "animator")

    private fun durationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
            ReflectionHelpers.ClassParameter.from(java.lang.Float.TYPE, scale))
    }

    private fun assertSettled(target: View) {
        assertEquals(1f, target.alpha, 0f)
        assertEquals(0f, target.translationY, 0f)
    }

    private fun dashboardState(iphoneActive: Boolean, diagnostic: String) = BrowserDashboardView.State(
        serverRunning = true, sessionRunning = true, iphoneActive = iphoneActive,
        browserConnected = false, videoRendered = false, batteryAllowed = true,
        routeActive = true, routeEnabled = true, routeBusy = false, wireless = true,
        routeMessage = "Ready", networkName = "Example hotspot", password = "test-only-password",
        trustedUrl = "https://example.test:8443", diagnostic = diagnostic,
    )
}
