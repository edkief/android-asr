package com.convoscribe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.convoscribe.R
import com.convoscribe.ui.home.HomeScreen
import com.convoscribe.ui.record.RecordScreen
import com.convoscribe.ui.setup.ModelSetupScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_SETUP = "setup"
private const val ROUTE_RECORD = "record"
private const val ROUTE_SESSION_DETAIL = "session/{id}"

/**
 * Single navigation graph for the app. The first-run model download
 * lives behind `setup`; [startDestination] is computed by
 * `MainActivity` from whether the language's required bundles are
 * already installed, so a fully-installed app skips straight to
 * `home` and a fresh install lands on `setup` first.
 *
 * Routes:
 *  - `home` — landing screen with the "New recording" FAB and
 *    session list;
 *  - `setup` — first-run model download;
 *  - `record` — live-transcript screen reached from the FAB;
 *  - `session/{id}` — placeholder until TASK-14 builds the real
 *    session-detail screen.
 */
@Composable
fun AppNavHost(startDestination: String = ROUTE_HOME) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_HOME) {
            HomeScreen(navController = navController)
        }
        composable(ROUTE_SETUP) {
            ModelSetupScreen(
                onFinished = {
                    navController.navigate(ROUTE_HOME) {
                        popUpTo(ROUTE_SETUP) { inclusive = true }
                    }
                }
            )
        }
        composable(ROUTE_RECORD) {
            RecordScreen(navController = navController)
        }
        composable(ROUTE_SESSION_DETAIL) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id")?.toLongOrNull() ?: 0L
            SessionDetailPlaceholder(id = id)
        }
    }
}

/**
 * Placeholder composed for `session/{id}` until TASK-14 wires the
 * real session-detail screen. Renders the id so it is obvious which
 * completed session a navigation push landed on; kept inside
 * `AppNavHost.kt` so the route and its placeholder travel together.
 */
@Composable
private fun SessionDetailPlaceholder(id: Long) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.session_placeholder_title, id),
            style = MaterialTheme.typography.headlineMedium
        )
    }
}
