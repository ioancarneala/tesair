package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import java.time.Duration
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrowserEvTestInputTest {
    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var context: Context

    @Before fun setUp() {
        directory = File(RuntimeEnvironment.getApplication().cacheDir, "ev-input-test").apply { mkdirs() }
        file = File(directory, "ev-battery-test.json")
        context = testContext()
    }

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    @Test fun absentInputAndNonDebugBrowserPackagesStayDisabled() {
        assertNull(BrowserEvTestInput.open(context))
        write()
        assertNull(BrowserEvTestInput.open(testContext(debuggable = false)))
        assertNull(BrowserEvTestInput.open(testContext(packageName = "com.shihab.diplay")))
        assertNotNull(BrowserEvTestInput.open(context))
    }

    @Test fun validInputPreservesValuesWithoutInventingUnknownEnergy() {
        write()
        val snapshot = BrowserEvTestInput.open(context)!!.snapshot()!!
        assertEquals(75.5, snapshot.batteryPercent, 0.0)
        assertEquals(350, snapshot.rangeKm)
        assertEquals(500, snapshot.maxRangeKm)
        assertFalse(snapshot.rangeWarning)
        assertFalse(snapshot.charging)
        assertNull(snapshot.currentChargeWh)
        assertNull(snapshot.maxChargeWh)
        assertTrue(file.exists())
    }

    @Test fun atomicUpdatesAreReadByTheSameProviderAndRemovalStopsData() {
        write()
        val provider = BrowserEvTestInput.open(context)!!
        val replacement = File(directory, "replacement.json")
        replacement.writeText(sample(percent = "20", charging = "true",
            extra = ",\"currentChargeWh\":16000,\"maxChargeWh\":80000"))
        assertTrue(replacement.renameTo(file))
        val snapshot = provider.snapshot()!!
        assertEquals(20.0, snapshot.batteryPercent, 0.0)
        assertTrue(snapshot.rangeWarning)
        assertTrue(snapshot.charging)
        assertEquals(16_000L, snapshot.currentChargeWh)
        assertEquals(80_000L, snapshot.maxChargeWh)
        assertTrue(file.delete())
        assertNull(provider.snapshot())
    }

    @Test fun invalidReplacementNeverReusesTheLastValidSample() {
        write()
        val provider = BrowserEvTestInput.open(context)!!
        file.writeText("{")
        assertNull(provider.snapshot())
        write()
        assertNotNull(provider.snapshot())
        write(expiresAt = System.currentTimeMillis() - 1)
        assertNull(provider.snapshot())
    }

    @Test fun expiredAndUnboundedSamplesAreRejected() {
        for (expiry in listOf(System.currentTimeMillis() - 1, System.currentTimeMillis(),
            System.currentTimeMillis() + 180_000, Long.MAX_VALUE)) {
            write(expiresAt = expiry)
            assertNull("expiry=$expiry", BrowserEvTestInput.open(context))
        }
    }

    @Test fun freshFileUpdatesCannotExtendTheProvidersTenMinuteLifetime() {
        write()
        val provider = BrowserEvTestInput.open(context)!!
        ShadowSystemClock.advanceBy(Duration.ofMinutes(9))
        write()
        assertNotNull(provider.snapshot())
        ShadowSystemClock.advanceBy(Duration.ofMinutes(1))
        write()
        assertNull(provider.snapshot())
        assertNotNull(BrowserEvTestInput.open(context))
    }

    @Test fun oversizedAndMalformedJsonAreRejectedWithoutThrowing() {
        val valid = sample()
        val invalid = listOf("", "{", "[]", valid + "{}", valid + " ".repeat(1_024),
            valid.replace("\"charging\"", "charging"), valid.replace("\"charging\"", "'charging'"),
            valid.replace("\"charging\":false", "\"charging\":\"false\""),
            valid.replace("\"rangeKm\":350", "\"rangeKm\":\"350\""),
            valid.replace("\"rangeKm\":350", "\"rangeKm\":350.1"),
            valid.replace("\"charging\":false", "\"charging\":false,\"charging\":true"),
            valid.replace("\"charging\":false", "\"charging\":false,\"unknown\":1"),
            valid.replace(",\"charging\":false", ""))
        for (value in invalid) {
            file.writeText(value)
            assertNull(value.take(150), BrowserEvTestInput.open(context))
        }
        file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        assertNull(BrowserEvTestInput.open(context))
    }

    @Test fun invalidBatteryRangesAndEnergyAreRejected() {
        val invalid = listOf(sample(percent = "-1"), sample(percent = "101"), sample(percent = "1e999"),
            sample(percent = "NaN"), sample(percent = "\"75\""), sample(range = -1), sample(range = 2_001),
            sample(maxRange = 0), sample(maxRange = 2_001), sample(range = 501, maxRange = 500),
            sample(extra = ",\"currentChargeWh\":-1"), sample(extra = ",\"maxChargeWh\":0"),
            sample(extra = ",\"currentChargeWh\":1000001"), sample(extra = ",\"maxChargeWh\":1000001"),
            sample(extra = ",\"currentChargeWh\":80001,\"maxChargeWh\":80000"))
        for (value in invalid) {
            file.writeText(value)
            assertNull(value, BrowserEvTestInput.open(context))
        }
    }

    @Test fun batteryBoundariesAndExplicitUnknownEnergyAreValid() {
        for (percent in listOf("0", "100")) {
            file.writeText(sample(percent = percent, range = 0,
                extra = ",\"currentChargeWh\":null,\"maxChargeWh\":null"))
            assertEquals(percent.toDouble(), BrowserEvTestInput.open(context)!!.snapshot()!!.batteryPercent, 0.0)
        }
    }

    private fun testContext(debuggable: Boolean = true, packageName: String = "com.shihab.diplay.browsertest"): Context =
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            private val info = ApplicationInfo(baseContext.applicationInfo).apply {
                flags = if (debuggable) flags or ApplicationInfo.FLAG_DEBUGGABLE
                    else flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            }
            override fun getPackageName() = packageName
            override fun getApplicationInfo() = info
            override fun getCacheDir() = directory
        }

    private fun write(expiresAt: Long = System.currentTimeMillis() + 60_000) {
        file.writeText(sample(expiresAt = expiresAt))
    }

    private fun sample(expiresAt: Long = System.currentTimeMillis() + 60_000, percent: String = "75.5",
        range: Int = 350, maxRange: Int = 500, charging: String = "false", extra: String = ""): String =
        "{\"expiresAtMs\":$expiresAt,\"batteryPercent\":$percent,\"rangeKm\":$range," +
            "\"maxRangeKm\":$maxRange,\"charging\":$charging$extra}"
}
