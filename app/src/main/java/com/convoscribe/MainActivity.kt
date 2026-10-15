package com.convoscribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.convoscribe.models.ModelCatalog
import com.convoscribe.ui.AppNavHost
import com.convoscribe.ui.theme.ConvoScribeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (applicationContext as ConvoApp).container
        // The active language and ASR engine are settings; if every
        // required bundle for that combination is already on disk we
        // can skip the first-run download screen and go straight to
        // the home screen.
        val settings = container.settingsRepository.settings.value
        val startDestination = if (
            container.modelStore.missing(
                ModelCatalog.requiredFor(settings.asrLanguage, settings.asrEngine)
            ).isEmpty()
        ) {
            "home"
        } else {
            "setup"
        }
        setContent {
            ConvoScribeTheme {
                AppNavHost(startDestination = startDestination)
            }
        }
    }
}
