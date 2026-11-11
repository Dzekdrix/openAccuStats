package io.github.battcap.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.battcap.R
import io.github.battcap.ui.MainActivity

/**
 * Canaux de notification :
 * - "charge_monitor" (faible) : charge en cours, seule notification affichée normalement ;
 * - "waiting" (minimale, sans icône dans la barre d'état) : attente d'une charge, utilisée
 *   uniquement si l'app n'est pas exemptée d'optimisation de batterie (voir MonitoringController) ;
 * - "alerts" (normale) : une charge a démarré mais Android a refusé de lancer la mesure.
 */
object Notifications {
    const val ID_SERVICE = 42
    const val ID_MISSED = 43
    private const val CH_CHARGING = "charge_monitor"
    private const val CH_WAITING = "waiting"
    private const val CH_ALERTS = "alerts"

    fun ensureChannels(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(CH_CHARGING, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_WAITING, context.getString(R.string.notif_channel_waiting), NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CH_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT)
            )
        )
    }

    /** Notification du service : "en charge" ou "en attente" selon [charging]. */
    fun service(context: Context, text: String, charging: Boolean): Notification {
        ensureChannels(context)
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, ChargeMonitorService::class.java).setAction(ChargeMonitorService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, if (charging) CH_CHARGING else CH_WAITING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_stop), stop)
            .setPriority(if (charging) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Une charge a commencé mais le service n'a pas pu démarrer en arrière-plan. */
    fun showMissed(context: Context) {
        ensureChannels(context)
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notif_missed))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_missed)))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_MISSED, n)
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )
}
