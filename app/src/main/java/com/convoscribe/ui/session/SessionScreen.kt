package com.convoscribe.ui.session

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.TranscriptLine

/**
 * Fixed 6-entry palette used to tint speaker labels by their
 * index. The index is taken modulo six so the sixth speaker and
 * onward share the last colour; in practice a conversation rarely
 * exceeds four or five speakers, so the wrap is rarely visible.
 *
 * The colours are picked to be distinguishable on both the light
 * and dark Material themes; none of them is the background or the
 * primary text colour, so a label rendered in any of them stays
 * legible against the transcript background.
 */
private val SPEAKER_PALETTE: List<Color> = listOf(
    // blue
    Color(0xFF1976D2),
    // red
    Color(0xFFD32F2F),
    // green
    Color(0xFF388E3C),
    // orange
    Color(0xFFF57C00),
    // purple
    Color(0xFF7B1FA2),
    // teal
    Color(0xFF00838F)
)

/**
 * Entry point for the `session/{id}` route. Pulls the
 * [SessionViewModel] out of the application container, renders the
 * top bar with a back arrow and a share icon, and hands the rest
 * of the layout off to [SessionBody] so the screen can be
 * previewed / tested without a navigation controller.
 *
 * @param sessionId primary key from the `session/{id}` route;
 *   passed straight to the ViewModel, which observes the matching
 *   row through the repository.
 * @param navController app-level controller; the back arrow pops
 *   the back stack so the user lands back on the home list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(sessionId: Long, navController: NavHostController) {
    val context = LocalContext.current
    val container = (context.applicationContext as ConvoApp).container
    val vm: SessionViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SessionViewModel(
                    sessionId = sessionId,
                    repository = container.sessionRepository
                )
            }
        }
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    SessionBody(
        title = state.title,
        status = state.status,
        lines = state.lines,
        notFound = state.notFound,
        onBack = { navController.popBackStack() },
        onShare = {
            // Build the share intent on the main thread; the
            // chooser has to be launched from a context that owns
            // the current activity. We only fire the share when
            // the transcript is non-empty — sharing a blank
            // string would still produce a chooser, but there is
            // nothing useful for the recipient to read.
            val text = vm.transcriptText()
            if (text.isNotBlank()) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, state.title)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                context.startActivity(Intent.createChooser(send, null))
            }
        }
    )
}

/**
 * Render-only variant of [SessionScreen] for preview and testing.
 * The status banner, the empty state and the tab body are pure
 * functions of the inputs, so the test/preview composable only
 * exercises the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionBody(
    title: String,
    status: SessionStatus?,
    lines: List<TranscriptLine>,
    notFound: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.session_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.session_share)
                        )
                    }
                }
            )
        }
    ) { innerPadding: PaddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(text = stringResource(R.string.session_tab_transcript)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(text = stringResource(R.string.session_tab_summary)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(text = stringResource(R.string.session_tab_ask)) }
                )
            }
            when {
                notFound -> SessionNotFound()
                selectedTab == 0 -> TranscriptTab(status = status, lines = lines)
                else -> ComingSoonTab()
            }
        }
    }
}

/**
 * Status banner above the transcript: shows an indeterminate
 * progress bar and the "Identifying speakers…" caption while the
 * diarizer is running, and a plain error label once it failed.
 * Hidden for any other status (RECORDING, TRANSCRIBED, DIARIZED)
 * because there is nothing for the user to wait on or react to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusBanner(status: SessionStatus?) {
    when (status) {
        SessionStatus.DIARIZING -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.session_status_diarizing),
                style = MaterialTheme.typography.bodyMedium
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        SessionStatus.DIARIZATION_FAILED -> Text(
            text = stringResource(R.string.session_status_diarization_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
        else -> Spacer(modifier = Modifier.height(0.dp))
    }
}

/**
 * Transcript tab body: status banner, then either a "No speech
 * was recognised." placeholder or the list of [TranscriptLine]s.
 * Each row renders the timestamp and the speaker label in
 * `labelMedium` (the label is coloured from [SPEAKER_PALETTE] by
 * speaker index, looking up the index on the in-memory list of
 * speakers so renames do not break the colour assignment) and the
 * text in `bodyLarge`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptTab(status: SessionStatus?, lines: List<TranscriptLine>) {
    Column(modifier = Modifier.fillMaxSize()) {
        StatusBanner(status = status)
        if (lines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.session_transcript_empty),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = lines, key = { it.startMs.toString() + "-" + it.text.hashCode() }) { line ->
                    TranscriptRow(line = line)
                }
            }
        }
    }
}

/**
 * Render one [TranscriptLine]. The speaker label colour is
 * derived from the line's position in [SPEAKER_PALETTE]; we use
 * the [TranscriptLine.speaker] string as a fallback key so two
 * segments labelled "Alice" share the same colour, but the
 * primary signal is still the speaker index. The `labelMedium`
 * metadata line shows `"00:03 · Speaker 1"` or just `"00:03"` when
 * the segment is unlabelled.
 */
@Composable
private fun TranscriptRow(line: TranscriptLine) {
    val paletteColor = speakerColor(line.speaker)
    val timestamp = remember(line.startMs) { com.convoscribe.data.TranscriptFormatter.timestamp(line.startMs) }
    val labelText = if (line.speaker.isNullOrEmpty()) {
        timestamp
    } else {
        "$timestamp · ${line.speaker}"
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = labelText,
            style = MaterialTheme.typography.labelMedium,
            color = paletteColor
        )
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * Stable colour for a speaker label. Hashing the label keeps the
 * colour constant across renames — a user named "Alice" keeps
 * their colour even if they are later renamed to "Alex". The
 * fallback when the label is `null` is the default on-surface
 * colour so unlabelled segments are visually quiet.
 */
private fun speakerColor(speaker: String?): Color {
    if (speaker.isNullOrEmpty()) return Color.Unspecified
    val idx = (speaker.hashCode() and Int.MAX_VALUE) % SPEAKER_PALETTE.size
    return SPEAKER_PALETTE[idx]
}

/**
 * Placeholder rendered for the Summary / Ask tabs until those
 * tasks wire their real bodies. Keeps the tab navigation
 * discoverable without claiming functionality that isn't
 * implemented yet.
 */
@Composable
private fun ComingSoonTab() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.session_coming_soon),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

/**
 * Rendered when the route's id does not match a session row —
 * either it was deleted or the navigation came from somewhere
 * unexpected (e.g. a deep link). Showing a placeholder beats
 * crashing, and the user can hit Back to leave the screen.
 */
@Composable
private fun SessionNotFound() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.session_not_found),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
