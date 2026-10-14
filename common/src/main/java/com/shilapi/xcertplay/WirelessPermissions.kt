package com.shilapi.xcertplay

import android.Manifest
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

internal object WirelessPermissions {
    fun required(sdk: Int, mode: WirelessHotspotMode): List<String> {
        val permissions = when {
            // These modes attach to a user-configured network; they do not manage nearby Wi-Fi.
            mode == WirelessHotspotMode.EXISTING_WIFI || mode == WirelessHotspotMode.MANUAL ->
                if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
            sdk >= 33 -> listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES)
            sdk >= 31 -> listOf(Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            else -> listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Android 17 protects direct LAN sockets in every wireless mode, including an existing network.
        return if (sdk >= 37) permissions + Manifest.permission.ACCESS_LOCAL_NETWORK else permissions
    }
}
