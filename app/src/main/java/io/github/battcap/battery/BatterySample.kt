package io.github.battcap.battery

/**
 * Un échantillon brut lu depuis le système.
 * Les valeurs "raw" ne sont PAS normalisées : selon le constructeur,
 * CURRENT_NOW peut être en µA ou en mA, et positif ou négatif en charge.
 */
data class BatterySample(
    val tNanos: Long,              // SystemClock.elapsedRealtimeNanos() : monotone, continue pendant le sommeil
    val levelPct: Int,             // niveau entier 0..100 (EXTRA_LEVEL / EXTRA_SCALE)
    val voltageMv: Int,            // EXTRA_VOLTAGE (mV)
    val temperatureC: Float,       // EXTRA_TEMPERATURE / 10
    val currentRaw: Int?,          // BATTERY_PROPERTY_CURRENT_NOW, null si non supporté
    val chargeCounterUah: Int?,    // BATTERY_PROPERTY_CHARGE_COUNTER (µAh), null si non supporté
    val isCharging: Boolean,
    val plugged: Int               // BATTERY_PLUGGED_AC / USB / WIRELESS / 0
)
