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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.battcap.R
import io.github.battcap.battery.BatteryReader
import io.github.battcap.battery.CapacityEstimator
import io.github.battcap.battery.ChargeSession
import io.github.battcap.data.AppDatabase
import io.github.battcap.data.ChargeRecord
import io.github.battcap.data.Settings
import io.github.battcap.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service au premier plan qui échantillonne la batterie.
 *
 * - Un appareil branché n'entre pas en Doze : la boucle tient pendant toute la charge.
 * - ACTION_POWER_CONNECTED ne peut plus être reçu via le manifest (Android 8+) et un service
 *   de premier plan ne peut pas démarrer depuis l'arrière-plan (Android 12+) : l'utilisateur
 *   lance donc le suivi depuis l'app, et le service reste actif entre les charges
 *   (échantillonnage lent quand l'appareil est débranché).
 * - Tout tourne sur le thread principal : le travail est minime et cela évite les accès
 *   concurrents à la session entre la boucle et le BroadcastReceiver.
 */
class ChargeMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var reader: BatteryReader
    private lateinit var settings: Settings
    private var session = ChargeSession()
    private var started = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    session = ChargeSession()
                    ChargeRepository.publishEstimate(null)
                    updateNotification(getString(R.string.notif_charging))
                }
                Intent.ACTION_POWER_DISCONNECTED -> finishSession()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        reader = BatteryReader(this)
        settings = Settings(this)
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
        }
        return START_STICKY
    }

    private suspend fun sampleLoop() {
        while (scope.isActive) {
            val s = reader.read()
            if (s == null) { delay(5_000); continue }
            session.onSample(s)
            ChargeRepository.publishSample(s, session.normalizedCurrentUa(s.currentRaw)?.div(1000.0))
            if (s.isCharging) publishEstimate()
            // 2 s en charge suffit : les constantes de temps de la charge sont de l'ordre de la minute
            delay(if (s.isCharging) 2_000 else 30_000)
        }
    }

    private fun publishEstimate() {
        val est = CapacityEstimator.estimate(session, settings.designMah) ?: return
        ChargeRepository.publishEstimate(est)
        updateNotification(getString(R.string.notif_estimate, est.bestMah, est.deltaSocPct))
    }

    /** Fige et enregistre la mesure quand l'appareil est débranché. */
    private fun finishSession() {
        val est = CapacityEstimator.estimate(session, settings.designMah)
        if (est != null && est.deltaSocPct >= MIN_SAVED_DELTA_SOC) {
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
                plugged = session.lastPlugged,
                confidence = est.confidence.name
            )
            scope.launch(Dispatchers.IO) { AppDatabase.get(this@ChargeMonitorService).chargeRecords().insert(record) }
        }
        session = ChargeSession()
        updateNotification(getString(R.string.notif_waiting))
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
        private const val MIN_SAVED_DELTA_SOC = 10

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ChargeMonitorService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, ChargeMonitorService::class.java))
    }
}
