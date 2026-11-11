package io.github.dzekdrix.openaccustats.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import io.github.dzekdrix.openaccustats.data.DiagnosticLog

/**
 * Tâches programmées du suivi (voir MonitoringController) :
 *  - JOB_ID_CHARGING : exécutée par Android quand le téléphone commence à charger ;
 *  - JOB_ID_SAFETY   : vérification toutes les 15 min, filet de sécurité général.
 * Le service devant tourner en continu, les deux se contentent de le relancer s'il ne tourne
 * pas (il a pu être tué par Android), que le téléphone soit en charge ou non.
 */
class ChargeJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val isChargingJob = params.jobId == MonitoringController.JOB_ID_CHARGING
        val name = if (isChargingJob) "Tâche « branchement »" else "Vérification périodique"
        if (!MonitoringController.isEnabled(this)) return false

        val running = ChargeRepository.running.value
        // Le journal ne garde la vérification périodique que si elle a quelque chose à faire
        if (isChargingJob || !running) {
            DiagnosticLog.log(this, "$name déclenchée (service actif=$running)")
        }
        // Le service doit tourner en continu : s'il ne tourne pas, on le relance,
        // qu'une charge soit en cours ou non.
        if (!running && !MonitoringController.startService(this)) {
            Notifications.showMissed(this)
        }
        // La tâche « branchement » n'est pas persistante au-delà de son déclenchement :
        // on la reprogramme pour la prochaine charge.
        if (isChargingJob) MonitoringController.scheduleJobs(this)
        return false // travail terminé
    }

    override fun onStopJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DiagnosticLog.log(this, "Tâche ${params.jobId} interrompue par Android (raison ${params.stopReason})")
        }
        return false
    }
}
