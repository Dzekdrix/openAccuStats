package io.github.battcap.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import io.github.battcap.data.DiagnosticLog

/**
 * Tâches programmées du suivi (voir MonitoringController) :
 *  - JOB_ID_CHARGING : exécutée par Android quand le téléphone commence à charger ;
 *  - JOB_ID_SAFETY   : vérification toutes les 15 min, filet de sécurité.
 * Les deux se contentent de lancer le service de mesure si une charge est en cours.
 */
class ChargeJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val isChargingJob = params.jobId == MonitoringController.JOB_ID_CHARGING
        val name = if (isChargingJob) "Tâche « branchement »" else "Vérification périodique"
        if (!MonitoringController.isEnabled(this)) return false

        val plugged = MonitoringController.isPlugged(this) ?: true
        val running = ChargeRepository.running.value
        // Le journal ne garde la vérification périodique que si elle a quelque chose à faire
        if (isChargingJob || (plugged && !running)) {
            DiagnosticLog.log(this, "$name déclenchée (branché=$plugged, service actif=$running)")
        }

        when {
            running -> Unit
            plugged -> {
                if (!MonitoringController.startService(this)) Notifications.showMissed(this)
            }
            // Débranché. Pour la tâche « branchement » : la contrainte « en charge » peut encore
            // paraître remplie juste après un débranchement. Dans tous les cas on (re)programme
            // la tâche « branchement » pour la prochaine charge.
            else -> MonitoringController.scheduleJobs(this)
        }
        return false // travail terminé
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DiagnosticLog.log(this, "Tâche ${params.jobId} interrompue par Android (raison ${params.stopReason})")
        }
        return false
    }
}
