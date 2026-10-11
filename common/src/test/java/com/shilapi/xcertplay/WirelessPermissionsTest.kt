package com.shilapi.xcertplay

import android.Manifest
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.*
import org.junit.Test

class WirelessPermissionsTest {
    @Test fun android17RequiresLocalNetworkInEveryWirelessMode() {
        for (mode in WirelessHotspotMode.entries) {
            val permissions = WirelessPermissions.required(37, mode)
            assertTrue(mode.name, Manifest.permission.ACCESS_LOCAL_NETWORK in permissions)
            assertTrue(mode.name, Manifest.permission.BLUETOOTH_CONNECT in permissions)
            assertFalse(mode.name, Manifest.permission.ACCESS_FINE_LOCATION in permissions)
            assertEquals(permissions.distinct(), permissions)
        }
    }

    @Test fun olderAndroidVersionsKeepTheirExistingDiscoveryPermissions() {
        assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            WirelessPermissions.required(28, WirelessHotspotMode.MANUAL))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION), WirelessPermissions.required(31, WirelessHotspotMode.MANUAL))
        for (sdk in 33..36) assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES), WirelessPermissions.required(sdk, WirelessHotspotMode.MANUAL))
    }

    @Test fun existingWifiDoesNotRequestUnneededLocationOrWifiDiscoveryPermissions() {
        assertTrue(WirelessPermissions.required(29, WirelessHotspotMode.EXISTING_WIFI).isEmpty())
        for (sdk in 31..36) assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT),
            WirelessPermissions.required(sdk, WirelessHotspotMode.EXISTING_WIFI))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_LOCAL_NETWORK),
            WirelessPermissions.required(37, WirelessHotspotMode.EXISTING_WIFI))
    }
}
