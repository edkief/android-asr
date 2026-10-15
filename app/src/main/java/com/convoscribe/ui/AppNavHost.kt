package com.convoscribe.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.convoscribe.ui.home.HomeScreen
import com.convoscribe.ui.setup.ModelSetupScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_SETUP = "setup"

/**
 * Single navigation graph for the app. The first-run model download
 * lives behind `setup`; [startDestination] is computed by
 * `MainActivity` from whether the language's required bundles are
 * already installed, so a fully-installed app skips straight to
 * `home` and a fresh install lands on `setup` first.
 */
@Composable
fun AppNavHost(startDestination: String = ROUTE_HOME) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_HOME) {
            HomeScreen()
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
    }
}
