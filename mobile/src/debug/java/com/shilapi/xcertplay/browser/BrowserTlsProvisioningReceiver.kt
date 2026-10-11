package com.shilapi.xcertplay.browser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** USB-debugging provisioning only; ordinary apps do not hold the DUMP permission. */
class BrowserTlsProvisioningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!context.packageName.endsWith(".browsertest")) return
        val action = intent.action
        if (action !in setOf(
                "com.shihab.diplay.browsertest.TLS_CSR",
                "com.shihab.diplay.browsertest.TLS_INSTALL",
                "com.shihab.diplay.browsertest.TLS_STATUS",
            )) return
        val application = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                when (action) {
                    "com.shihab.diplay.browsertest.TLS_CSR" -> {
                        TlsIdentity.writeCertificateRequest(File(application.filesDir, "browser-tls.csr.pem"))
                        pending.resultData = "Public certificate request ready"
                    }
                    "com.shihab.diplay.browsertest.TLS_INSTALL" -> {
                        TlsIdentity.installCertificateChain(File(application.filesDir, "browser-tls-chain.pem"))
                        pending.resultData = "Trusted certificate installed; restart DiPlay to use it"
                    }
                    "com.shihab.diplay.browsertest.TLS_STATUS" -> {
                        pending.resultData = "trusted=${TlsIdentity.hasTrustedCertificate()} expires=${TlsIdentity.trustedCertificateNotAfter()}"
                    }
                }
                pending.resultCode = 1
            } catch (error: Exception) {
                // Do not expose certificate material or private paths in broadcasts/logs.
                pending.resultCode = -1
                pending.resultData = "Certificate provisioning failed: ${error.javaClass.simpleName}"
            } finally {
                pending.finish()
            }
        }, "browser-tls-provisioning").start()
    }
}
