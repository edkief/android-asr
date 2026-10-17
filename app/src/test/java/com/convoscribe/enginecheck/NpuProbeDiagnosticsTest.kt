package com.convoscribe.enginecheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the text the NPU probe attaches when its
 * process dies: [probeFailureMessage], [formatProbeDiagnostics]
 * and the `diagnostics:` block in [formatReport].
 */
class NpuProbeDiagnosticsTest {

    @Test
    fun probeFailureMessage_namesSelfExitStatus() {
        val msg = probeFailureMessage(ProbeExit(reason = "EXIT_SELF", status = 255, description = null))
        assertEquals("The NPU runtime gave up and exited (status 255) while starting", msg)
    }

    @Test
    fun probeFailureMessage_withoutExitInfo_isGeneric() {
        assertEquals("The NPU runtime closed its process while starting", probeFailureMessage(null))
    }

    @Test
    fun formatProbeDiagnostics_includesExitDspNodesAndLog() {
        val text = formatProbeDiagnostics(
            pid = 7817,
            exit = ProbeExit(reason = "EXIT_SELF", status = 255, description = "exit"),
            dspNodes = listOf("/dev/fastrpc-cdsp" to "not visible"),
            logLines = listOf("W sherpa-onnx: Failed to call deviceCreate")
        )
        assertTrue(text, text.contains("probePid: 7817"))
        assertTrue(text, text.contains("exit: EXIT_SELF status=255 (exit)"))
        assertTrue(text, text.contains("dsp /dev/fastrpc-cdsp: not visible"))
        assertTrue(text, text.contains("probeLog:\nW sherpa-onnx: Failed to call deviceCreate"))
    }

    @Test
    fun formatProbeDiagnostics_unknownPidAndNoLog() {
        val text = formatProbeDiagnostics(pid = 0, exit = null, dspNodes = emptyList(), logLines = emptyList())
        assertTrue(text, text.contains("probePid: unknown"))
        assertTrue(text, text.contains("exit: unknown"))
        assertTrue(text, text.endsWith("probeLog: (none)"))
    }

    @Test
    fun formatReport_indentsDiagnosticsUnderFailedResult() {
        val info = DeviceInfo(
            manufacturer = "Onyx",
            model = "Air6C",
            socManufacturer = "QTI",
            socModel = "SM7225",
            hardware = "qcom",
            androidVersion = "15",
            cdspLibraryPresent = true,
            htpSkelLibs = emptyList(),
            freeStorageBytes = 2_000_000_000L
        )
        val result = EngineCheckResult(
            engine = "npu",
            initMs = 0L,
            firstRun = false,
            audioMs = 0L,
            processMs = 0L,
            text = "",
            error = "boom",
            diagnostics = "probePid: 1\nexit: unknown"
        )
        val report = formatReport(info, listOf(result))
        val expected = "verdict: FAILED\nerror: boom\ndiagnostics:\n  probePid: 1\n  exit: unknown\n"
        assertTrue(report, report.contains(expected))
    }
}
