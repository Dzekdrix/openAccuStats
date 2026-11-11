package io.github.battcap.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.github.battcap.battery.BatteryReader
import io.github.battcap.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Décide comment le suivi attend une charge.
 *
 * Un service au premier plan doit TOUJOURS afficher une notification. Pour ne rien afficher
 * pendant l'attente, aucun service ne tourne alors : une tâche JobScheduler avec la contrainte
 * « en charge » réveille l'app au branchement et démarre le service de mesure.
 *
 * Contrainte d'Android 12+ : un service au premier plan ne peut démarrer depuis l'arrière-plan
 * (donc depuis cette tâche) que si l'utilisateur a désactivé l'optimisation de batterie pour
 * l'app. Cette exemption soustrait aussi l'app aux « buckets » de veille qui peuvent retarder
 * les tâches de plusieurs heures. D'où deux modes :
 *  - exemptée     -> attente silencieuse (aucune notification, aucun service) ;
 *  - non exemptée -> le service reste actif avec une notification minimale (repliée, sans
 *                    icône dans la barre d'état), comme avant.
 */
object MonitoringController {

    private const val JOB_ID = 4201
    private val _enabled = MutableStateFlow(false)

    /** Suivi activé par l'utilisateur. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun refresh(context: Context) { _enabled.value = Settings(context).monitoringEnabled }

    fun isEnabled(context: Context) = Settings(context).monitoringEnabled

    fun canWaitSilently(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Appelé depuis l'interface (premier plan : le démarrage du service est autorisé). */
    fun enable(context: Context) {
        Settings(context).monitoringEnabled = true
        _enabled.value = true
        ensure(context)
    }

    fun disable(context: Context) {
        Settings(context).monitoringEnabled = false
        _enabled.value = false
        cancelJob(context)
        context.stopService(Intent(context, ChargeMonitorService::class.java))
    }

    /**
     * Remet en place le bon mode d'attente. Appelé à l'ouverture de l'app : répare le cas où
     * la tâche aurait été supprimée (arrêt forcé de l'app, effacement des données…).
     */
    fun ensure(context: Context) {
        refresh(context)
        if (!isEnabled(context)) return
        val plugged = BatteryReader(context).read()?.let { it.plugged != 0 } ?: false
        if (plugged || !canWaitSilently(context)) startService(context) else scheduleChargeJob(context)
    }

    fun startService(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, ChargeMonitorService::class.java))
    }

    /** Tâche déclenchée au branchement. Persistante : survit au redémarrage du téléphone. */
    fun scheduleChargeJob(context: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, ChargeJobService::class.java))
            .setRequiresCharging(true)
            .setPersisted(true)
            .build()
        context.getSystemService(JobScheduler::class.java).schedule(job) // remplace la précédente
    }

    fun cancelJob(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
