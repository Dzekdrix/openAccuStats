package io.github.battcap.data

import android.content.Context
import io.github.battcap.battery.BatteryReader

/** Capacité nominale : saisie manuelle prioritaire, sinon valeur détectée dans le power_profile. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val detectedDesignMah: Double? = BatteryReader.designCapacityMah(context)

    var manualDesignMah: Double?
        get() = prefs.getFloat(KEY_MANUAL, -1f).takeIf { it > 0 }?.toDouble()
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_MANUAL) else putFloat(KEY_MANUAL, value.toFloat())
        }.apply()

    val designMah: Double? get() = manualDesignMah ?: detectedDesignMah

    private companion object { const val KEY_MANUAL = "manual_design_mah" }
}
