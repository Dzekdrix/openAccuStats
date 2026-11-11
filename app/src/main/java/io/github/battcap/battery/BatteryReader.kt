package io.github.battcap.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock

class BatteryReader(private val context: Context) {

    private val bm = context.getSystemService(BatteryManager::class.java)

    fun read(): BatterySample? {
        // Broadcast "sticky" : registerReceiver(null, ...) renvoie juste le dernier état connu.
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        return BatterySample(
            tNanos = SystemClock.elapsedRealtimeNanos(),
            levelPct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
            temperatureC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            currentRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).validOrNull(),
            chargeCounterUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).validOrNull(),
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL,
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        )
    }

    /** Int.MIN_VALUE = propriété non supportée ; 0 = souvent "pas d'info" sur d'anciens HAL. */
    private fun Int.validOrNull(): Int? = if (this == Int.MIN_VALUE || this == 0) null else this

    companion object {
        /**
         * Capacité nominale (design) déclarée dans le power_profile.xml de l'OEM.
         * Classe interne d'AOSP (open source), lue par réflexion : fonctionne sur beaucoup
         * d'appareils mais peut être bloquée. L'app propose une saisie manuelle de secours.
         */
        fun designCapacityMah(context: Context): Double? = runCatching {
            val cls = Class.forName("com.android.internal.os.PowerProfile")
            val pp = cls.getConstructor(Context::class.java).newInstance(context)
            cls.getMethod("getBatteryCapacity").invoke(pp) as Double
        }.getOrNull()?.takeIf { it > 1000.0 } // 1000 = valeur par défaut d'AOSP, donc non significative
    }
}
