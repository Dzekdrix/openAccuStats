package io.github.battcap.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.battcap.R
import io.github.battcap.battery.BatteryReader
import io.github.battcap.battery.BatterySample
import io.github.battcap.battery.CapacityEstimate
import io.github.battcap.battery.CapacityEstimator
import io.github.battcap.battery.ChargeSession
import io.github.battcap.battery.CurrentNormalizer
import io.github.battcap.battery.DischargeSession
import io.github.battcap.data.AppDatabase
import io.github.battcap.data.ChargeRecord
import io.github.battcap.data.DiagnosticLog
import io.github.battcap.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Service au premier plan qui échantillonne la batterie en continu.
 *
 * - Tant que le suivi est activé, le service tourne sans interruption : il mesure un cycle de
 *   charge pendant que le téléphone est branché, et un cycle de décharge pendant qu'il ne l'est
 *   pas. Un service au premier plan doit toujours afficher une notification ; elle reste
 *   discrète (priorité minimale, sans icône) hors charge.
 * - Pendant la charge, un wakelock partiel garde le processeur éveillé : sans lui, écran éteint,
 *   le téléphone se met en veille profonde (même branché) et la boucle s'arrête. Le coût est
 *   négligeable puisque l'appareil est sur secteur, et le wakelock est relâché dès que la charge
 *   est terminée ou débranchée. Pendant la décharge, aucun wakelock n'est pris : la mesure
 *   s'appuie sur le compteur de charge matériel, fiable même avec des échantillons espacés.
 * - Le branchement/débranchement est détecté dans la boucle à partir de l'échantillon lui-même ;
 *   les broadcasts POWER_CONNECTED/DISCONNECTED servent seulement à réveiller la boucle plus tôt.
 *   Ainsi, lancer le suivi alors que le téléphone est déjà branché fonctionne aussi.
 * - Tout tourne sur le thread principal : le travail est minime et évite les accès concurrents.
 * - Le service n'est arrêté que par l'utilisateur (désactivation du suivi) ou par Android ;
 *   voir MonitoringController pour la remise en place après un arrêt forcé.
 */
class ChargeMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var reader: BatteryReader
    private lateinit var settings: Settings
    private lateinit var wakeLock: PowerManager.WakeLock

    private val normalizer = CurrentNormalizer()
    private var chargeSession: ChargeSession? = null
    private var dischargeSession: DischargeSession? = null
    private var sessionStartedAtMs = 0L
    private var wasPlugged: Boolean? = null
    private var started = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { wake.trySend(Unit) }
    }

    override fun onCreate() {
        super.onCreate()
        reader = BatteryReader(this)
        settings = Settings(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BattCap:charge")
            .apply { setReferenceCounted(false) }
        ContextCompat.registerReceiver(
            this, powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED // les broadcasts système restent reçus
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground doit être appelé dans les 5 s qui suivent startForegroundService,
        // même si le service s'arrête aussitôt : sinon Android plante l'app.
        val plugged = reader.read()?.let { it.plugged != 0 } ?: false
        ServiceCompat.startForeground(
            this, Notifications.ID_SERVICE,
            Notifications.service(
                this, getString(if (plugged) R.string.notif_charging else R.string.notif_waiting), plugged
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        if (intent?.action == ACTION_STOP) {
            MonitoringController.disable(this) // depuis la notification : désactive le suivi
            return START_NOT_STICKY
        }
        if (!MonitoringController.isEnabled(this)) { // redémarrage système après désactivation
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            DiagnosticLog.log(this, "Service de mesure démarré (branché=$plugged)")
            ChargeRepository.setRunning(true)
            scope.launch { sampleLoop() }
            // Écran de l'app affiché : on réveille la boucle pour un affichage immédiat
            scope.launch { ChargeRepository.uiVisible.collect { if (it) wake.trySend(Unit) } }
        }
        return START_STICKY
    }

    private suspend fun sampleLoop() {
        while (scope.isActive) {
            val s = reader.read()
            if (s != null) handle(s)
            val delayMs = when {
                s == null -> 5_000L
                // 2 s : les constantes de temps de la charge sont de l'ordre de la minute
                s.plugged != 0 || ChargeRepository.uiVisible.value -> 2_000L
                else -> 30_000L
            }
            withTimeoutOrNull(delayMs) { wake.receive() }
        }
    }

    private fun handle(s: BatterySample) {
        val plugged = s.plugged != 0
        // "!= true" / "!= false" plutôt que "== false" / "== true" : au tout premier échantillon
        // (wasPlugged encore null), la bonne session démarre aussi, sans branchement/débranchement.
        if (plugged && wasPlugged != true) {
            finishDischargeSession()
            startChargeSession()
        }
        if (!plugged && wasPlugged != false) {
            finishChargeSession()
            startDischargeSession()
        }
        wasPlugged = plugged

        val charging = chargeSession
        if (charging != null) {
            charging.onSample(s)
            if (s.isCharging) publishEstimate(charging)
        } else {
            dischargeSession?.onSample(s)
            s.currentRaw?.let { normalizer.feed(it, charging = false) }
        }

        // Wakelock seulement tant que la charge progresse vraiment
        val needAwake = charging != null && s.isCharging && !s.isFull && !charging.reachedFull
        if (needAwake) wakeLock.acquire(WAKE_TIMEOUT_MS) // ré-armé à chaque échantillon
        else if (wakeLock.isHeld) wakeLock.release()

        ChargeRepository.publishSample(s, s.currentRaw?.let { normalizer.displayMa(it, s.isCharging) })
    }

    private fun startChargeSession() {
        chargeSession = ChargeSession(normalizer)
        sessionStartedAtMs = System.currentTimeMillis()
        DiagnosticLog.log(this, "Charge détectée : début de la mesure")
        ChargeRepository.publishEstimate(null)
        updateNotification(getString(R.string.notif_charging), charging = true)
    }

    private fun startDischargeSession() {
        dischargeSession = DischargeSession(normalizer)
        sessionStartedAtMs = System.currentTimeMillis()
        updateNotification(getString(R.string.notif_waiting), charging = false)
    }

    private fun publishEstimate(current: ChargeSession) {
        val est = CapacityEstimator.estimate(current, settings.designMah) ?: return
        ChargeRepository.publishEstimate(est)
        updateNotification(getString(R.string.notif_estimate, est.bestMah, est.deltaSocPct), charging = true)
    }

    /** Fige la mesure de charge (débranchement ou arrêt du suivi) et l'enregistre si exploitable. */
    private fun finishChargeSession() {
        val ended = chargeSession ?: return
        chargeSession = null
        if (wakeLock.isHeld) wakeLock.release()

        val est = CapacityEstimator.estimate(ended, settings.designMah)
        val outcome = when {
            est == null -> {
                DiagnosticLog.log(this, "Fin de charge : aucune variation de niveau mesurée, rien d'enregistré")
                SessionOutcome.NoMeasurement
            }
            else -> {
                save(est, ended.lastPlugged, isDischarge = false)
                DiagnosticLog.log(
                    this,
                    "Fin de charge enregistrée : ${est.startLevel} % - ${est.endLevel} %, ${est.bestMah.roundToInt()} mAh"
                )
                SessionOutcome.Saved(est.bestMah, est.deltaSocPct, est.reachedFull)
            }
        }
        ChargeRepository.publishOutcome(outcome)
        ChargeRepository.publishEstimate(null)
    }

    /** Fige la mesure de décharge (branchement ou arrêt du suivi) et l'enregistre si exploitable. */
    private fun finishDischargeSession() {
        val ended = dischargeSession ?: return
        dischargeSession = null

        val est = CapacityEstimator.estimateDischarge(ended, settings.designMah) ?: return
        save(est, plugged = 0, isDischarge = true)
        DiagnosticLog.log(
            this,
            "Fin de décharge enregistrée : ${est.startLevel} % - ${est.endLevel} %, ${est.bestMah.roundToInt()} mAh"
        )
    }

    private fun save(est: CapacityEstimate, plugged: Int, isDischarge: Boolean) {
        val record = ChargeRecord(
            endedAtEpochMs = System.currentTimeMillis(),
            startedAtEpochMs = sessionStartedAtMs,
            startLevel = est.startLevel,
            endLevel = est.endLevel,
            bestMah = est.bestMah,
            coulombMah = est.coulombMah,
            chargeCounterMah = est.chargeCounterMah,
            energyWh = est.energyWh,
            durationS = est.durationS,
            maxTempC = est.maxTempC,
            plugged = plugged,
            confidence = est.confidence.name,
            reachedFull = est.reachedFull,
            isDischarge = isDischarge
        )
        val dao = AppDatabase.get(applicationContext).chargeRecords()
        // Portée indépendante du service : l'écriture aboutit même si le service s'arrête
        ioScope.launch { dao.insert(record) }
    }

    private fun updateNotification(text: String, charging: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_SERVICE, Notifications.service(this, text, charging))
    }

    override fun onDestroy() {
        DiagnosticLog.log(this, "Service arrêté")
        finishChargeSession() // arrêt du suivi pendant une charge : la mesure en cours est conservée
        finishDischargeSession() // idem pendant une décharge
        if (wakeLock.isHeld) wakeLock.release()
        unregisterReceiver(powerReceiver)
        scope.cancel()
        ChargeRepository.setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "io.github.battcap.STOP"
        private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
