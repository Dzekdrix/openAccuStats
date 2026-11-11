package io.github.battcap.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.github.battcap.battery.BatteryReader
import io.github.battcap.data.DiagnosticLog
import io.github.battcap.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Décide comment le suivi attend une charge.
 *
 * Un service au premier plan doit TOUJOURS afficher une notification. Pour ne rien afficher
 * pendant l'attente, aucun service ne tourne alors, et plusieurs mécanismes redémarrent la
 * mesure au branchement, du plus rapide au plus robuste :
 *  1. tâche JobScheduler « en charge » : se déclenche au branchement ;
 *  2. vérification périodique (toutes les 15 min) : filet de sécurité si la tâche 1 n'a pas
 *     été exécutée (surcouche constructeur, tâche annulée…) ;
 *  3. récepteur BOOT_COMPLETED / MY_PACKAGE_REPLACED : remet tout en place après un
 *     redémarrage ou une mise à jour de l'app (qui peut annuler les tâches).
 *
 * Contrainte d'Android 12+ : un service au premier plan ne peut démarrer depuis l'arrière-plan
 * que si l'utilisateur a désactivé l'optimisation de batterie pour l'app (exceptions :
 * démarrage du téléphone et mise à jour de l'app). D'où deux modes :
 *  - exemptée     -> attente silencieuse (aucune notification, aucun service) ;
 *  - non exemptée -> le service reste actif avec une notification d'attente minimale.
 */
object MonitoringController {

    const val JOB_ID_CHARGING = 4201
    const val JOB_ID_SAFETY = 4202
    private const val SAFETY_PERIOD_MS = 15 * 60 * 1000L // minimum autorisé par Android

    private val _enabled = MutableStateFlow(false)

    /** Suivi activé par l'utilisateur. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun refresh(context: Context) { _enabled.value = Settings(context).monitoringEnabled }

    fun isEnabled(context: Context) = Settings(context).monitoringEnabled

    fun canWaitSilently(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun isPlugged(context: Context): Boolean? = BatteryReader(context).read()?.let { it.plugged != 0 }

    /** Appelé depuis l'interface (premier plan : le démarrage du service est autorisé). */
    fun enable(context: Context) {
        Settings(context).monitoringEnabled = true
        _enabled.value = true
        DiagnosticLog.log(context, "Suivi activé par l'utilisateur")
        ensure(context, reason = "activation")
    }

    fun disable(context: Context) {
        Settings(context).monitoringEnabled = false
        _enabled.value = false
        cancelJobs(context)
        context.stopService(Intent(context, ChargeMonitorService::class.java))
        DiagnosticLog.log(context, "Suivi désactivé par l'utilisateur")
    }

    /**
     * Remet en place le bon mode d'attente (tâches + service si nécessaire).
     * À appeler seulement quand le démarrage d'un service au premier plan est autorisé :
     * app ouverte, démarrage du téléphone, mise à jour de l'app, ou app exemptée.
     */
    fun ensure(context: Context, reason: String) {
        refresh(context)
        if (!isEnabled(context)) return
        scheduleJobs(context)
        val plugged = isPlugged(context) ?: false
        val silent = canWaitSilently(context)
        if (plugged || !silent) {
            if (!ChargeRepository.running.value) {
                DiagnosticLog.log(context, "[$reason] démarrage du service (branché=$plugged, exemptée=$silent)")
            }
            startService(context)
        }
    }

    /** Lance le service de mesure. Renvoie false si Android l'a refusé (Android 12+, app non exemptée). */
    fun startService(context: Context): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, ChargeMonitorService::class.java))
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException (Android 12+) ou IllegalStateException (Android 8+)
        DiagnosticLog.log(context, "REFUS d'Android au démarrage du service : ${e.javaClass.simpleName}")
        false
    }

    /** Programme (ou reprogramme) les deux tâches. Persistantes : elles survivent au redémarrage. */
    fun scheduleJobs(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val component = ComponentName(context, ChargeJobService::class.java)

        val charging = JobInfo.Builder(JOB_ID_CHARGING, component)
            .setRequiresCharging(true)
            .setPersisted(true)
            .build()
        if (scheduler.schedule(charging) != JobScheduler.RESULT_SUCCESS) {
            DiagnosticLog.log(context, "ÉCHEC de la programmation de la tâche « branchement »")
        }

        // Ne pas reprogrammer la tâche périodique si elle existe déjà : cela remettrait son délai à zéro
        if (scheduler.getPendingJob(JOB_ID_SAFETY) == null) {
            val safety = JobInfo.Builder(JOB_ID_SAFETY, component)
                .setPeriodic(SAFETY_PERIOD_MS)
                .setPersisted(true)
                .build()
            if (scheduler.schedule(safety) != JobScheduler.RESULT_SUCCESS) {
                DiagnosticLog.log(context, "ÉCHEC de la programmation de la vérification périodique")
            }
        }
    }

    fun cancelJobs(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(JOB_ID_CHARGING)
        scheduler.cancel(JOB_ID_SAFETY)
    }

    // ----- État, pour l'écran de diagnostic -----

    data class Status(
        val enabled: Boolean,
        val exempt: Boolean,
        val chargingJobPending: Boolean,
        val safetyJobPending: Boolean,
        val serviceRunning: Boolean,
        val standbyBucket: Int?,     // UsageStatsManager.STANDBY_BUCKET_*, null avant Android 9
        val device: String
    )

    fun status(context: Context): Status {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        return Status(
            enabled = isEnabled(context),
            exempt = canWaitSilently(context),
            chargingJobPending = scheduler.getPendingJob(JOB_ID_CHARGING) != null,
            safetyJobPending = scheduler.getPendingJob(JOB_ID_SAFETY) != null,
            serviceRunning = ChargeRepository.running.value,
            standbyBucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket else null,
            device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        )
    }
}
