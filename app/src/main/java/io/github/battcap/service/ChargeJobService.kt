package io.github.battcap.service

import android.app.job.JobParameters
import android.app.job.JobService
import io.github.battcap.battery.BatteryReader

/**
 * Exécutée par Android quand le téléphone commence à charger (contrainte « en charge »).
 * Se contente de lancer le service de mesure, puis se termine. Le service reprogramme
 * cette tâche quand la charge se termine.
 */
class ChargeJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!MonitoringController.isEnabled(this)) return false
        // Juste après un débranchement, la contrainte « en charge » peut encore être vue comme
        // remplie : on vérifie l'état réel pour ne pas lancer le service (et sa notification) pour rien.
        val plugged = BatteryReader(this).read()?.let { it.plugged != 0 } ?: true
        if (!plugged) {
            MonitoringController.scheduleChargeJob(this)
            return false
        }
        try {
            MonitoringController.startService(this)
        } catch (e: IllegalStateException) {
            // Android 12+ : démarrage refusé (exemption d'optimisation de batterie retirée).
            // ForegroundServiceStartNotAllowedException hérite d'IllegalStateException.
            // Pas de reprogrammation ici (elle se redéclencherait aussitôt) : l'ouverture de
            // l'app, proposée par la notification, remet le suivi en place.
            Notifications.showMissed(this)
        }
        return false // travail terminé, pas besoin de garder la tâche active
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
