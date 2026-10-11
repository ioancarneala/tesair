package com.shilapi.xcertplay

/** Display-only shortcut. Receiver endpoints and certificate validation keep the direct address. */
internal object BrowserConnectionAddress {
    const val ENTRY_URL = "https://go.tesair.co"
    private const val DIRECT_URL = "https://carplay.tesair.co:8443"

    // Callers select an available trusted URL first; a pending or untrusted address stays unchanged.
    fun forInstructions(trustedUrl: String?): String? = when (trustedUrl) {
        DIRECT_URL, "$DIRECT_URL/" -> ENTRY_URL
        else -> trustedUrl
    }
}
