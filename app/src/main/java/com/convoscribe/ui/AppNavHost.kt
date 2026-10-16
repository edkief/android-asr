package com.convoscribe.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.convoscribe.ui.enginecheck.EngineCheckScreen
import com.convoscribe.ui.home.HomeScreen
import com.convoscribe.ui.record.RecordScreen
import com.convoscribe.ui.session.SessionScreen
import com.convoscribe.ui.settings.SettingsScreen
import com.convoscribe.ui.setup.ModelSetupScreen
import com.convoscribe.ui.speakers.SpeakersScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_SETUP = "setup"
private const val ROUTE_SETUP_WITH_ENGINE = "setup?engine={engine}"
private const val ROUTE_RECORD = "record"
private const val ROUTE_SESSION_DETAIL = "session/{id}"
private const val ROUTE_ENGINE_CHECK = "engine-check"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_SPEAKERS = "speakers"

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
 *    overflow menu or the Settings screen;
 *  - `settings` — LLM endpoint, transcription engine, threads,
 *    speakers. Form saves on save/back; engine changes are
 *    written immediately by the segmented button.
 *  - `speakers` — enroll a known speaker (record a 15-second
 *    voice sample so the diarizer can label the cluster with
 *    the speaker's real name), list the enrolled names, delete
 *    them. Reachable from the Speakers section of Settings; the
 *    screen renders an "Open setup" hint when the embedding
 *    model is not installed.
 *  - `session/{id}` — session-detail screen (transcript tab,
 *    summary tab and ask tab). The route id is a `Long` so the
 *    home list's `session/${row.id}` push lands on the right row.
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
            SettingsScreen(navController = navController)
        }
        composable(ROUTE_SPEAKERS) {
            SpeakersScreen(navController = navController)
        }
        composable(
            route = ROUTE_SESSION_DETAIL,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.LongType
                }
            )
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            SessionScreen(sessionId = id, navController = navController)
        }
    }
}
