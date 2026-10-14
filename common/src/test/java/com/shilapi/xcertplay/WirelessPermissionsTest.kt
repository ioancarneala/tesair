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

    @Test fun managedHotspotsKeepTheirExistingDiscoveryPermissions() {
        for (mode in listOf(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT, WirelessHotspotMode.WIFI_P2P)) {
            assertEquals(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                WirelessPermissions.required(28, mode))
            assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION), WirelessPermissions.required(31, mode))
            for (sdk in 33..36) assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES), WirelessPermissions.required(sdk, mode))
            assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_LOCAL_NETWORK), WirelessPermissions.required(37, mode))
        }
    }

    @Test fun userConfiguredNetworksDoNotRequestLocationOrWifiDiscoveryPermissions() {
        for (mode in listOf(WirelessHotspotMode.MANUAL, WirelessHotspotMode.EXISTING_WIFI)) {
            for (sdk in 28..30) assertEquals(emptyList<String>(), WirelessPermissions.required(sdk, mode))
            for (sdk in 31..36) assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT),
                WirelessPermissions.required(sdk, mode))
            assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_LOCAL_NETWORK),
                WirelessPermissions.required(37, mode))
        }
    }
}
