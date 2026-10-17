package com.convoscribe.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import com.convoscribe.ConvoApp
import com.convoscribe.TAG
import com.convoscribe.audio.WavReader
import com.convoscribe.enginecheck.EngineCheckRunner
import com.convoscribe.enginecheck.NpuProbeProtocol
import com.convoscribe.enginecheck.toBundle
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the NPU engine check in the `:npuprobe` process (see the
 * manifest), so a native `exit()` from the QNN runtime kills only
 * this process and not the app. The main process talks to it
 * through [com.convoscribe.enginecheck.NpuProbeClient] with the
 * [NpuProbeProtocol] messages:
 *
 *  1. client sends [NpuProbeProtocol.MSG_RUN] with the sample WAV path;
 *  2. service replies [NpuProbeProtocol.MSG_STARTED] with its pid
 *     (so the client can pull this process's log lines if it dies);
 *  3. service replies [NpuProbeProtocol.MSG_RESULT] with the
 *     [com.convoscribe.enginecheck.EngineCheckResult] as a Bundle.
 *
 * If the process dies between 2 and 3 the client sees the binder
 * die instead of a result.
 */
class NpuProbeService : Service() {

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == NpuProbeProtocol.MSG_RUN) handleRun(msg)
            true
        }
    )

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handleRun(msg: Message) {
        val replyTo = msg.replyTo ?: return
        val samplePath = msg.data.getString(NpuProbeProtocol.KEY_SAMPLE_FILE) ?: return
        val pid = Process.myPid()
        runCatching { replyTo.send(Message.obtain(null, NpuProbeProtocol.MSG_STARTED, pid, 0)) }
        Log.i(TAG, "npu probe: starting NPU check in pid $pid")
        thread(name = "npu-probe") {
            val container = (application as ConvoApp).container
            val runner = EngineCheckRunner(
                factory = container.transcriberFactoryFor("npu"),
                loadSamples = { WavReader.readMono16k(File(samplePath)) },
                clock = { System.nanoTime() / 1_000_000L }
            )
            val result = runner.run("npu")
            Log.i(TAG, "npu probe: finished, error=${result.error}")
            val reply = Message.obtain(null, NpuProbeProtocol.MSG_RESULT).apply {
                data = result.toBundle()
            }
            runCatching { replyTo.send(reply) }
        }
    }
}
