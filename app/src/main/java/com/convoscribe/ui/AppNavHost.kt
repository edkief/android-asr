package com.convoscribe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.convoscribe.R
import com.convoscribe.ui.enginecheck.EngineCheckScreen
import com.convoscribe.ui.home.HomeScreen
import com.convoscribe.ui.record.RecordScreen
import com.convoscribe.ui.setup.ModelSetupScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_SETUP = "setup"
private const val ROUTE_SETUP_WITH_ENGINE = "setup?engine={engine}"
private const val ROUTE_RECORD = "record"
private const val ROUTE_SESSION_DETAIL = "session/{id}"
private const val ROUTE_ENGINE_CHECK = "engine-check"
private const val ROUTE_SETTINGS = "settings"

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
 *  - `setup` — first-run model download (also reachable with
 *    `?engine=npu|cpu` to scope the download to one engine);
 *  - `record` — live-transcript screen reached from the FAB;
 *  - `engine-check` — diagnostics screen reached from the home
 *    overflow menu;
 *  - `settings` — placeholder until TASK-21 wires the real
 *    settings screen;
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
        composable(
            route = ROUTE_SETUP_WITH_ENGINE,
            arguments = listOf(
                navArgument("engine") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val engine = backStackEntry.arguments?.getString("engine")
            ModelSetupScreen(
                initialEngine = engine,
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
        composable(ROUTE_ENGINE_CHECK) {
            EngineCheckScreen(navController = navController)
        }
        composable(ROUTE_SETTINGS) {
            SettingsPlaceholder(navController = navController)
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

/**
 * Placeholder for the future `settings` screen (real wiring lands
 * in TASK-21). Kept here so the route exists today and the home
 * screen's settings icon has somewhere to land; a back button is
 * the only affordance so QA can hop back to the home list without
 * piling extra code into this file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPlaceholder(navController: NavHostController) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.settings_placeholder_body),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
