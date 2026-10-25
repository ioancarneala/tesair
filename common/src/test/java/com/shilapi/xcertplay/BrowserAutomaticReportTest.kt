package com.shilapi.xcertplay

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class BrowserAutomaticReportTest {
    private lateinit var folder: File
    private lateinit var app: Context
    private val savedFile get() = File(folder, "automatic-diagnostics/latest.txt")

    @Before fun setup() {
        folder = Files.createTempDirectory("browser-automatic-report").toFile()
        app = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = "com.shihab.diplay.browsertest"
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = folder
            override fun getExternalFilesDir(type: String?): File? =
                throw AssertionError("Automatic reports must not use external storage")
        }
    }

    @After fun cleanup() {
        AsyncDiagnosticLog.awaitIdle(2_000)
        folder.deleteRecursively()
    }

    @Test fun savesPrivateReportWithoutAnActivityAndRedactsLegacyJournalContents() {
        log("reliability.log").writeText("viewer_close reason=read_io code=0\npassword=synthetic-secret\n")
        log("diplay.log").writeText("session ready peer=10.11.12.13\ntoken=synthetic-token\n")
        StartupDiagnosticSnapshot.received(app, true)
        StartupDiagnosticSnapshot.launchResult(app, service = true)

        assertTrue(BrowserAutomaticReport.save(app))

        val report = savedFile.readText()
        assertTrue(report.contains("TesAir automatic diagnostic report"))
        assertTrue(report.contains("capturedAtEpochMs="))
        assertTrue(report.contains("API 31"))
        assertTrue(report.contains("receiverRequested="))
        assertTrue(report.contains("--- HTTPS identity state ---"))
        assertTrue(report.contains("tls trusted="))
        assertTrue(report.contains("notAfterMs="))
        assertTrue(report.contains("Process exits available="))
        assertTrue(report.contains("startForegroundService-returned"))
        assertTrue(report.contains("viewer_close reason=read_io"))
        assertTrue(report.contains("session ready peer=[ip]"))
        assertFalse(report.contains("synthetic-secret"))
        assertFalse(report.contains("synthetic-token"))
        assertFalse(report.contains("10.11.12.13"))
        assertFalse(File(folder, "diagnostic-reports").exists())
        assertEquals(listOf("latest.txt"), savedFile.parentFile!!.listFiles()!!.map { it.name })
    }

    @Test fun replacesThePreviousSnapshotInsteadOfAccumulatingReports() {
        log("reliability.log").writeText("first_snapshot_evidence\n")
        assertTrue(BrowserAutomaticReport.save(app))
        log("reliability.log").writeText("second_snapshot_evidence\n")

        assertTrue(BrowserAutomaticReport.save(app))

        val report = savedFile.readText()
        assertTrue(report.contains("second_snapshot_evidence"))
        assertFalse(report.contains("first_snapshot_evidence"))
        assertEquals(listOf("latest.txt"), savedFile.parentFile!!.listFiles()!!.map { it.name })
    }

    @Test fun includesOnlyBoundedTailsOfBothJournals() {
        for (name in listOf("diplay.log", "reliability.log")) {
            log(name).writeText("old_evidence_must_be_clipped\n" +
                "routine_event captured=true\n".repeat(8_000) + "latest_evidence_${name}\n")
        }

        assertTrue(BrowserAutomaticReport.save(app))

        val report = savedFile.readText()
        assertTrue(report.contains("latest_evidence_diplay.log"))
        assertTrue(report.contains("latest_evidence_reliability.log"))
        assertFalse(report.contains("old_evidence_must_be_clipped"))
        assertTrue(savedFile.length() < 24 * 1024)
    }

    @Test fun failedAtomicWritePreservesThePreviousCompletedSnapshot() {
        assertTrue(BrowserAutomaticReport.save(app))
        val previous = savedFile.readBytes()
        // AtomicFile's staging path is unavailable, while the completed report remains readable.
        val blockedStaging = File(savedFile.path + ".new")
        assertTrue(blockedStaging.mkdir())
        File(blockedStaging, "occupied").writeText("synthetic storage failure")

        assertFalse(BrowserAutomaticReport.save(app))

        assertArrayEquals(previous, savedFile.readBytes())
    }

    @Test fun unavailablePrivateStorageFailsWithoutInterruptingTheCaller() {
        File(folder, "automatic-diagnostics").writeText("synthetic blocked directory")
        assertFalse(BrowserAutomaticReport.save(app))
        assertFalse(savedFile.exists())
    }

    @Test fun otherBuildsDoNotCreateAutomaticReports() {
        val normalApp = object : ContextWrapper(app) {
            override fun getPackageName() = "com.shihab.diplay"
            override fun getApplicationContext(): Context = this
        }

        assertFalse(BrowserAutomaticReport.save(normalApp))

        assertFalse(savedFile.exists())
    }

    private fun log(name: String): File = File(folder, "logs/$name").also {
        it.parentFile!!.mkdirs()
    }
}
