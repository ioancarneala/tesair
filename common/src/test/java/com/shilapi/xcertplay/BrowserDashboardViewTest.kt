package com.shilapi.xcertplay

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w320dp-h640dp")
class BrowserDashboardViewTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun firstUnmetPrerequisiteGetsTheOnlyMainAction() {
        val cases = listOf(
            state(permissionsRequired = true, hotspotNeedsAttention = true, bluetoothRequired = true,
                setupNeedsAttention = true, receiverFailed = true, route = false, trustedUrl = null) to
                R.string.onboarding_permissions_action,
            state(battery = false, hotspotNeedsAttention = true, bluetoothRequired = true,
                route = false) to R.string.browser_battery_allow,
            state(hotspotNeedsAttention = true, bluetoothRequired = true, setupNeedsAttention = true,
                receiverFailed = true, route = false, trustedUrl = null) to R.string.onboarding_network_settings,
            state(bluetoothRequired = true, setupNeedsAttention = true, receiverFailed = true,
                route = false, trustedUrl = null) to R.string.browser_next_open_bluetooth,
            state(setupNeedsAttention = true, receiverFailed = true, route = false,
                trustedUrl = null) to R.string.browser_dashboard_review_setup,
        )
        for ((state, action) in cases) {
            val view = dashboard()
            view.render(state)
            assertEquals(listOf(context.getString(action)), mainActions(view))
        }
    }

    @Test fun workingPictureDoesNotHideRequiredBackgroundAccess() {
        val actions = mutableListOf<String>()
        val view = dashboard(actions)
        val working = state(browserConnected = true, rendered = true, battery = false,
            route = false, routeEnabled = true, routeBusy = true, otherVpn = true,
            secureAddressMessage = context.getString(R.string.browser_https_retry), addressNeedsHelp = true)
        view.render(working)
        assertTrue(working.pictureReady)
        assertFalse(working.ready)
        assertEquals(BrowserDashboardView.Step.BACKGROUND, working.step)
        assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_ready)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_https_retry)))
        assertEquals(listOf(context.getString(R.string.browser_battery_allow)), mainActions(view))
        button(view, R.string.browser_dashboard_show_details).performClick()
        button(view, R.string.browser_battery_allow).performClick()
        assertEquals(listOf("battery"), actions)
        button(view, R.string.browser_details_show_technical).performClick()
        assertFalse(button(view, R.string.browser_dashboard_disable_route).isEnabled)
    }

    @Test fun browserConnectionOrAStaleFrameDoesNotClaimVideoIsReady() {
        val view = dashboard()
        view.render(state(browserConnected = true))
        assertTrue(texts(view).contains(context.getString(R.string.browser_next_video_title)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_ready)))
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_picture_waiting)))
        button(view, R.string.browser_dashboard_hide_details).performClick()

        view.render(state(browserConnected = true, rendered = true))
        assertTrue(texts(view).contains(context.getString(R.string.browser_dashboard_ready)))
        val staleStates = listOf(
            state(browserConnected = false, rendered = true),
            state(browserConnected = true, rendered = true, iphoneActive = false),
            state(browserConnected = true, rendered = true, serverRunning = false),
            state(browserConnected = true, rendered = true, setupNeedsAttention = true),
        )
        for (stale in staleStates) {
            view.render(stale)
            assertFalse(stale.ready)
            assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_ready)))
        }
    }

    @Test fun automaticReceiverRouteAddressAndPictureWaitsDoNotAskForReconnect() {
        val cases = listOf(
            state(serverRunning = false) to R.string.browser_next_starting_title,
            state(sessionRunning = false) to R.string.browser_next_starting_title,
            state(route = false, routeBusy = true) to R.string.browser_address_starting,
            state(trustedUrl = null) to R.string.browser_https_preparing,
            state(browserConnected = true) to R.string.browser_next_video_title,
        )
        for ((state, title) in cases) {
            val view = dashboard()
            view.render(state)
            assertTrue(texts(view).contains(context.getString(title)))
            assertEquals(BrowserDashboardView.Action.NONE, state.action)
            assertEquals(listOf(context.getString(R.string.settings),
                context.getString(R.string.browser_dashboard_show_details)), buttons(view))
            assertFalse(texts(view).contains(context.getString(R.string.retry_carplay_connection)))
        }
    }

    @Test fun explicitReceiverFailureOffersAnActionableRetry() {
        val actions = mutableListOf<String>()
        val view = dashboard(actions)
        view.render(state(serverRunning = false, sessionRunning = false, receiverFailed = true,
            iphoneActive = false, trustedUrl = null))
        assertTrue(texts(view).contains(context.getString(R.string.browser_next_retry_title)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_next_retry_hint)))
        assertEquals(listOf(context.getString(R.string.retry_carplay_connection)), mainActions(view))
        button(view, R.string.retry_carplay_connection).performClick()
        assertEquals(listOf("retry"), actions)
    }

    @Test fun stoppedReceiverOffersStartWithoutPretendingToConnect() {
        val actions = mutableListOf<String>()
        val view = dashboard(actions)
        view.render(state(stopped = true, serverRunning = false, sessionRunning = false,
            iphoneActive = false, trustedUrl = null))
        assertEquals(listOf(context.getString(R.string.onboarding_iphone_start)), mainActions(view))
        assertFalse(texts(view).contains(context.getString(R.string.browser_next_starting_title)))
        button(view, R.string.onboarding_iphone_start).performClick()
        assertEquals(listOf("retry"), actions)

        view.render(state(stopped = true, battery = false))
        assertEquals(listOf(context.getString(R.string.browser_battery_allow)), mainActions(view))
    }

    @Test fun stoppedReceiverDoesNotPretendItsAddressIsStillBeingPrepared() {
        val view = dashboard()
        view.render(state(stopped = true, serverRunning = false, sessionRunning = false,
            iphoneActive = false, trustedUrl = null))
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_address_idle)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_server_starting)))

        view.render(state(stopped = false, serverRunning = false, sessionRunning = false,
            iphoneActive = false, trustedUrl = null))
        assertTrue(texts(view).contains(context.getString(R.string.browser_dashboard_server_starting)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_details_address_idle)))

        val certificateError = context.getString(R.string.browser_https_unavailable)
        view.render(state(stopped = true, serverRunning = false, trustedUrl = null,
            secureAddressMessage = certificateError))
        assertTrue(texts(view).contains(certificateError))
        assertFalse(texts(view).contains(context.getString(R.string.browser_details_address_idle)))

        view.render(state(stopped = true, serverRunning = false))
        assertTrue(texts(view).contains(TEST_URL))
        assertFalse(texts(view).contains(context.getString(R.string.browser_details_address_idle)))
    }

    @Test fun eachRequiredActionOpensItsRelevantSettingInsteadOfGenericRetry() {
        val cases = listOf(
            Triple(state(permissionsRequired = true), R.string.onboarding_permissions_action, "permissions"),
            Triple(state(hotspotNeedsAttention = true), R.string.onboarding_network_settings, "hotspot"),
            Triple(state(bluetoothRequired = true), R.string.browser_next_open_bluetooth, "bluetooth"),
            Triple(state(otherVpn = true, route = false), R.string.browser_next_open_vpn, "vpn"),
            Triple(state(route = false), R.string.browser_address_enable, "route"),
            Triple(state(trustedUrl = null, addressNeedsHelp = true), R.string.browser_next_open_internet, "internet"),
            Triple(state(iphoneActive = false), R.string.onboarding_iphone_wifi_help, "iphone"),
            Triple(state(setupNeedsAttention = true), R.string.browser_dashboard_review_setup, "guide"),
        )
        for ((state, action, expected) in cases) {
            val actions = mutableListOf<String>()
            val view = dashboard(actions)
            view.render(state)
            button(view, action).performClick()
            assertEquals(context.getString(action), listOf(expected), actions)
        }
    }

    @Test fun terminalSetupFailureOverridesPreviouslyRenderedVideo() {
        val actions = mutableListOf<String>()
        val view = dashboard(actions)
        val failed = state(browserConnected = true, rendered = true,
            route = false, setupNeedsAttention = true)
        view.render(failed)
        assertFalse(failed.ready)
        assertEquals(BrowserDashboardView.Action.SETUP, failed.action)
        assertTrue(texts(view).contains(context.getString(R.string.browser_dashboard_setup_attention)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_dashboard_setup_attention_hint)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_next_starting_hint)))
        button(view, R.string.browser_dashboard_review_setup).performClick()
        assertEquals(listOf("guide"), actions)
    }

    @Test fun detailsStayHiddenUntilRequestedAndEachStepShowsOnlyItsRelevantCard() {
        val view = dashboard()
        view.render(state(iphoneActive = false))
        assertDetailsHidden(view)
        assertFalse(texts(view).contains(context.getString(R.string.browser_next_network_label)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_next_address_label)))
        assertFalse(texts(view).contains("Example Wi-Fi"))
        assertFalse(texts(view).contains(TEST_URL))

        view.render(state(hotspotNeedsAttention = true))
        assertDetailsHidden(view)
        assertTrue(texts(view).contains(context.getString(R.string.browser_next_network_label)))
        assertTrue(texts(view).contains("Example Wi-Fi"))
        assertTrue(texts(view).contains(context.getString(R.string.browser_dashboard_show_password)))
        assertFalse(texts(view).contains(TEST_URL))
        assertFalse(texts(view).any { it.contains(TEST_PASSWORD) })

        view.render(state())
        assertDetailsHidden(view)
        assertTrue(texts(view).contains(context.getString(R.string.browser_next_address_label)))
        assertTrue(texts(view).contains(TEST_URL))
        assertTrue(texts(view).contains(context.getString(R.string.browser_next_same_network, "Example Wi-Fi")))
        assertFalse(texts(view).contains(context.getString(R.string.browser_next_network_label)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_show_password)))

        button(view, R.string.browser_dashboard_show_details).performClick()
        assertFalse(texts(view).contains("Diagnostic example"))
        assertTrue(texts(view).contains(context.getString(R.string.browser_dashboard_connection)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_next_network_label)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_next_address_label)))
        button(view, R.string.browser_dashboard_hide_details).performClick()
        assertDetailsHidden(view)
    }

    @Test fun passwordRequiresRevealAndConcealsOnPauseOrEitherCredentialChange() {
        val view = dashboard()
        view.render(state(hotspotNeedsAttention = true))
        assertFalse(texts(view).any { it.contains(TEST_PASSWORD) })
        button(view, R.string.browser_dashboard_show_password).performClick()
        assertTrue(texts(view).any { it.contains(TEST_PASSWORD) })
        view.render(state(hotspotNeedsAttention = true, routeBusy = true))
        assertTrue(texts(view).any { it.contains(TEST_PASSWORD) })
        view.concealPassword()
        assertFalse(texts(view).any { it.contains(TEST_PASSWORD) })

        button(view, R.string.browser_dashboard_show_password).performClick()
        view.render(state(hotspotNeedsAttention = true, networkName = "Replacement Wi-Fi"))
        assertFalse(texts(view).any { it.contains(TEST_PASSWORD) })
        button(view, R.string.browser_dashboard_show_password).performClick()
        view.render(state(hotspotNeedsAttention = true, networkName = "Replacement Wi-Fi", password = "replacement-secret"))
        assertFalse(texts(view).any { it.contains("replacement-secret") || it.contains(TEST_PASSWORD) })
        assertFalse(state().toString().contains(TEST_PASSWORD))
    }

    @Test fun hidingTheNetworkCardAlwaysResetsPasswordReveal() {
        val view = dashboard()
        view.render(state(hotspotNeedsAttention = true))
        button(view, R.string.browser_dashboard_show_password).performClick()
        assertTrue(texts(view).any { it.contains(TEST_PASSWORD) })
        view.render(state())
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertFalse(texts(view).any { it.contains(TEST_PASSWORD) })
        button(view, R.string.browser_dashboard_show_password).performClick()
        assertTrue(texts(view).any { it.contains(TEST_PASSWORD) })
        button(view, R.string.browser_dashboard_hide_details).performClick()
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertFalse(texts(view).any { it.contains(TEST_PASSWORD) })
        assertTrue(texts(view).contains(context.getString(R.string.browser_dashboard_show_password)))
    }

    @Test fun openNetworkNeverShowsASecretOrARevealAction() {
        val view = dashboard()
        view.render(state(hotspotNeedsAttention = true, password = ""))
        assertTrue(texts(view).contains("Example Wi-Fi"))
        assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_show_password)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_password, "••••••••")))
    }

    @Test fun backgroundAccessIsTheMainActionUntilItsLiveStateIsAllowed() {
        val actions = mutableListOf<String>()
        val view = dashboard(actions)
        view.render(state(battery = false, route = false))
        assertEquals(listOf(context.getString(R.string.browser_battery_allow)), mainActions(view))
        button(view, R.string.browser_battery_allow).performClick()
        assertEquals(listOf("battery"), actions)
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertEquals(1, buttons(view).count { it == context.getString(R.string.browser_battery_allow) })
        button(view, R.string.browser_dashboard_hide_details).performClick()
        view.render(state(battery = false))
        assertTrue(texts(view).contains(context.getString(R.string.browser_battery_allow)))
        assertEquals(BrowserDashboardView.Action.BACKGROUND, state(battery = false).action)
        view.render(state(battery = true, route = false))
        assertEquals(listOf(context.getString(R.string.browser_address_enable)), mainActions(view))
    }

    @Test fun workingVideoWithRequiredApprovalsHasNoExtraVerificationAction() {
        val view = dashboard()
        val working = state(browserConnected = true, rendered = true)
        view.render(working)
        assertTrue(working.pictureReady)
        assertTrue(working.ready)
        assertEquals(BrowserDashboardView.Step.READY, working.step)
        assertTrue(mainActions(view).isEmpty())
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_picture_ready)))
        assertFalse(buttons(view).contains(context.getString(R.string.retry_carplay_connection)))
        assertFalse(buttons(view).contains(context.getString(R.string.browser_dashboard_disable_route)))
    }

    @Test fun pendingSecureAddressUsesCurrentGuidanceAndTrustedAddressBeatsRenewalErrors() {
        val view = dashboard()
        view.render(state(trustedUrl = null, secureAddressMessage = context.getString(R.string.browser_https_retry)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_https_preparing)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_address_missing)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_address_help)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_next_address_label)))

        view.render(state(trustedUrl = null, addressNeedsHelp = true,
            secureAddressMessage = context.getString(R.string.browser_https_retry)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_next_address_title)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_next_address_hint)))
        assertEquals(listOf(context.getString(R.string.browser_next_open_internet)), mainActions(view))

        view.render(state(addressNeedsHelp = true, secureAddressMessage = context.getString(R.string.browser_https_retry)))
        assertTrue(texts(view).contains(TEST_URL))
        assertFalse(texts(view).contains(context.getString(R.string.browser_https_retry)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_next_address_title)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_dashboard_browser_instruction)))
    }

    @Test fun shortEntryAddressKeepsTheVerifiedDirectAddressAsAnExplicitTechnicalFallback() {
        val direct = "https://carplay.tesair.co:8443"
        val view = dashboard()
        view.render(state(trustedUrl = direct))
        assertTrue(texts(view).contains(BrowserConnectionAddress.ENTRY_URL))
        assertFalse(texts(view).contains(direct))
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertTrue(texts(view).contains(BrowserConnectionAddress.ENTRY_URL))
        assertFalse(texts(view).contains(direct))
        button(view, R.string.browser_details_show_technical).performClick()
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_direct_address)))
        assertTrue(texts(view).contains(direct))

        view.render(state(trustedUrl = null,
            secureAddressMessage = context.getString(R.string.browser_https_unavailable)))
        assertFalse(texts(view).contains(BrowserConnectionAddress.ENTRY_URL))
        assertFalse(texts(view).contains(direct))
        assertFalse(texts(view).contains(context.getString(R.string.browser_details_direct_address)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_https_unavailable)))
    }

    @Test fun settingsAndDetailedRecoveryControlsKeepTheirExplicitCallbacks() {
        val actions = mutableListOf<String>()
        val view = dashboard(actions)
        view.render(state())
        button(view, R.string.settings).performClick()
        button(view, R.string.browser_dashboard_show_details).performClick()
        button(view, R.string.browser_details_show_technical).performClick()
        button(view, R.string.retry_carplay_connection).performClick()
        button(view, R.string.browser_dashboard_disable_route).performClick()
        assertEquals(listOf("settings", "retry", "toggle"), actions)
    }

    @Test fun connectionDetailsUseLabeledLiveStatusesWithoutTechnicalMessagesOrRecoveryControls() {
        val actions = mutableListOf<String>()
        val view = dashboard(actions)
        view.render(state(browserConnected = true, rendered = true))
        button(view, R.string.browser_dashboard_show_details).performClick()
        for (label in listOf(R.string.browser_details_iphone, R.string.browser_details_browser,
            R.string.browser_details_background, R.string.browser_details_tesla)) {
            assertTrue(texts(view).contains(context.getString(label)))
        }
        for (status in listOf(R.string.browser_details_connected, R.string.browser_details_picture_ready,
            R.string.browser_details_allowed, R.string.browser_details_enabled)) {
            assertTrue(texts(view).contains(context.getString(status)))
        }
        assertFalse(texts(view).contains("Local address status"))
        assertFalse(texts(view).contains("Diagnostic example"))
        assertFalse(buttons(view).contains(context.getString(R.string.retry_carplay_connection)))
        assertFalse(buttons(view).contains(context.getString(R.string.browser_dashboard_disable_route)))
        assertEquals(1, texts(view).count { it == TEST_URL })
        assertEquals(1, texts(view).count { it == "Example Wi-Fi" })
        assertTrue(actions.isEmpty())

        view.render(state(iphoneActive = false, route = false, routeBusy = true, battery = false))
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_waiting)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_permission_needed)))
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_starting)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_details_picture_ready)))
        assertTrue(actions.isEmpty())
    }

    @Test fun technicalDetailsAreExplicitAndResetWhenConnectionDetailsClose() {
        val actions = mutableListOf<String>()
        val view = dashboard(actions)
        view.render(state())
        assertTrue(button(view, R.string.browser_dashboard_show_details).contentDescription.toString()
            .contains(context.getString(R.string.browser_details_collapsed)))
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertTrue(button(view, R.string.browser_dashboard_hide_details).contentDescription.toString()
            .contains(context.getString(R.string.browser_details_expanded)))
        button(view, R.string.browser_details_show_technical).performClick()
        assertTrue(texts(view).contains("Diagnostic example"))
        assertTrue(texts(view).contains("Local address status"))
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_recovery_hint)))
        button(view, R.string.browser_dashboard_hide_details).performClick()
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertFalse(texts(view).contains("Diagnostic example"))
        assertFalse(texts(view).contains("Local address status"))
        assertTrue(buttons(view).contains(context.getString(R.string.browser_details_show_technical)))
        assertTrue(actions.isEmpty())
    }

    @Test fun detailedStatusDoesNotSuggestTeslaRouteIsRequiredForAWiredConnection() {
        val view = dashboard()
        view.render(state(wireless = false, route = false))
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_not_needed)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_details_not_ready)))
    }

    @Test fun activeLocalRouteOnlyClaimsEnabledUntilBrowserActuallyRendersVideo() {
        val view = dashboard()
        view.render(state(route = true, browserConnected = false, rendered = false))
        button(view, R.string.browser_dashboard_show_details).performClick()
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_enabled)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_details_picture_ready)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_ready)))
        view.render(state(route = true, browserConnected = true, rendered = false))
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_picture_waiting)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_details_picture_ready)))
        view.render(state(route = true, browserConnected = true, rendered = true))
        assertTrue(texts(view).contains(context.getString(R.string.browser_details_picture_ready)))
    }

    @Test fun headlineAnnouncesStateChangesAndIllustrationIsDecorative() {
        val view = dashboard()
        view.render(state())
        val headline = descendants(view).filterIsInstance<TextView>().first {
            it.text.toString() == context.getString(R.string.browser_next_browser_title)
        }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, headline.accessibilityLiveRegion)
        assertTrue(headline.isAccessibilityHeading)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            descendants(view).filterIsInstance<BrowserOnboardingArtView>().single().importantForAccessibility)
        val address = descendants(view).filterIsInstance<TextView>().first { it.text.toString() == TEST_URL }
        assertTrue(address.isTextSelectable)
        view.render(state(browserConnected = true, rendered = true))
        assertEquals(context.getString(R.string.browser_dashboard_ready), headline.text.toString())
    }

    @Test fun browserAddressRendersLeftToRightInsideAnRtlLayout() {
        val view = dashboard().apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        view.render(state())
        val density = context.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val address = descendants(view).filterIsInstance<TextView>().first { it.text.toString() == TEST_URL }
        assertNotNull(address.layout)
        assertEquals(1, address.layout.getParagraphDirection(0))
    }

    @Test fun narrowScreenAndLargeTextKeepPrimaryAndDetailedActionsUsable() {
        val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            fontScale = 1.5f
        })
        val view = BrowserDashboardView(scaled, {}, {}, {}, {}, {})
        view.render(state(hotspotNeedsAttention = true))
        val density = scaled.resources.displayMetrics.density
        val width = (320 * density).toInt()
        val height = (640 * density).toInt()
        fun measureAndCheck() {
            repeat(2) {
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
            }
            assertTrue(view.isFillViewport)
            descendants(view).filterIsInstance<Button>().filter(::visible).forEach {
                assertTrue(it.text.toString(), it.measuredHeight >= 48 * density)
                assertTrue(it.text.toString(), it.measuredWidth > 0 && it.measuredWidth <= width)
            }
        }
        measureAndCheck()
        assertTrue(button(view, R.string.onboarding_network_settings).isEnabled)
        view.render(state(hotspotNeedsAttention = true, battery = false))
        measureAndCheck()
        assertTrue(button(view, R.string.browser_battery_allow).isEnabled)
        button(view, R.string.browser_dashboard_show_details).performClick()
        measureAndCheck()
        assertTrue(button(view, R.string.browser_battery_allow).isEnabled)
        button(view, R.string.browser_details_show_technical).performClick()
        measureAndCheck()
    }

    private fun assertDetailsHidden(view: View) {
        assertFalse(texts(view).contains("Diagnostic example"))
        assertFalse(texts(view).contains(context.getString(R.string.browser_dashboard_connection)))
        assertFalse(texts(view).contains(context.getString(R.string.browser_battery_allow)))
        assertFalse(texts(view).contains(context.getString(R.string.retry_carplay_connection)))
    }

    private fun dashboard(actions: MutableList<String> = mutableListOf()) = BrowserDashboardView(
        context = context,
        allowBackground = { actions += "battery" },
        enableRoute = { actions += "route" },
        toggleRoute = { actions += "toggle" },
        retry = { actions += "retry" },
        settings = { actions += "settings" },
        reviewSetup = { actions += "guide" },
        allowConnection = { actions += "permissions" },
        openHotspot = { actions += "hotspot" },
        openBluetooth = { actions += "bluetooth" },
        openVpn = { actions += "vpn" },
        openInternet = { actions += "internet" },
        connectIphone = { actions += "iphone" },
    )

    private fun state(
        browserConnected: Boolean = false,
        rendered: Boolean = false,
        battery: Boolean = true,
        route: Boolean = true,
        routeEnabled: Boolean = route,
        routeBusy: Boolean = false,
        wireless: Boolean = true,
        networkName: String = "Example Wi-Fi",
        password: String = TEST_PASSWORD,
        trustedUrl: String? = TEST_URL,
        setupNeedsAttention: Boolean = false,
        secureAddressMessage: String? = null,
        serverRunning: Boolean = true,
        sessionRunning: Boolean = true,
        iphoneActive: Boolean = true,
        permissionsRequired: Boolean = false,
        hotspotNeedsAttention: Boolean = false,
        bluetoothRequired: Boolean = false,
        otherVpn: Boolean = false,
        receiverFailed: Boolean = false,
        addressNeedsHelp: Boolean = false,
        stopped: Boolean = false,
    ) = BrowserDashboardView.State(
        serverRunning = serverRunning, sessionRunning = sessionRunning, iphoneActive = iphoneActive,
        browserConnected = browserConnected, videoRendered = rendered, batteryAllowed = battery,
        routeActive = route, routeEnabled = routeEnabled, routeBusy = routeBusy, wireless = wireless,
        routeMessage = "Local address status", networkName = networkName, password = password,
        trustedUrl = trustedUrl, diagnostic = "Diagnostic example", setupNeedsAttention = setupNeedsAttention,
        secureAddressMessage = secureAddressMessage,
        permissionsRequired = permissionsRequired, hotspotNeedsAttention = hotspotNeedsAttention,
        bluetoothRequired = bluetoothRequired, otherVpn = otherVpn, receiverFailed = receiverFailed,
        addressNeedsHelp = addressNeedsHelp,
        stopped = stopped,
    )

    private fun button(view: View, id: Int) = descendants(view).filterIsInstance<Button>()
        .first { visible(it) && it.text.toString() == context.getString(id) }
    private fun buttons(view: View) = descendants(view).filterIsInstance<Button>().filter(::visible)
        .map { it.text.toString() }.toList()
    private fun mainActions(view: View): List<String> {
        val utilities = setOf(R.string.settings, R.string.browser_dashboard_show_details,
            R.string.browser_dashboard_hide_details, R.string.browser_dashboard_show_password,
            R.string.browser_dashboard_hide_password).map(context::getString).toSet()
        return buttons(view).filterNot(utilities::contains)
    }
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>().filter(::visible)
        .map { it.text.toString() }.toList()
    private fun visible(view: View): Boolean = view.visibility == View.VISIBLE &&
        ((view.parent as? View)?.let(::visible) ?: true)
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private companion object {
        const val TEST_PASSWORD = "example-secret"
        const val TEST_URL = "https://carplay.example.test:8443"
    }
}
