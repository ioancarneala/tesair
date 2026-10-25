package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.shilapi.xcertplay.browser.BrowserTlsDiagnostics
import com.shilapi.xcertplay.browser.TlsIdentity
import java.io.File
import java.io.FileOutputStream

/** A private, bounded snapshot; sharing remains an explicit diagnostic export. */
internal object BrowserAutomaticReport {
    private const val JOURNAL_TAIL_BYTES = 8_192L

    // The final snapshot of an old service can overlap the next service's first snapshot.
    @Synchronized
    fun save(context: Context): Boolean = try {
        val app = context.applicationContext
        if (!browserCarPlayEnabled(app)) false else {
            val report = report(app)
            // Deliberately outside the FileProvider's diagnostic-reports directory.
            val file = AtomicFile(File(app.filesDir, "automatic-diagnostics/latest.txt"))
            var stream: FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(report.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                true
            } catch (error: Exception) {
                runCatching { file.failWrite(stream) }
                throw error
            }
        }
    } catch (_: Exception) {
        // A full or unavailable volume must not interrupt the receiver or recovery.
        false
    }

    private fun report(context: Context): String {
        // Called on the health worker, never from a video callback or the main thread.
        AsyncDiagnosticLog.awaitIdle(1_000)
        @Suppress("DEPRECATION")
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "unknown"
        return buildString {
            appendLine("TesAir automatic diagnostic report")
            appendLine("Version: $version")
            appendLine("capturedAtEpochMs=${System.currentTimeMillis()}")
            appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine()
            appendLine("--- Current receiver state ---")
            appendLine(BrowserReliabilityDiagnostics.snapshot(context))
            appendLine()
            appendLine("--- HTTPS identity state ---")
            appendLine(BrowserTlsDiagnostics.format(TlsIdentity.diagnostics(),
                DiPlaySessionService.currentBrowserOutput()?.certificateDiagnostics))
            appendLine()
            appendLine("--- Recent own-app process exits ---")
            appendLine(ProcessExitDiagnostics.report(context))
            appendLine()
            appendLine("--- Last display negotiation ---")
            appendLine(DisplayDiagnosticSnapshot.report(context))
            appendLine()
            appendLine("--- Last startup ---")
            appendLine(StartupDiagnosticSnapshot.report(context))
            appendLine()
            appendLine("--- Browser reliability history (bounded tails) ---")
            appendLine(SessionLogFile.report(File(context.filesDir, "logs/reliability.log"),
                maxBytesPerFile = JOURNAL_TAIL_BYTES))
            appendLine()
            appendLine("--- Receiver history (bounded tails) ---")
            appendLine(SessionLogFile.report(File(context.filesDir, "logs/diplay.log"),
                maxBytesPerFile = JOURNAL_TAIL_BYTES))
        }.lineSequence().mapNotNull(DiagnosticRedactor::redact).joinToString("\n") + "\n"
    }
}
