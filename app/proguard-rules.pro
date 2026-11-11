# Réflexion sur l'API interne AOSP utilisée pour lire la capacité nominale.
# La classe appartient au framework (pas à l'APK), aucune règle n'est nécessaire,
# mais on garde notre propre code de lecture intact pour faciliter le débogage.
-keep class io.github.dzekdrix.openaccustats.battery.BatteryReader { *; }
