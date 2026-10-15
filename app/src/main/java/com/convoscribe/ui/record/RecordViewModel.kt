package com.convoscribe.ui.record

import androidx.lifecycle.ViewModel
import com.convoscribe.recording.RecordingController
import com.convoscribe.recording.RecordingState
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin ViewModel over [RecordingController] for the recording screen.
 * The controller already owns its own [RecordingController.state]
 * `StateFlow` and exposes every operation the UI needs; this class
 * adds:
 *
 *  - the [formatElapsed] formatter (top-level function so a
 *    no-Android unit test can cover it without instantiating the
 *    controller);
 *  - the [onNavigatedToSession] callback that clears
 *    [RecordingState.lastCompletedSessionId] once the navigation
 *    has fired.
 *
 * Kept deliberately narrow — no business logic, no coroutines,
 * just the bits the screen calls. Constructed by
 * [com.convoscribe.ui.AppNavHost]'s `viewModelFactory` reading
 * `AppContainer.recordingController` so the singleton lives across
 * configuration changes (and is the same instance the service
 * drives).
 */
class RecordViewModel(
    controller: RecordingController
) : ViewModel() {

    private val recordingController = controller

    /** Latest state snapshot of the active recording session. */
    val state: StateFlow<RecordingState> = controller.state

    /**
     * Run after navigation has consumed
     * [RecordingState.lastCompletedSessionId] so the same id is not
     * re-handled on the next recomposition. Safe to call when
     * nothing is pending.
     */
    fun onNavigatedToSession() {
        recordingController.consumeCompleted()
    }
}

/**
 * Format an elapsed time as the recording screen displays it.
 *
 *  - `mm:ss` (always two digits each) under one hour;
 *  - `h:mm:ss` (single-digit hours, padded minutes/seconds) once
 *    the elapsed time crosses one hour;
 *  - clamped to `0` for negative input so a transient glitch in
 *    the counter cannot throw.
 *
 * Locale is pinned to `Locale.US` so the colon never flips to a
 * comma on a European locale. Used in the body of the screen and
 * is also the formatter the recording-screen's notification text
 * indirectly relies on (separate function for the notification so
 * the two strings can evolve independently).
 *
 * Top-level on purpose: the unit test for this formatter must not
 * touch the Android framework, only pure JUnit.
 */
fun formatElapsed(ms: Long): String {
    val safe = max(0L, ms)
    val totalSeconds = safe / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
