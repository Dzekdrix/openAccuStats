package io.github.dzekdrix.openaccustats.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.dzekdrix.openaccustats.data.DiagnosticLog

/**
 * Remet le suivi en place après :
 *  - un redémarrage du téléphone (BOOT_COMPLETED) ;
 *  - une mise à jour de l'app (MY_PACKAGE_REPLACED), qui peut annuler les tâches programmées.
 * Ces deux événements font partie des exceptions où Android autorise le démarrage d'un
 * service au premier plan depuis l'arrière-plan, même sans exemption d'optimisation de batterie.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "démarrage du téléphone"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "mise à jour de l'app"
            else -> return
        }
        if (!MonitoringController.isEnabled(context)) return
        DiagnosticLog.log(context, "Remise en place du suivi après $event")
        MonitoringController.ensure(context, reason = event)
    }
}
