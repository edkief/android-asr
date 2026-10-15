package com.convoscribe.enginecheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [formatReport], [storageWarning] and the
 * [verdict] helper. No Android, no recognizer — these are the
 * bits the engine-check screen can preview without ever touching
 * a real device.
 */
class ReportTest {

    /**
     * Test from the spec: `formatReport` contains `socModel:
     * QCS6690` for such a DeviceInfo, `RTF: 0.20` and
     * `freeStorage: 2.5 GB` for 2 500 000 000 free bytes.
     */
    @Test
    fun formatReport_containsSocModelRtfAndFreeStorage() {
        val info = DeviceInfo(
            manufacturer = "Qualcomm",
            model = "Q-6690",
            socManufacturer = "Qualcomm",
            socModel = "QCS6690",
            hardware = "qcs6690",
            androidVersion = "14",
            cdspLibraryPresent = true,
            htpSkelLibs = listOf("libQnnHtpV75Skel.so", "libQnnHtpV79Skel.so"),
            freeStorageBytes = 2_500_000_000L
        )
        val results = listOf(
            EngineCheckResult(
                engine = "npu",
                initMs = 1_500L,
                firstRun = false,
                audioMs = 2_000L,
                processMs = 400L,
                text = "Hello world"
            )
        )
        val report = formatReport(info, results)
        assertTrue(
            "report should mention socModel: QCS6690 but was:\n$report",
            report.contains("socModel: QCS6690")
        )
        assertTrue(
            "report should contain RTF: 0.20 but was:\n$report",
            report.contains("RTF: 0.20")
        )
        assertTrue(
            "report should contain freeStorage: 2.5 GB but was:\n$report",
            report.contains("freeStorage: 2.5 GB")
        )
    }

    /**
     * An empty results list still produces the device snapshot;
     * the report is the device card minus any per-engine block.
     */
    @Test
    fun formatReport_noResults_stillEmitsDeviceSection() {
        val info = DeviceInfo(
            manufacturer = "Qualcomm",
            model = "Q-6690",
            socManufacturer = "Qualcomm",
            socModel = "QCS6690",
            hardware = "qcs6690",
            androidVersion = "14",
            cdspLibraryPresent = true,
            htpSkelLibs = emptyList(),
            freeStorageBytes = 2_500_000_000L
        )
        val report = formatReport(info, emptyList())
        assertTrue(report.contains("model: Q-6690"))
        assertTrue(report.contains("htpSkelLibs: "))
        // No engine block.
        assertTrue(!report.contains("engine:"))
    }

    /**
     * A result with `error` set renders the error line and a
     * `verdict: FAILED` line in place of the OK text block.
     */
    @Test
    fun formatReport_errorResult_isRenderedAsVerdictFailed() {
        val info = DeviceInfo(
            manufacturer = "Qualcomm",
            model = "Q-6690",
            socManufacturer = "Qualcomm",
            socModel = "QCS6690",
            hardware = "qcs6690",
            androidVersion = "14",
            cdspLibraryPresent = false,
            htpSkelLibs = emptyList(),
            freeStorageBytes = 1_000_000_000L
        )
        val results = listOf(
            EngineCheckResult(
                engine = "npu",
                initMs = 0L,
                firstRun = false,
                audioMs = 2_000L,
                processMs = 0L,
                text = "",
                error = "NPU failed to start"
            )
        )
        val report = formatReport(info, results)
        assertTrue(report.contains("verdict: FAILED"))
        assertTrue(report.contains("error: NPU failed to start"))
        assertTrue(!report.contains("text:"))
    }

    /**
     * Test from the spec: `storageWarning(1_000_000_000,
     * npuPrepared = false)` is non-null;
     * `storageWarning(1_000_000_000, true)` and
     * `storageWarning(2_000_000_000, false)` are null.
     */
    @Test
    fun storageWarning_thresholds() {
        val lowUnprepared = storageWarning(1_000_000_000L, npuPrepared = false)
        assertNotNull("low + unprepared should warn", lowUnprepared)
        assertTrue(lowUnprepared!!.contains("1.5 GB"))

        val lowPrepared = storageWarning(1_000_000_000L, npuPrepared = true)
        assertNull("low + prepared should not warn", lowPrepared)

        val highUnprepared = storageWarning(2_000_000_000L, npuPrepared = false)
        assertNull("high + unprepared should not warn", highUnprepared)
    }

    /**
     * Exactly at 1.5 GB the warning is *not* shown — the
     * threshold is `<`, not `<=`, so a free-storage reading of
     * 1.5 GB does not trigger the alert.
     */
    @Test
    fun storageWarning_at1500MillionBytes_isNull() {
        assertNull(storageWarning(1_500_000_000L, npuPrepared = false))
    }

    /**
     * The RTF field always carries two decimals, regardless of
     * the underlying value (an integer RTF becomes `1.00`).
     */
    @Test
    fun formatReport_rtfIsAlwaysTwoDecimals() {
        val info = DeviceInfo(
            manufacturer = "Qualcomm",
            model = "Q-6690",
            socManufacturer = "Qualcomm",
            socModel = "QCS6690",
            hardware = "qcs6690",
            androidVersion = "14",
            cdspLibraryPresent = true,
            htpSkelLibs = emptyList(),
            freeStorageBytes = 2_500_000_000L
        )
        val results = listOf(
            EngineCheckResult(
                engine = "cpu",
                initMs = 50L,
                firstRun = false,
                audioMs = 1_000L,
                processMs = 1_000L,
                text = ""
            )
        )
        val report = formatReport(info, results)
        assertTrue(report.contains("RTF: 1.00"))
    }

    /**
     * htpSkelLibs renders as a comma-separated list in input
     * order (after the spec's `sorted()`). Empty lists render
     * as a bare trailing colon.
     */
    @Test
    fun formatReport_htpSkelLibsIsCommaSeparated() {
        val info = DeviceInfo(
            manufacturer = "Qualcomm",
            model = "Q-6690",
            socManufacturer = "Qualcomm",
            socModel = "QCS6690",
            hardware = "qcs6690",
            androidVersion = "14",
            cdspLibraryPresent = true,
            htpSkelLibs = listOf("libQnnHtpV75Skel.so", "libQnnHtpV79Skel.so"),
            freeStorageBytes = 2_500_000_000L
        )
        val report = formatReport(info, emptyList())
        assertTrue(
            report.contains("htpSkelLibs: libQnnHtpV75Skel.so,libQnnHtpV79Skel.so")
        )
    }

    /**
     * Multiple results are rendered in input order — the spec
     * does not say one engine comes before the other, but the
     * tests can still pin down the order so a refactor does not
     * silently shuffle them.
     */
    @Test
    fun formatReport_resultsAppearInOrder() {
        val info = DeviceInfo(
            manufacturer = "Qualcomm",
            model = "Q-6690",
            socManufacturer = "Qualcomm",
            socModel = "QCS6690",
            hardware = "qcs6690",
            androidVersion = "14",
            cdspLibraryPresent = true,
            htpSkelLibs = emptyList(),
            freeStorageBytes = 2_500_000_000L
        )
        val results = listOf(
            EngineCheckResult("npu", 0L, false, 1_000L, 100L, "npu text"),
            EngineCheckResult("cpu", 0L, false, 1_000L, 200L, "cpu text")
        )
        val report = formatReport(info, results)
        val npuIdx = report.indexOf("engine: npu")
        val cpuIdx = report.indexOf("engine: cpu")
        assertTrue("npu must appear before cpu", npuIdx in 0 until cpuIdx)
        assertTrue(report.contains("text: npu text"))
        assertTrue(report.contains("text: cpu text"))
    }

    @Test
    fun verdict_errorShortCircuitsBeforeRtf() {
        val r = EngineCheckResult(
            engine = "npu",
            initMs = 0L,
            firstRun = false,
            audioMs = 1_000L,
            processMs = 0L,
            text = "",
            error = "boom"
        )
        assertEquals("FAILED", verdict(r))
    }
}
