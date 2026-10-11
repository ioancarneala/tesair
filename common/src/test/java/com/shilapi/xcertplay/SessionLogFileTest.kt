package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SessionLogFileTest {
    @Test fun aRedactedFirstHeaderStillCreatesAnEmptyUsableLog() = withFolder { folder ->
        val file = folder.resolve("logs/diplay.log")
        SessionLogFile(file).use {
            it.beginSession("token=private-header")
            assertTrue(file.isFile)
            assertEquals("", file.readText())
            it.append("session ready")
        }
        assertEquals("session ready\n", file.readText())
    }

    @Test fun repeatedActivityAndProcessStartsPreserveThePreviousDrive() = withFolder { folder ->
        val file = folder.resolve("diplay.log")
        SessionLogFile(file).use {
            it.beginSession("session started at=1000")
            it.append("connection lost event=wifi_disconnected")
        }
        repeat(30) { restart ->
            SessionLogFile(file).use {
                it.beginSession("session restarted at=${2000 + restart}")
                it.append("session active=true")
            }
        }
        val report = SessionLogFile.report(file)
        assertTrue(report.contains("connection lost event=wifi_disconnected"))
        assertEquals(30, report.lineSequence().count { it.startsWith("session restarted") })
        assertEquals(1, folder.listFiles()!!.size)
    }

    @Test fun byteRotationIsBoundedForMultibyteTextAndReturnsChronologicalHistory() = withFolder { folder ->
        val file = folder.resolve("diplay.log")
        SessionLogFile(file).use {
            repeat(2_600) { index -> it.append("entry=$index " + "é".repeat(675)) }
        }
        assertTrue(folder.listFiles()!!.size > 1)
        assertTrue(folder.listFiles()!!.all { it.length() <= SessionLogFile.MAX_BYTES })
        val entries = SessionLogFile.report(file).lineSequence().filter { it.startsWith("entry=") }
            .map { it.substringAfter("entry=").substringBefore(' ').toInt() }.toList()
        assertEquals(entries.sorted(), entries)
        assertEquals(2599, entries.last())
    }

    @Test fun journalsInTheSameDirectoryDoNotRotateOrExportEachOthersEvidence() = withFolder { folder ->
        val session = folder.resolve("diplay.log")
        val reliability = folder.resolve("reliability.log")
        repeat(3) { index ->
            SessionLogFile(session).use { it.reset("transport session=$index") }
            SessionLogFile(reliability).use { it.reset("reliability session=$index") }
        }
        val transportReport = SessionLogFile.report(session)
        val reliabilityReport = SessionLogFile.report(reliability)
        assertEquals(3, transportReport.lineSequence().count { it.startsWith("transport session=") })
        assertEquals(3, reliabilityReport.lineSequence().count { it.startsWith("reliability session=") })
        assertFalse(transportReport.contains("reliability"))
        assertFalse(reliabilityReport.contains("transport"))
    }

    @Test fun legacyOversizedFilesAreBoundedAndClippedSecretLinesAreNeverExported() = withFolder { folder ->
        val file = folder.resolve("diplay.log")
        file.writeText("password=" + "sensitive".repeat(SessionLogFile.MAX_BYTES.toInt()) +
            "\nconnected peer=192.168.1.5\ntoken=do-not-export\n")
        val report = SessionLogFile.report(file)
        assertTrue(report.contains("connected peer=[ip]"))
        assertFalse(report.contains("sensitive"))
        assertFalse(report.contains("do-not-export"))
        assertFalse(report.contains("192.168.1.5"))
        assertTrue(report.length < 200)
    }

    @Test fun closedWritersAndUnavailableStorageDoNotCrashOrAlterEvidence() = withFolder { folder ->
        val file = folder.resolve("diplay.log")
        val closed = SessionLogFile(file)
        closed.beginSession("before close")
        closed.close()
        closed.append("after close")
        closed.reset("reset after close")
        assertEquals("before close\n", file.readText())
        val unavailable = SessionLogFile(file.resolve("diplay.log"))
        unavailable.beginSession("cannot create child of a file")
        unavailable.reset("cannot rotate child of a file")
        assertEquals("", SessionLogFile.report(unavailable.file))
    }

    @Test fun compactReportCapsEveryJournalAndDropsClippedCredentialLines() = withFolder { folder ->
        val file = folder.resolve("reliability.log")
        val archive = folder.resolve("reliability-previous.log")
        for (target in listOf(archive, file)) {
            target.writeText("password=" + "private-fragment".repeat(100) + "\nlatest event=connected\n")
        }
        val compact = SessionLogFile.report(file, maxBytesPerFile = 64)
        assertEquals(2, compact.lineSequence().count { it == "latest event=connected" })
        assertFalse(compact.contains("private-fragment"))
        assertTrue(compact.toByteArray().size < 200)
    }

    private fun withFolder(test: (java.io.File) -> Unit) {
        val folder = Files.createTempDirectory("diplay-session-history").toFile()
        try { test(folder) } finally { folder.deleteRecursively() }
    }
}
