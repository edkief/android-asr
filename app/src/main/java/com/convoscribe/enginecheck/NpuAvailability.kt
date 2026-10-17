package com.convoscribe.enginecheck

import android.content.SharedPreferences

/**
 * Remembers whether the NPU probe has seen the QNN runtime kill
 * its process on this device.
 *
 * sherpa-onnx's QNN backend calls `exit()` when it cannot create
 * the HTP device (seen on the Boox Note Air 6C, whose SoC ships
 * with the NSP fused off and no CDSP), so starting the NPU in the
 * app process closes the whole app with no exception to catch.
 * The NPU probe runs in
 * its own process and records the outcome here; the main
 * [com.convoscribe.engine.sherpa.SherpaTranscriberFactory] reads
 * [blockedReason] and refuses to start the NPU instead of dying.
 *
 * Only the main process writes this store, so the
 * SharedPreferences multi-process caveat does not apply.
 */
class NpuAvailability(private val prefs: SharedPreferences) {

    /**
     * Short reason the NPU must not be started in-process (e.g.
     * `"the NPU runtime exited with status 255 during the last
     * engine check"`), or `null` when no failure is on record.
     */
    fun blockedReason(): String? = prefs.getString(KEY_BLOCKED_REASON, null)

    /**
     * `true` once an NPU probe ran cleanly on this device and no failure
     * has been seen since. The default ASR engine is NPU only then.
     */
    fun isVerified(): Boolean = prefs.getBoolean(KEY_VERIFIED, false)

    /** Record that the probe process died while starting the NPU. */
    fun markFailed(reason: String) {
        prefs.edit().putString(KEY_BLOCKED_REASON, reason).putBoolean(KEY_VERIFIED, false).apply()
    }

    /** Record a clean NPU probe run: clears any failure and marks the NPU verified. */
    fun markVerified() {
        prefs.edit().remove(KEY_BLOCKED_REASON).putBoolean(KEY_VERIFIED, true).apply()
    }

    companion object {
        const val PREFS_NAME = "npu_availability"
        private const val KEY_BLOCKED_REASON = "blockedReason"
        private const val KEY_VERIFIED = "verified"
    }
}
