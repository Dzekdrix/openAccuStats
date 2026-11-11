# CLAUDE.md — BattCap

Instructions pour reprendre le projet avec Claude Code. L'historique git (`git log`) raconte
l'évolution ; `CHANGELOG.md` liste les changements par version ; `README.md` présente l'app.

## But
App Android open source (Kotlin, Jetpack Compose, Room, GPLv3, cible F-Droid) qui estime la
capacité totale réelle de la batterie Li-ion à partir de la charge qui y entre (cycle de charge)
et qui en sort (cycle de décharge). Interface en français (défaut) et anglais.

## Contraintes impératives
- **Open source uniquement** : aucune dépendance propriétaire (pas de Google Play Services,
  Firebase, SDK de pistage). Versions épinglées dans `gradle/libs.versions.toml`.
- **Aucune permission réseau** (`INTERNET` interdit).
- **Textes** : toujours via `strings.xml` ET `values-fr/strings.xml` (mêmes clés des deux côtés ;
  `app_name` est `translatable="false"`).
- Réponses et commentaires en français.

## Versionnage et release
- SemVer. `versionCode = MAJOR*10000 + MINOR*100 + PATCH` (ex. 0.1.0 → 100). Un garde-fou dans
  `app/build.gradle.kts` refuse de compiler si les deux divergent.
- Noter chaque changement sous `## [Unreleased]` de `CHANGELOG.md`.
- Publier : `./scripts/release.sh <patch|minor|major> [--dry-run]` (dépôt propre requis ; met à
  jour version, changelog, fastlane, commit + tag, ne pousse rien).
- Ne modifiez pas à la main versionName/versionCode.

## Build et tests
- **JDK 17 obligatoire** (Gradle 8.11.1 ne supporte pas JDK 25 : erreur cryptique « 25 »).
  Android Studio : Settings > Build > Gradle > Gradle JDK = 17 (jbr-17).
- `./gradlew clean test` (tests unitaires JVM, sans appareil), `./gradlew assembleDebug`.
- Room : `exportSchema = true`, schémas dans `app/schemas/` — les versionner.

## Architecture (`app/src/main/java/io/github/battcap/`)
- `battery/` — logique pure, testée en JVM :
  - `BatteryReader` lit courant/compteur de charge/niveau/tension ; `CurrentNormalizer` apprend
    l'unité (µA/mA) et le signe de charge ; `magnitudeUa()` donne l'amplitude sans signe (décharge).
  - `ChargeSession` / `DischargeSession` intègrent le courant (trapèzes) et le compteur de la
    jauge, ancrés sur les changements de niveau entiers (évite le bruit de quantification).
    `ChargeSession.finalSnapshot()` compte la charge d'entretien après 100 % jusqu'au débranchement.
  - `CapacityEstimator.estimate` / `estimateDischarge` : capacité extrapolée à 100 %, confiance,
    avertissements. Charge : l'intégration du courant prime en cas de désaccord ; décharge : le
    compteur de la jauge prime.
  - `CapacityAverage` : moyenne pondérée par le niveau gagné, charges < 30 % exclues.
- `data/` — Room (`ChargeRecord`, DB v4, migrations 1→4 ; `isDischarge` distingue les décharges),
  `Settings`, `DiagnosticLog` (journal copiable ; `logError` pour les erreurs).
- `service/` — `ChargeMonitorService` (service premier plan **continu** tant que le suivi est
  activé, charge et décharge), `MonitoringController` (démarrage/relance, JobScheduler),
  `ChargeJobService` (tâche « en charge » + vérification périodique 15 min), `RestartReceiver`
  (BOOT_COMPLETED / MY_PACKAGE_REPLACED), `ChargeRepository` (StateFlow partagés service ↔ UI).
- `ui/` — Compose : `AppRoot` (menu latéral), `HomeScreen`, `HistoryScreen`, `StatsScreen`,
  `SettingsScreen`, `MainViewModel`.

## Règles de robustesse (important)
Le service et l'app partagent un même processus : une exception non rattrapée dans le service
fait planter l'app, et comme `MainActivity.onStart()` relance le service, cela peut boucler.
Tout point d'entrée externe (`onCreate`, `onStartCommand`, boucle d'échantillonnage, receivers,
jobs, `MonitoringController.ensure`) doit être enveloppé dans try/catch avec
`DiagnosticLog.logError(context, "où", t)`. En cas d'erreur au démarrage, renvoyer
`START_NOT_STICKY`. Android 12+ : `startForeground()` dans les 5 s ; les démarrages depuis
l'arrière-plan ne sont permis que via exemption batterie, boot, mise à jour ou JobScheduler.

## État et points ouverts
- Un crash au branchement a été signalé sur Pixel 5 / Android 14 après le passage au service
  continu. La correction (filets d'erreur) est basée sur un raisonnement : **aucune trace logcat
  n'a été obtenue**. Si le problème persiste : `adb logcat -b crash` et le journal de diagnostic
  (Paramètres), puis corriger la cause réelle.
- `MainViewModel.history` (Flow Room) n'a pas de `.catch {}` : surface de crash restante.
- `CapacityEstimate.deltaSocPct` n'est pas en valeur absolue (contrairement à
  `ChargeRecord.deltaSocPct`) : attention si utilisé pour des décharges.
- Idées : exporter l'historique (CSV), graphiques séparés charge/décharge, moyenne dédiée décharge.

## Conventions de travail
- Petits commits en français, message au présent/nom (« Ajout… », « Correction… »).
- Ajouter/mettre à jour un test dans `app/src/test` pour toute logique de `battery/`.
- Vérifier la parité des clés de chaînes en/fr et l'absence de `R.string.*` manquant.
