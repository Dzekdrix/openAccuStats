package io.github.dzekdrix.openaccustats.data

import android.content.Context
import io.github.dzekdrix.openaccustats.battery.BatteryReader

/** Préférences de l'app. Capacité nominale : saisie manuelle prioritaire, sinon valeur détectée. */
class Settings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Lecture par réflexion du power_profile : faite une seule fois, à la demande. */
    val detectedDesignMah: Double? by lazy { BatteryReader.designCapacityMah(context) }

    var manualDesignMah: Double?
        get() = prefs.getFloat(KEY_MANUAL, -1f).takeIf { it > 0 }?.toDouble()
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_MANUAL) else putFloat(KEY_MANUAL, value.toFloat())
        }.apply()

    val designMah: Double? get() = manualDesignMah ?: detectedDesignMah

    /** Le suivi est activé par l'utilisateur (indépendamment du fait qu'un service tourne). */
    var monitoringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MONITORING, false)
        set(value) = prefs.edit().putBoolean(KEY_MONITORING, value).apply()

    private companion object {
        const val KEY_MANUAL = "manual_design_mah"
        const val KEY_MONITORING = "monitoring_enabled"
    }
}
