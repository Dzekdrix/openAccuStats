package io.github.dzekdrix.openaccustats.service

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import io.github.dzekdrix.openaccustats.data.DiagnosticLog
import io.github.dzekdrix.openaccustats.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Démarre et surveille le service de mesure.
 *
 * Le service tourne en continu tant que le suivi est activé : il mesure un cycle de charge
 * pendant que le téléphone est branché, un cycle de décharge sinon. Un service au premier plan
 * doit TOUJOURS afficher une notification ; elle reste discrète (priorité minimale, sans icône)
 * hors charge.
 *
 * S'il est arrêté de force (Android tue le processus, surcouche constructeur agressive…),
 * plusieurs mécanismes le relancent, du plus rapide au plus robuste :
 *  1. tâche JobScheduler « en charge » : se déclenche au prochain branchement ;
 *  2. vérification périodique (toutes les 15 min) : filet de sécurité général ;
 *  3. récepteur BOOT_COMPLETED / MY_PACKAGE_REPLACED : remet tout en place après un
 *     redémarrage ou une mise à jour de l'app (qui peut annuler les tâches programmées).
 *
 * Contrainte d'Android 12+ : un service au premier plan ne peut démarrer depuis l'arrière-plan
 * que si l'utilisateur a désactivé l'optimisation de batterie pour l'app, ou si le démarrage a
 * lieu depuis une tâche JobScheduler, au démarrage du téléphone ou après une mise à jour de
 * l'app (exceptions reconnues par Android). Exempter l'app réduit surtout le risque qu'Android
 * tue le service en premier lieu.
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

    /** Exemptée d'optimisation de batterie (réduit le risque qu'Android tue le service). */
    fun canWaitSilently(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

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
     * Remet le service en route s'il devrait tourner et qu'il ne tourne pas (premier démarrage,
     * ou relance après un arrêt forcé). À appeler quand le démarrage d'un service au premier
     * plan est autorisé : app ouverte, démarrage du téléphone, mise à jour de l'app, tâche
     * JobScheduler, ou app exemptée.
     */
    fun ensure(context: Context, reason: String) {
        // Appelé depuis MainActivity.onStart() sans filet : ne doit jamais faire planter l'app
        // appelante, quoi qu'il arrive côté JobScheduler ou démarrage du service.
        try {
            refresh(context)
            if (!isEnabled(context)) return
            scheduleJobs(context)
            if (!ChargeRepository.running.value) {
                DiagnosticLog.log(context, "[$reason] démarrage du service")
                startService(context)
            }
        } catch (t: Throwable) {
            DiagnosticLog.logError(context, "ensure($reason)", t)
        }
    }

    /** Lance le service de mesure. Renvoie false si Android l'a refusé (Android 12+, app non exemptée). */
    fun startService(context: Context): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, ChargeMonitorService::class.java))
        true
    } catch (t: Throwable) {
        // ForegroundServiceStartNotAllowedException (Android 12+) ou IllegalStateException (Android 8+)
        DiagnosticLog.logError(context, "startService", t)
        false
    }

    /** Programme (ou reprogramme) les deux tâches. Persistantes : elles survivent au redémarrage. */
    fun scheduleJobs(context: Context) {
        try {
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
        } catch (t: Throwable) {
            DiagnosticLog.logError(context, "scheduleJobs", t)
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
