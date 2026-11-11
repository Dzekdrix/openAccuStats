package io.github.battcap.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.battcap.R
import io.github.battcap.battery.BatteryReader
import io.github.battcap.battery.BatterySample
import io.github.battcap.battery.CapacityEstimate
import io.github.battcap.battery.CapacityEstimator
import io.github.battcap.battery.ChargeSession
import io.github.battcap.battery.CurrentNormalizer
import io.github.battcap.data.AppDatabase
import io.github.battcap.data.ChargeRecord
import io.github.battcap.data.Settings
import io.github.battcap.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Service au premier plan qui échantillonne la batterie.
 *
 * - Pendant la charge, un wakelock partiel garde le processeur éveillé : sans lui, écran éteint,
 *   le téléphone se met en veille profonde (même branché) et la boucle s'arrête. La mesure
 *   n'aurait alors presque aucun échantillon. Le coût est négligeable puisque l'appareil est
 *   sur secteur, et le wakelock est relâché dès que la charge est terminée ou débranchée.
 * - Le branchement/débranchement est détecté dans la boucle à partir de l'échantillon lui-même ;
 *   les broadcasts POWER_CONNECTED/DISCONNECTED servent seulement à réveiller la boucle plus tôt.
 *   Ainsi, lancer le suivi alors que le téléphone est déjà branché fonctionne aussi.
 * - Tout tourne sur le thread principal : le travail est minime et évite les accès concurrents.
 */
class ChargeMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var reader: BatteryReader
    private lateinit var settings: Settings
    private lateinit var wakeLock: PowerManager.WakeLock

    private val normalizer = CurrentNormalizer()
    private var session: ChargeSession? = null
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(getString(R.string.notif_waiting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        if (!started) {
            started = true
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
        if (plugged && wasPlugged != true) startSession()
        if (!plugged && wasPlugged == true) {
            finishSession()
            updateNotification(getString(R.string.notif_waiting))
        }
        wasPlugged = plugged

        val current = session
        if (current != null) {
            current.onSample(s)
            if (s.isCharging) publishEstimate(current)
        } else {
            s.currentRaw?.let { normalizer.feed(it, charging = false) }
        }

        // Wakelock seulement tant que la charge progresse vraiment
        val needAwake = current != null && s.isCharging && !s.isFull && !current.reachedFull
        if (needAwake) wakeLock.acquire(WAKE_TIMEOUT_MS) // ré-armé à chaque échantillon
        else if (wakeLock.isHeld) wakeLock.release()

        ChargeRepository.publishSample(s, s.currentRaw?.let { normalizer.displayMa(it, s.isCharging) })
    }

    private fun startSession() {
        session = ChargeSession(normalizer)
        ChargeRepository.publishEstimate(null)
        updateNotification(getString(R.string.notif_charging))
    }

    private fun publishEstimate(current: ChargeSession) {
        val est = CapacityEstimator.estimate(current, settings.designMah) ?: return
        ChargeRepository.publishEstimate(est)
        updateNotification(getString(R.string.notif_estimate, est.bestMah, est.deltaSocPct))
    }

    /** Fige la mesure (débranchement ou arrêt du suivi) et l'enregistre si elle est exploitable. */
    private fun finishSession() {
        val ended = session ?: return
        session = null
        if (wakeLock.isHeld) wakeLock.release()

        val est = CapacityEstimator.estimate(ended, settings.designMah)
        val outcome = when {
            est == null -> SessionOutcome.NoMeasurement
            est.deltaSocPct < MIN_SAVED_DELTA_SOC -> SessionOutcome.TooShort(est.deltaSocPct)
            else -> {
                save(est, ended.lastPlugged)
                SessionOutcome.Saved(est.bestMah, est.deltaSocPct, est.reachedFull)
            }
        }
        ChargeRepository.publishOutcome(outcome)
        ChargeRepository.publishEstimate(null)
    }

    private fun save(est: CapacityEstimate, plugged: Int) {
        val record = ChargeRecord(
            endedAtEpochMs = System.currentTimeMillis(),
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
            reachedFull = est.reachedFull
        )
        val dao = AppDatabase.get(applicationContext).chargeRecords()
        // Portée indépendante du service : l'écriture aboutit même si le service s'arrête
        ioScope.launch { dao.insert(record) }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ChargeMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onDestroy() {
        finishSession() // arrêt du suivi pendant une charge : la mesure en cours est conservée
        if (wakeLock.isHeld) wakeLock.release()
        unregisterReceiver(powerReceiver)
        scope.cancel()
        ChargeRepository.setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "io.github.battcap.STOP"
        private const val CHANNEL = "charge_monitor"
        private const val NOTIF_ID = 42
        /** Une charge de moins de 5 % n'est pas enregistrée (et en dessous de 30 % elle est exclue de la moyenne). */
        const val MIN_SAVED_DELTA_SOC = 5
        private const val WAKE_TIMEOUT_MS = 10 * 60 * 1000L
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ChargeMonitorService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, ChargeMonitorService::class.java))
    }
}
