package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserConnectionAddressTest {
    @Test fun onlyTheApprovedDirectEndpointGetsTheShortInstructionalAddress() {
        listOf("https://carplay.tesair.co:8443", "https://carplay.tesair.co:8443/").forEach {
            assertEquals(BrowserConnectionAddress.ENTRY_URL, BrowserConnectionAddress.forInstructions(it))
        }
    }

    @Test fun unrelatedOrMalformedAddressesAreNeverReplacedByTheShortcut() {
        listOf("", "not a URL", "https://99.1.1.1:8443", "http://carplay.tesair.co:8443",
            "https://carplay.tesair.co", "https://carplay.tesair.co:443",
            "https://carplay.tesair.co:8443@other.example", "https://carplay.tesair.co.evil.example:8443",
            "https://carplay.tesair.co:8443/path", "https://carplay.tesair.co:8443?test=true",
            "https://carplay.tesair.co:8443#fragment", " https://carplay.tesair.co:8443").forEach {
            assertEquals(it, BrowserConnectionAddress.forInstructions(it))
        }
    }

    @Test fun absentTrustedAddressStaysAbsentDuringSetup() {
        assertNull(BrowserConnectionAddress.forInstructions(null))
    }

    @Test fun legacyDomainIsNeverAdvertisedThroughTheTesAirShortcut() {
        listOf("https://carplay.example.invalid:8443", "https://carplay.example.invalid:8443/").forEach {
            assertEquals(it, BrowserConnectionAddress.forInstructions(it))
        }
    }
}
