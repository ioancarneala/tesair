package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.VpnService
import com.shilapi.xcertplay.browser.BrowserAddressService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33], shadows = [OnboardingVpnConsentShadow::class])
class BrowserOnboardingVpnConsentTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val networks get() = shadowOf(context.getSystemService(ConnectivityManager::class.java))

    @Before @After fun reset() {
        BrowserAddressService.disable(context)
        networks.clearAllNetworks()
        OnboardingVpnConsentShadow.prepareCalls = 0
    }

    @Test fun pollingReadinessNeverPreparesOverAnotherVpn() {
        val network = ShadowNetwork.newInstance(123)
        @Suppress("DEPRECATION")
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_VPN, 0, true, true)
        networks.addNetwork(network, info)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        networks.setNetworkCapabilities(network, capabilities)

        repeat(3) { assertFalse(BrowserOnboardingProgress.localAddressApproved(context)) }
        assertEquals(0, OnboardingVpnConsentShadow.prepareCalls)

        networks.clearAllNetworks()
        assertTrue(BrowserOnboardingProgress.localAddressApproved(context))
        assertEquals(1, OnboardingVpnConsentShadow.prepareCalls)
    }

    @Test fun aNormallyStoppedLocalServiceStillChecksItsExistingConsent() {
        assertFalse(BrowserAddressService.snapshot(context).active)
        assertTrue(BrowserOnboardingProgress.localAddressApproved(context))
        assertEquals(1, OnboardingVpnConsentShadow.prepareCalls)
    }
}

@Implements(VpnService::class)
class OnboardingVpnConsentShadow {
    companion object {
        var prepareCalls = 0

        @Implementation @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun prepare(context: Context): Intent? {
            prepareCalls++
            return null
        }
    }
}
