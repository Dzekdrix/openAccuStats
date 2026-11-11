package io.github.dzekdrix.openaccustats.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.dzekdrix.openaccustats.service.ChargeRepository
import io.github.dzekdrix.openaccustats.service.MonitoringController
import io.github.dzekdrix.openaccustats.ui.theme.OpenAccuStatsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenAccuStatsTheme {
                AppRoot()
            }
        }
    }

    // Écran visible : le service échantillonne toutes les 2 s même sans chargeur
    override fun onStart() {
        super.onStart()
        ChargeRepository.setUiVisible(true)
        // App au premier plan : on peut (re)mettre en place le suivi en toute sécurité
        MonitoringController.ensure(this, reason = "ouverture de l'app")
    }

    override fun onStop() {
        ChargeRepository.setUiVisible(false)
        super.onStop()
    }
}
