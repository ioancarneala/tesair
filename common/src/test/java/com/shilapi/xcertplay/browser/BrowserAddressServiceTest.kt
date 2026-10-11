package com.shilapi.xcertplay.browser

import android.app.Service
import android.app.Notification
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.Build
import android.system.OsConstants
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowVpnService

/** Exercise service state and resource ownership with controlled VPN and network-interface boundaries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33], shadows = [BrowserAddressBuilderShadow::class])
class BrowserAddressServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val expectedAddresses get() = buildList {
        add(BrowserAddressService.ADDRESS to 32)
        if (Build.VERSION.SDK_INT >= 33) add(BrowserAddressService.CARRIER_ADDRESS to 32)
    }
    private val expectedTunnelCount get() = expectedAddresses.size
    private lateinit var lifecycle: ServiceController<BrowserAddressService>
    private lateinit var service: BrowserAddressService
    private lateinit var interfaces: MockedStatic<NetworkInterface>
    private lateinit var aliasInterface: NetworkInterface
    private lateinit var carrierInterface: NetworkInterface

    @Before fun setUp() {
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).clearAllNetworks()
        ShadowVpnService.setPrepareResult(null)
        BrowserAddressVpnBoundary.reset()
        BrowserAddressVpnBoundary.createDescriptor = {
            ParcelFileDescriptor.open(temporary.newFile(), ParcelFileDescriptor.MODE_READ_WRITE)
        }
        aliasInterface = mock(NetworkInterface::class.java)
        carrierInterface = mock(NetworkInterface::class.java)
        `when`(aliasInterface.index).thenReturn(41)
        `when`(aliasInterface.isUp).thenReturn(false)
        `when`(carrierInterface.index).thenReturn(42)
        `when`(carrierInterface.isUp).thenReturn(true)
        interfaces = mockStatic(NetworkInterface::class.java)
        interfaces.`when`<NetworkInterface> {
            NetworkInterface.getByInetAddress(InetAddress.getByName(BrowserAddressService.ADDRESS))
        }.thenReturn(aliasInterface)
        interfaces.`when`<NetworkInterface> {
            NetworkInterface.getByInetAddress(InetAddress.getByName(BrowserAddressService.CARRIER_ADDRESS))
        }.thenReturn(carrierInterface)
        lifecycle = Robolectric.buildService(BrowserAddressService::class.java).create()
        service = lifecycle.get()
        BrowserAddressService.disable(service)
        while (shadowOf(context).nextStartedService != null) { /* Discard earlier test starts. */ }
    }

    @After fun tearDown() {
        lifecycle.destroy()
        BrowserAddressVpnBoundary.descriptors.forEach { runCatching { it.close() } }
        context.getSharedPreferences("browser_address", 0).edit().clear().commit()
        ShadowVpnService.setPrepareResult(null)
        interfaces.close()
    }

    @Test fun addressIsScopedToOneHostAndOneAppWithoutChangingDnsOrIpv6Routing() {
        activate()

        assertEquals(expectedAddresses, BrowserAddressVpnBoundary.addresses)
        assertEquals(expectedAddresses, BrowserAddressVpnBoundary.routes)
        assertEquals("Each handover must replace, not accumulate, the previous address",
            expectedAddresses.map { listOf(it) }, BrowserAddressVpnBoundary.establishedAddresses)
        assertEquals(List(expectedTunnelCount) { service.packageName }, BrowserAddressVpnBoundary.allowedApps)
        assertTrue("DNS must stay with the existing network", BrowserAddressVpnBoundary.dns.isEmpty())
        assertEquals(List(expectedTunnelCount) { OsConstants.AF_INET6 }, BrowserAddressVpnBoundary.allowedFamilies)
        assertEquals(expectedTunnelCount, BrowserAddressVpnBoundary.bypassCalls)
        assertEquals(1500, BrowserAddressVpnBoundary.mtu)
        assertEquals(List(expectedTunnelCount) { listOf("allow:${service.packageName}", "establish") }.flatten(),
            BrowserAddressVpnBoundary.scopeCalls)
        assertTrue(BrowserAddressService.snapshot(service).active)
        assertTrue(BrowserAddressService.snapshot(service).enabled)
    }

    @Test
    @Config(sdk = [31, 33, 34], shadows = [BrowserAddressBuilderShadow::class, BrowserAddressForegroundShadow::class])
    fun foregroundTypeIsExplicitAndSupportedByTheAndroidVersion() {
        activate()

        val expected = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        assertEquals(listOf(expected), BrowserAddressVpnBoundary.foregroundTypes)
        assertEquals("The untyped overload imports the unsupported manifest type on older Android", 0,
            BrowserAddressVpnBoundary.untypedForegroundCalls)
        assertTrue(shadowOf(service).isLastForegroundNotificationAttached)
    }

    @Test fun missingPermissionNeverOptsInOrStartsAnAddress() {
        ShadowVpnService.setPrepareResult(Intent("test.VPN_CONSENT"))

        assertFalse(BrowserAddressService.enable(service))
        assertNull(shadowOf(context).nextStartedService)
        assertEquals(0, BrowserAddressVpnBoundary.establishCalls)
        assertFalse(BrowserAddressService.snapshot(service).enabled)
        assertFalse(BrowserAddressService.snapshot(service).active)
        assertEquals(BrowserAddressService.Phase.PERMISSION_REQUIRED, BrowserAddressService.snapshot(service).phase)
    }

    @Test fun permissionIsRecheckedWhenAQueuedStartActuallyRuns() {
        val queued = requestStart()
        ShadowVpnService.setPrepareResult(Intent("test.VPN_CONSENT"))

        dispatch(queued)

        assertEquals(0, BrowserAddressVpnBoundary.establishCalls)
        assertFalse(BrowserAddressService.snapshot(service).active)
        assertEquals(BrowserAddressService.Phase.PERMISSION_REQUIRED, BrowserAddressService.snapshot(service).phase)
    }

    @Test fun failedAppAllowlistCannotFallBackToAnUnrestrictedTunnel() {
        BrowserAddressVpnBoundary.allowFailure = PackageManager.NameNotFoundException("test scope failure")

        dispatch(requestStart())

        assertEquals(listOf("allow:${service.packageName}"), BrowserAddressVpnBoundary.scopeCalls)
        assertEquals(0, BrowserAddressVpnBoundary.establishCalls)
        assertFalse(BrowserAddressService.snapshot(service).active)
        assertEquals(BrowserAddressService.Phase.ERROR, BrowserAddressService.snapshot(service).phase)
    }

    @Test fun deniedEstablishmentClearsOptInWithoutClaimingActivation() {
        BrowserAddressVpnBoundary.createDescriptor = { null }

        dispatch(requestStart())

        assertEquals(1, BrowserAddressVpnBoundary.establishCalls)
        assertFalse(BrowserAddressService.snapshot(service).enabled)
        assertFalse(BrowserAddressService.snapshot(service).active)
        assertEquals(BrowserAddressService.Phase.PERMISSION_REQUIRED, BrowserAddressService.snapshot(service).phase)
    }

    @Test fun repeatedStartKeepsTheExistingDescriptors() {
        val firstStart = activate()
        val descriptors = BrowserAddressVpnBoundary.descriptors.toList()

        dispatch(firstStart)
        assertTrue(BrowserAddressService.enable(service))
        assertTrue(BrowserAddressService.ensureStartedIfEnabled(service))

        assertNull("An active tunnel must not queue another start", shadowOf(context).nextStartedService)
        assertEquals(expectedTunnelCount, BrowserAddressVpnBoundary.establishCalls)
        assertEquals(descriptors, BrowserAddressVpnBoundary.descriptors)
        descriptors.forEach { assertTrue(it.fd >= 0) }
        assertTrue(BrowserAddressService.snapshot(service).active)
    }

    @Test fun disableClosesEveryDescriptorClearsOptInAndRejectsTheOldStart() {
        val oldStart = activate()
        val descriptors = BrowserAddressVpnBoundary.descriptors.toList()

        BrowserAddressService.disable(service)
        descriptors.forEach(::assertClosed)
        assertFalse(BrowserAddressService.snapshot(service).enabled)
        assertFalse(BrowserAddressService.snapshot(service).active)
        dispatch(oldStart)

        assertEquals(expectedTunnelCount, BrowserAddressVpnBoundary.establishCalls)
        assertFalse(BrowserAddressService.ensureStartedIfEnabled(service))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun revokeClosesEveryDescriptorClearsOptInAndRejectsTheOldStart() {
        val oldStart = activate()
        val descriptors = BrowserAddressVpnBoundary.descriptors.toList()

        service.onRevoke()
        descriptors.forEach(::assertClosed)
        assertFalse(BrowserAddressService.snapshot(service).enabled)
        assertEquals(BrowserAddressService.Phase.REVOKED, BrowserAddressService.snapshot(service).phase)
        dispatch(oldStart)

        assertEquals(expectedTunnelCount, BrowserAddressVpnBoundary.establishCalls)
        assertFalse(BrowserAddressService.snapshot(service).active)
        assertFalse(BrowserAddressService.ensureStartedIfEnabled(service))
    }

    @Test fun pauseClosesEveryDescriptorButPreservesOptInForANewStart() {
        val oldStart = activate()
        val first = BrowserAddressVpnBoundary.descriptors.toList()

        BrowserAddressService.pause(service)
        first.forEach(::assertClosed)
        assertTrue(BrowserAddressService.snapshot(service).enabled)
        assertFalse(BrowserAddressService.snapshot(service).active)
        dispatch(oldStart)
        assertEquals("A queued start must not undo Pause", expectedTunnelCount,
            BrowserAddressVpnBoundary.establishCalls)

        assertTrue(BrowserAddressService.ensureStartedIfEnabled(service))
        dispatch(nextStart())

        assertEquals(expectedTunnelCount * 2, BrowserAddressVpnBoundary.establishCalls)
        first.forEach(::assertClosed)
        BrowserAddressVpnBoundary.descriptors.drop(first.size).forEach { assertTrue(it.fd >= 0) }
        assertTrue(BrowserAddressService.snapshot(service).active)
    }

    @Test fun destroyingTheServiceClosesEveryDescriptorButPreservesOptIn() {
        activate()
        val descriptors = BrowserAddressVpnBoundary.descriptors.toList()

        service.onDestroy()

        descriptors.forEach(::assertClosed)
        assertTrue(BrowserAddressService.snapshot(service).enabled)
        assertEquals(BrowserAddressService.Phase.OFF, BrowserAddressService.snapshot(service).phase)
    }

    @Test
    @Config(sdk = [33])
    fun carrierPermissionRevokedDuringHandoverClosesTheRetainedAlias() {
        val createDescriptor = BrowserAddressVpnBoundary.createDescriptor
        BrowserAddressVpnBoundary.createDescriptor = {
            if (BrowserAddressVpnBoundary.establishCalls == 1) createDescriptor() else null
        }
        val oldStart = requestStart()

        dispatch(oldStart)

        assertEquals(2, BrowserAddressVpnBoundary.establishCalls)
        assertClosed(BrowserAddressVpnBoundary.descriptors.single())
        assertFalse(BrowserAddressService.snapshot(service).enabled)
        assertEquals(BrowserAddressService.Phase.PERMISSION_REQUIRED, BrowserAddressService.snapshot(service).phase)
        dispatch(oldStart)
        assertEquals("A stale start must not restore a revoked VPN", 2, BrowserAddressVpnBoundary.establishCalls)
        assertFalse(BrowserAddressService.ensureStartedIfEnabled(service))
    }

    @Test
    @Config(sdk = [33])
    fun carrierFailureClosesTheAliasAndAllowsACleanRetry() {
        val createDescriptor = BrowserAddressVpnBoundary.createDescriptor
        BrowserAddressVpnBoundary.createDescriptor = {
            if (BrowserAddressVpnBoundary.establishCalls == 1) createDescriptor()
            else throw IllegalStateException("test carrier failure")
        }

        dispatch(requestStart())

        val failedAlias = BrowserAddressVpnBoundary.descriptors.single()
        assertClosed(failedAlias)
        assertEquals(2, BrowserAddressVpnBoundary.establishCalls)
        assertEquals(BrowserAddressService.Phase.ERROR, BrowserAddressService.snapshot(service).phase)
        assertTrue(BrowserAddressService.snapshot(service).enabled)

        BrowserAddressVpnBoundary.createDescriptor = createDescriptor
        assertTrue(BrowserAddressService.ensureStartedIfEnabled(service))
        dispatch(nextStart())

        assertEquals(4, BrowserAddressVpnBoundary.establishCalls)
        assertClosed(failedAlias)
        BrowserAddressVpnBoundary.descriptors.drop(1).forEach { assertTrue(it.fd >= 0) }
        assertTrue(BrowserAddressService.snapshot(service).active)
    }

    @Test
    @Config(sdk = [33])
    fun failedCarrierAllowlistClosesTheAliasWithoutEstablishingAnUnscopedCarrier() {
        BrowserAddressVpnBoundary.allowFailure = PackageManager.NameNotFoundException("test carrier scope failure")
        BrowserAddressVpnBoundary.allowFailureOnCall = 2

        dispatch(requestStart())

        assertEquals(listOf("allow:${service.packageName}", "establish", "allow:${service.packageName}"),
            BrowserAddressVpnBoundary.scopeCalls)
        assertEquals(1, BrowserAddressVpnBoundary.establishCalls)
        assertClosed(BrowserAddressVpnBoundary.descriptors.single())
        assertEquals(BrowserAddressService.Phase.ERROR, BrowserAddressService.snapshot(service).phase)
    }

    @Test
    @Config(sdk = [33])
    fun retainedAliasMayBeDownWhenTheSeparateCarrierIsUp() {
        assertFalse(aliasInterface.isUp)
        assertTrue(carrierInterface.isUp)

        activate()

        assertTrue(BrowserAddressService.ensureStartedIfEnabled(service))
        assertTrue(BrowserAddressService.snapshot(service).active)
    }

    @Test
    @Config(sdk = [33])
    fun missingRetainedAliasClosesBothInterfacesWithoutRetryingInTheBackground() {
        interfaces.`when`<NetworkInterface> {
            NetworkInterface.getByInetAddress(InetAddress.getByName(BrowserAddressService.ADDRESS))
        }.thenReturn(null)

        val oldStart = requestStart()
        dispatch(oldStart)

        assertUnsupportedAddressStopped()
        dispatch(oldStart)
        assertEquals(2, BrowserAddressVpnBoundary.establishCalls)
    }

    @Test
    @Config(sdk = [33])
    fun reusedInterfaceIsRejectedEvenWhenBothAddressesRemainVisible() {
        `when`(aliasInterface.index).thenReturn(42)

        dispatch(requestStart())

        assertUnsupportedAddressStopped()
    }

    @Test
    @Config(sdk = [33])
    fun missingCarrierClosesTheRetainedAlias() {
        interfaces.`when`<NetworkInterface> {
            NetworkInterface.getByInetAddress(InetAddress.getByName(BrowserAddressService.CARRIER_ADDRESS))
        }.thenReturn(null)

        dispatch(requestStart())

        assertUnsupportedAddressStopped()
    }

    @Test
    @Config(sdk = [33])
    fun aDownCarrierIsRejectedDespiteOpenDescriptors() {
        `when`(carrierInterface.isUp).thenReturn(false)

        dispatch(requestStart())

        assertUnsupportedAddressStopped()
    }

    @Test
    @Config(sdk = [33])
    fun interfaceLookupFailureStopsWithoutClaimingActivation() {
        interfaces.`when`<NetworkInterface> {
            NetworkInterface.getByInetAddress(InetAddress.getByName(BrowserAddressService.ADDRESS))
        }.thenThrow(SocketException("test interface lookup failure"))

        dispatch(requestStart())

        assertUnsupportedAddressStopped()
    }

    @Test
    @Config(sdk = [33])
    fun recoveryDetectsAnAliasRemovedAfterStartupAndAllowsOnlyAnExplicitRetry() {
        activate()
        val previousDescriptors = BrowserAddressVpnBoundary.descriptors.toList()
        interfaces.`when`<NetworkInterface> {
            NetworkInterface.getByInetAddress(InetAddress.getByName(BrowserAddressService.ADDRESS))
        }.thenReturn(null)

        assertFalse(BrowserAddressService.ensureStartedIfEnabled(service))

        assertUnsupportedAddressStopped()
        interfaces.`when`<NetworkInterface> {
            NetworkInterface.getByInetAddress(InetAddress.getByName(BrowserAddressService.ADDRESS))
        }.thenReturn(aliasInterface)
        assertFalse(BrowserAddressService.ensureStartedIfEnabled(service))
        assertNull(shadowOf(context).nextStartedService)

        dispatch(requestStart())

        assertTrue(BrowserAddressService.snapshot(service).active)
        assertEquals(4, BrowserAddressVpnBoundary.establishCalls)
        previousDescriptors.forEach(::assertClosed)
        BrowserAddressVpnBoundary.descriptors.drop(2).forEach { assertTrue(it.fd >= 0) }
    }

    @Test fun aClosedAliasDescriptorCannotRemainActive() {
        activate()
        BrowserAddressVpnBoundary.descriptors.first().close()

        assertFalse(BrowserAddressService.enable(service))

        assertUnsupportedAddressStopped()
    }

    @Test
    @Config(sdk = [33])
    fun aClosedCarrierDescriptorClosesTheRetainedAlias() {
        activate()
        BrowserAddressVpnBoundary.descriptors.last().close()

        assertFalse(BrowserAddressService.ensureStartedIfEnabled(service))

        assertUnsupportedAddressStopped()
    }

    @Test fun anExistingVpnIsNotReplaced() {
        val manager = shadowOf(context.getSystemService(ConnectivityManager::class.java))
        val network = ShadowNetwork.newInstance(123)
        @Suppress("DEPRECATION")
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_VPN, 0, true, true)
        manager.addNetwork(network, info)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        manager.setNetworkCapabilities(network, capabilities)

        assertFalse(BrowserAddressService.enable(service))

        assertEquals(0, BrowserAddressVpnBoundary.establishCalls)
        assertNull(shadowOf(context).nextStartedService)
        assertFalse(BrowserAddressService.snapshot(service).enabled)
        assertEquals(BrowserAddressService.Phase.OTHER_VPN, BrowserAddressService.snapshot(service).phase)
    }

    private fun requestStart(): Intent {
        assertTrue(BrowserAddressService.enable(service))
        return nextStart()
    }

    private fun nextStart(): Intent = checkNotNull(shadowOf(context).nextStartedService).also {
        assertEquals(BrowserAddressService.ACTION_START, it.action)
        assertEquals(BrowserAddressService::class.java.name, it.component?.className)
    }

    private fun activate(): Intent = requestStart().also {
        dispatch(it)
        assertTrue("The fixture must own a real open descriptor", BrowserAddressService.snapshot(service).active)
        assertEquals(expectedTunnelCount, BrowserAddressVpnBoundary.descriptors.size)
    }

    private fun dispatch(intent: Intent) {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
    }

    private fun assertClosed(descriptor: ParcelFileDescriptor) {
        assertThrows(IllegalStateException::class.java) { descriptor.fd }
    }

    private fun assertUnsupportedAddressStopped() {
        BrowserAddressVpnBoundary.descriptors.forEach(::assertClosed)
        assertEquals(BrowserAddressService.Phase.ERROR, BrowserAddressService.snapshot(service).phase)
        assertFalse(BrowserAddressService.snapshot(service).enabled)
        assertFalse(BrowserAddressService.ensureStartedIfEnabled(service))
        assertNull(shadowOf(context).nextStartedService)
    }
}

object BrowserAddressVpnBoundary {
    val addresses = mutableListOf<Pair<String, Int>>()
    val establishedAddresses = mutableListOf<List<Pair<String, Int>>>()
    val routes = mutableListOf<Pair<String, Int>>()
    val allowedApps = mutableListOf<String>()
    val allowedFamilies = mutableListOf<Int>()
    val dns = mutableListOf<String>()
    val scopeCalls = mutableListOf<String>()
    val descriptors = mutableListOf<ParcelFileDescriptor>()
    val foregroundTypes = mutableListOf<Int>()
    var untypedForegroundCalls = 0
    var bypassCalls = 0
    var mtu = 0
    var establishCalls = 0
    var allowFailure: Exception? = null
    var allowFailureOnCall = 1
    var createDescriptor: () -> ParcelFileDescriptor? = { null }

    fun reset() {
        addresses.clear(); establishedAddresses.clear(); routes.clear(); allowedApps.clear(); allowedFamilies.clear()
        dns.clear(); scopeCalls.clear(); descriptors.clear()
        foregroundTypes.clear(); untypedForegroundCalls = 0
        bypassCalls = 0; mtu = 0; establishCalls = 0; allowFailure = null; allowFailureOnCall = 1
        createDescriptor = { null }
    }
}

/** Default Robolectric silently treats the untyped overload as NONE; record the call boundary too. */
@Implements(VpnService::class)
class BrowserAddressForegroundShadow : ShadowVpnService() {
    private var explicitTypeCall = false

    @Implementation override fun startForeground(id: Int, notification: Notification) {
        if (!explicitTypeCall) BrowserAddressVpnBoundary.untypedForegroundCalls++
        super.startForeground(id, notification)
    }

    @Implementation override fun startForeground(id: Int, notification: Notification, type: Int) {
        BrowserAddressVpnBoundary.foregroundTypes += type
        explicitTypeCall = true
        try {
            super.startForeground(id, notification, type)
        } finally {
            explicitTypeCall = false
        }
    }
}

@Implements(VpnService.Builder::class)
class BrowserAddressBuilderShadow {
    @RealObject private lateinit var builder: VpnService.Builder
    private val addresses = mutableListOf<Pair<String, Int>>()

    @Implementation fun addAddress(address: String, prefix: Int): VpnService.Builder {
        addresses += address to prefix
        BrowserAddressVpnBoundary.addresses += address to prefix
        return builder
    }
    @Implementation fun addAddress(address: InetAddress, prefix: Int): VpnService.Builder =
        addAddress(address.hostAddress!!, prefix)
    @Implementation fun addRoute(address: String, prefix: Int): VpnService.Builder {
        BrowserAddressVpnBoundary.routes += address to prefix
        return builder
    }
    @Implementation fun addRoute(address: InetAddress, prefix: Int): VpnService.Builder =
        addRoute(address.hostAddress!!, prefix)
    @Implementation fun addAllowedApplication(packageName: String): VpnService.Builder {
        BrowserAddressVpnBoundary.scopeCalls += "allow:$packageName"
        if (BrowserAddressVpnBoundary.allowedApps.size + 1 == BrowserAddressVpnBoundary.allowFailureOnCall) {
            BrowserAddressVpnBoundary.allowFailure?.let { throw it }
        }
        BrowserAddressVpnBoundary.allowedApps += packageName
        return builder
    }
    @Implementation fun addDnsServer(address: String): VpnService.Builder {
        BrowserAddressVpnBoundary.dns += address
        return builder
    }
    @Implementation fun addDnsServer(address: InetAddress): VpnService.Builder = addDnsServer(address.hostAddress!!)
    @Implementation fun allowFamily(family: Int): VpnService.Builder {
        BrowserAddressVpnBoundary.allowedFamilies += family
        return builder
    }
    @Implementation fun allowBypass(): VpnService.Builder {
        BrowserAddressVpnBoundary.bypassCalls++
        return builder
    }
    @Implementation fun setMtu(mtu: Int): VpnService.Builder {
        BrowserAddressVpnBoundary.mtu = mtu
        return builder
    }
    @Implementation fun establish(): ParcelFileDescriptor? {
        BrowserAddressVpnBoundary.establishCalls++
        BrowserAddressVpnBoundary.establishedAddresses += addresses.toList()
        BrowserAddressVpnBoundary.scopeCalls += "establish"
        return BrowserAddressVpnBoundary.createDescriptor().also { descriptor ->
            if (descriptor != null) BrowserAddressVpnBoundary.descriptors += descriptor
        }
    }
}
