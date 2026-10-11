package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HotspotQrImportTest {
    @Test fun escapedCredentialsArePreservedAndNeverAppearInToString() {
        val credentials = HotspotQrImport.parse("WIFI:T:WPA;S: Example\\; hotspot ;P:demo\\;pass\\\\word;;")
        assertEquals(" Example; hotspot ", credentials.ssid)
        assertEquals("demo;pass\\word", credentials.password)
        assertEquals(ManualHotspotSecurity.WPA2, credentials.security)
        assertFalse(credentials.toString().contains(credentials.ssid))
        assertFalse(credentials.toString().contains(credentials.password))
    }

    @Test fun openAndWpa3SecurityRemainDistinct() {
        val open = HotspotQrImport.parse("WIFI:T:nopass;S:Example hotspot;;")
        assertEquals("", open.password)
        assertEquals(ManualHotspotSecurity.OPEN, open.security)
        for (type in listOf("WPA3", "SAE")) {
            assertEquals(ManualHotspotSecurity.WPA3,
                HotspotQrImport.parse("WIFI:T:$type;S:Example hotspot;P:example-password;;").security)
        }
    }

    @Test fun invalidOrUnsupportedNetworkDetailsAreRejected() {
        for (text in listOf(
            "WIFI:T:WEP;S:Example;P:example-password;;",
            "WIFI:T:WPA2-EAP;S:Example;P:example-password;;",
            "WIFI:T:WPA;S:Example;P:example-password;E:PEAP;;",
            "WIFI:T:nopass;S:Example;P:example-password;;",
            "WIFI:T:WPA;S:Example;;",
            "WIFI:T:WPA;S:Example;P:short;;",
            "WIFI:T:WPA;S:${"x".repeat(33)};P:example-password;;",
            "WIFI:T:WPA;S:Example;P:example\u0000password;;",
        )) assertProblem(HotspotQrImport.Problem.NETWORK) { HotspotQrImport.parse(text) }
        for (text in listOf("https://example.com", "DPP:K:example;;", "WIFI:T:WPA;P:example-password;;", "WIFI:${"x".repeat(4096)}")) {
            assertProblem(HotspotQrImport.Problem.NO_WIFI_QR) { HotspotQrImport.parse(text) }
        }
    }

    @Test fun remoteAndFilesystemUrisCannotBeRead() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        for (uri in listOf("https://example.com/qr.png", "file:///tmp/qr.png", "android.resource://example/1")) {
            assertProblem(HotspotQrImport.Problem.IMAGE) { HotspotQrImport.read(resolver, Uri.parse(uri)) }
        }
    }

    @Test fun imageReadStopsAtItsSizeLimit() {
        val endless = object : InputStream() {
            var count = 0
            override fun read(): Int = 0.also { count++ }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length.also { count += length }
        }
        assertProblem(HotspotQrImport.Problem.IMAGE) { HotspotQrImport.readBounded(endless) }
        assertTrue(endless.count <= HotspotQrImport.MAX_IMAGE_BYTES + 16 * 1024)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun fullScreenshotAndInvertedRotatedQrImagesDecodeLocally() {
        for (rotate in listOf(false, true)) {
            val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (rotate) Color.BLACK else Color.WHITE)
            val matrix = QRCodeWriter().encode("WIFI:T:WPA;S:Example hotspot;P:example-password;;", BarcodeFormat.QR_CODE, 500, 500)
            val qr = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
            for (y in 0 until 500) for (x in 0 until 500) {
                qr.setPixel(x, y, if (matrix[x, y] xor rotate) Color.BLACK else Color.WHITE)
            }
            if (rotate) canvas.rotate(90f, 540f, 1200f)
            canvas.drawBitmap(qr, 290f, 950f, null)
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            qr.recycle(); bitmap.recycle()
            val result = HotspotQrImport.decode(bytes)
            assertEquals("Example hotspot", result.ssid)
            assertEquals("example-password", result.password)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun unreadableImagesAndImagesWithoutQrHaveHelpfulFailures() {
        assertProblem(HotspotQrImport.Problem.IMAGE) { HotspotQrImport.decode(byteArrayOf(1, 2, 3)) }
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        assertProblem(HotspotQrImport.Problem.NO_WIFI_QR) { HotspotQrImport.decode(bytes) }
    }

    private fun assertProblem(problem: HotspotQrImport.Problem, action: () -> Unit) {
        try {
            action()
            fail("Expected $problem")
        } catch (error: HotspotQrImport.ImportException) {
            assertEquals(problem, error.problem)
        }
    }
}
