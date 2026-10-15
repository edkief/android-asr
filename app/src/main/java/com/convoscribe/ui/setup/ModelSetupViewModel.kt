package com.convoscribe.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.models.DownloadEvent
import com.convoscribe.models.ModelBundle
import com.convoscribe.models.ModelDownloadException
import com.convoscribe.models.ModelDownloads
import com.convoscribe.models.ModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State shown by the first-run model download screen. [totalBytes]
 * is fixed at construction (sum of every missing bundle) so the UI
 * can render an idle bar before the user taps Download. [doneBytes]
 * is a running total reported by the downloader; the ViewModel does
 * its own arithmetic so the UI does not have to track it across
 * files.
 */
data class SetupUiState(
    val totalBytes: Long,
    val doneBytes: Long = 0L,
    val running: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false
)

/**
 * First-run model download ViewModel. Owns one [SetupUiState] flow
 * that the [ModelSetupScreen] collects. [start] is idempotent while a
 * download is in flight; after an error, calling [start] again clears
 * the error and runs once more.
 */
class ModelSetupViewModel(
    private val downloader: ModelDownloads,
    private val store: ModelStore,
    private val bundles: List<ModelBundle>
) : ViewModel() {

    private val _state = MutableStateFlow(
        // The on-screen "About N MB will be downloaded" string
        // reflects what lands on disk, not the compressed archive
        // body the network pushes. `totalBytes` sums every
        // declared file in the missing bundles (the same value
        // individual files are tracked against by the downloader),
        // and for archive-based bundles equals the unpacked file
        // sizes — closer to "this is how much space you'll free
        // up if you delete it later" than `downloadBytes`.
        SetupUiState(totalBytes = store.missing(bundles).sumOf { it.totalBytes })
    )
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    /**
     * Launch a download for every bundle in [ModelStore.missing]. A
     * second call while [running] is a no-op so the UI cannot
     * accidentally start two parallel OkHttp calls.
     */
    fun start() {
        if (_state.value.running) return
        val toFetch = store.missing(bundles)
        if (toFetch.isEmpty()) {
            // Nothing to do; mark finished so the screen pops itself.
            _state.value = _state.value.copy(
                running = false,
                error = null,
                finished = true
            )
            return
        }
        _state.value = _state.value.copy(
            running = true,
            error = null,
            doneBytes = 0L
        )
        viewModelScope.launch {
            try {
                downloader.download(toFetch).collect { event ->
                    when (event) {
                        is DownloadEvent.Progress -> _state.value = _state.value.copy(
                            doneBytes = event.bytesDone
                        )
                        DownloadEvent.Completed -> _state.value = _state.value.copy(
                            running = false,
                            doneBytes = _state.value.totalBytes,
                            error = null,
                            finished = true
                        )
                    }
                }
            } catch (e: ModelDownloadException) {
                _state.value = _state.value.copy(
                    running = false,
                    error = e.message
                )
            }
        }
    }
}
