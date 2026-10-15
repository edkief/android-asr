package com.convoscribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.convoscribe.ui.AppNavHost
import com.convoscribe.ui.theme.ConvoScribeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ConvoScribeTheme {
                AppNavHost()
            }
        }
    }
}
