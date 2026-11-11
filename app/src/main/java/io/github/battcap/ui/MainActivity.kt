package io.github.battcap.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.battcap.service.ChargeRepository
import io.github.battcap.ui.theme.BattCapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BattCapTheme {
                MainScreen()
            }
        }
    }

    // Écran visible : le service échantillonne toutes les 2 s même sans chargeur
    override fun onStart() {
        super.onStart()
        ChargeRepository.setUiVisible(true)
    }

    override fun onStop() {
        ChargeRepository.setUiVisible(false)
        super.onStop()
    }
}
