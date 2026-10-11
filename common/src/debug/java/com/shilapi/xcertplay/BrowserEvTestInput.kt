package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import com.shilapi.xcertplay.transport.VehicleStatusProvider
import com.shilapi.xcertplay.transport.VehicleStatusSnapshot
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Short-lived, app-private ADB input for testing the browser receiver's EV data path. */
internal object BrowserEvTestInput {
    private const val MAX_BYTES = 1_024
    private const val SAMPLE_VALIDITY_MILLIS = 120_000L
    private const val PROVIDER_LIFETIME_MILLIS = 600_000L
    private const val MAX_CHARGE_WH = 1_000_000L

    fun open(context: Context): VehicleStatusProvider? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            !context.packageName.endsWith(".browsertest")) return null
        val file = File(context.cacheDir, "ev-battery-test.json")
        val startedAt = SystemClock.elapsedRealtime()
        val provider = VehicleStatusProvider {
            val age = SystemClock.elapsedRealtime() - startedAt
            if (age < 0 || age >= PROVIDER_LIFETIME_MILLIS) null else read(file)
        }
        return provider.takeIf { it.snapshot() != null }
    }

    private fun read(file: File): VehicleStatusSnapshot? = runCatching {
        if (!file.isFile) return null
        // Bound the read itself: a writer may replace or grow the file after a length check.
        val bytes = ByteArray(MAX_BYTES + 1)
        val length = file.inputStream().use { input ->
            var count = 0
            while (count < bytes.size) {
                val next = input.read(bytes, count, bytes.size - count)
                if (next <= 0) break
                count += next
            }
            count
        }
        if (length == 0 || length > MAX_BYTES) return null
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length)).toString()
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            val seen = mutableSetOf<String>()
            var expiresAt: Long? = null
            var percent: Double? = null
            var range: Long? = null
            var maxRange: Long? = null
            var currentCharge: Long? = null
            var maxCharge: Long? = null
            var charging: Boolean? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(seen.add(name))
                when (name) {
                    "expiresAtMs" -> expiresAt = reader.integer()
                    "batteryPercent" -> {
                        require(reader.peek() == JsonToken.NUMBER)
                        percent = reader.nextString().toDouble().also { require(it.isFinite() && it in 0.0..100.0) }
                    }
                    "rangeKm" -> range = reader.integer()
                    "maxRangeKm" -> maxRange = reader.integer()
                    "currentChargeWh" -> currentCharge = reader.optionalInteger()
                    "maxChargeWh" -> maxCharge = reader.optionalInteger()
                    "charging" -> {
                        require(reader.peek() == JsonToken.BOOLEAN)
                        charging = reader.nextBoolean()
                    }
                    else -> error("Unknown EV test input field")
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            val now = System.currentTimeMillis()
            val expires = requireNotNull(expiresAt)
            require(expires > now && expires - now <= SAMPLE_VALIDITY_MILLIS)
            val rangeKm = requireNotNull(range).also { require(it in 0..2_000L) }
            val maxRangeKm = requireNotNull(maxRange).also { require(it in 1..2_000L && it >= rangeKm) }
            currentCharge?.let { require(it in 0..MAX_CHARGE_WH) }
            maxCharge?.let { require(it in 1..MAX_CHARGE_WH) }
            if (currentCharge != null && maxCharge != null) require(currentCharge <= maxCharge)
            val batteryPercent = requireNotNull(percent)
            VehicleStatusSnapshot(rangeKm = rangeKm.toInt(), rangeWarning = batteryPercent <= 20.0,
                batteryPercent = batteryPercent, currentChargeWh = currentCharge, maxChargeWh = maxCharge,
                maxRangeKm = maxRangeKm.toInt(), charging = requireNotNull(charging))
        }
    }.getOrNull()

    private fun JsonReader.integer(): Long {
        require(peek() == JsonToken.NUMBER)
        return nextString().toLong()
    }

    private fun JsonReader.optionalInteger(): Long? = if (peek() == JsonToken.NULL) {
        nextNull()
        null
    } else integer()
}
