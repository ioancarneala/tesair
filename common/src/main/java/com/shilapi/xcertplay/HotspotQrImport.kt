package com.shilapi.xcertplay

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.client.result.WifiResultParser
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

/** Deliberately has no generated toString: hotspot credentials must not enter diagnostic logs. */
internal class HotspotQrCredentials(val ssid: String, val password: String, val security: ManualHotspotSecurity) {
    override fun toString() = "HotspotQrCredentials(redacted)"
}

/** Imports only a user-selected local image; it never saves credentials or changes the hotspot. */
internal object HotspotQrImport {
    const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_SOURCE_PIXELS = 40_000_000L
    private const val MAX_DECODE_EDGE = 2048

    enum class Problem { IMAGE, NO_WIFI_QR, NETWORK }
    class ImportException(val problem: Problem) : Exception(problem.name)

    fun read(resolver: ContentResolver, uri: Uri): HotspotQrCredentials {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw ImportException(Problem.IMAGE)
        return try {
            val bytes = resolver.openInputStream(uri)?.use(::readBounded)
                ?: throw ImportException(Problem.IMAGE)
            decode(bytes)
        } catch (error: ImportException) {
            throw error
        } catch (_: Exception) {
            throw ImportException(Problem.IMAGE)
        }
    }

    internal fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw ImportException(Problem.IMAGE)
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) throw ImportException(Problem.IMAGE)
            if (output.size() + count > MAX_IMAGE_BYTES) throw ImportException(Problem.IMAGE)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun decode(bytes: ByteArray): HotspotQrCredentials {
        if (bytes.size > MAX_IMAGE_BYTES) throw ImportException(Problem.IMAGE)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) {
            throw ImportException(Problem.IMAGE)
        }
        var sample = 1
        while (maxOf(width, height) / sample > MAX_DECODE_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ImportException(Problem.IMAGE)
        val source = try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        var rotated: com.google.zxing.LuminanceSource = source
        repeat(4) {
            for (candidate in listOf(rotated, rotated.invert())) {
                if (Thread.currentThread().isInterrupted) throw ImportException(Problem.IMAGE)
                val result = try {
                    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(candidate)), hints)
                } catch (_: com.google.zxing.ReaderException) {
                    null
                }
                if (result != null) return parse(result.text)
            }
            rotated = rotated.rotateCounterClockwise()
        }
        throw ImportException(Problem.NO_WIFI_QR)
    }

    internal fun parse(text: String): HotspotQrCredentials {
        if (text.length > 4096 || !text.startsWith("WIFI:")) throw ImportException(Problem.NO_WIFI_QR)
        val result = WifiResultParser().parse(Result(text, null, null, BarcodeFormat.QR_CODE))
            ?: throw ImportException(Problem.NO_WIFI_QR)
        val security = result.networkEncryption.uppercase(Locale.ROOT)
        val password = result.password.orEmpty()
        val open = security == "NOPASS"
        if (security !in setOf("NOPASS", "WPA", "WPA2", "WPA3", "SAE") ||
            !result.identity.isNullOrEmpty() || !result.anonymousIdentity.isNullOrEmpty() ||
            !result.eapMethod.isNullOrEmpty() || !result.phase2Method.isNullOrEmpty() ||
            open != password.isEmpty() || ManualHotspotValidation.error(result.ssid, password) != null) {
            throw ImportException(Problem.NETWORK)
        }
        return HotspotQrCredentials(result.ssid, password, when (security) {
            "NOPASS" -> ManualHotspotSecurity.OPEN
            "WPA3", "SAE" -> ManualHotspotSecurity.WPA3
            else -> ManualHotspotSecurity.WPA2
        })
    }
}
