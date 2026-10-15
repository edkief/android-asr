package com.convoscribe.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.convoscribe.ConvoApp
import com.convoscribe.R
import com.convoscribe.models.ModelCatalog

/**
 * Landing screen. Today this is the empty-list shell; the only
 * action is the "New recording" [ExtendedFloatingActionButton] that
 * navigates to the `record` route — but only if the required model
 * bundles for the configured language are already on disk. When
 * any required bundle is missing the click handler routes to
 * `setup` instead, so the FAB works in both first-run and steady
 * state.
 *
 * @param navController app-level controller; passed in (rather than
 *   re-read via `rememberNavController`) so a configuration change
 *   pushes back onto the same back stack the parent owns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    // Compute the click handler in composition so the language
    // setting is read fresh every recomposition. The container is
    // cheap to access — it is held on the application instance.
    val container = (context.applicationContext as ConvoApp).container
    val onNewRecording: () -> Unit = {
        val language = container.settingsRepository.settings.value.asrLanguage
        val missing = container.modelStore.missing(
            ModelCatalog.requiredFor(language)
        )
        val target = if (missing.isEmpty()) "record" else "setup"
        navController.navigate(target)
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(R.string.app_name)) })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewRecording,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = stringResource(R.string.record_start)
                    )
                },
                text = { Text(text = stringResource(R.string.home_new_recording)) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(text = stringResource(R.string.home_empty))
        }
    }
}
