package io.github.dzekdrix.openaccustats.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import io.github.dzekdrix.openaccustats.data.DiagnosticLog

/**
 * Vérification périodique (toutes les 15 min, voir MonitoringController) : le service devant
 * tourner en continu, elle se contente de le relancer s'il ne tourne pas (il a pu être tué
 * par Android), que le téléphone soit en charge ou non.
 */
class ChargeJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        try {
            if (params.jobId != MonitoringController.JOB_ID_SAFETY) return false // ancienne tâche
            if (!MonitoringController.isEnabled(this)) return false
            if (ChargeRepository.running.value) return false // rien à faire : pas de ligne au journal

            DiagnosticLog.log(this, "Vérification périodique : service arrêté, relance")
            if (!MonitoringController.startService(this)) {
                Notifications.showMissed(this)
            }
        } catch (t: Throwable) {
            DiagnosticLog.logError(this, "ChargeJobService.onStartJob", t)
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
