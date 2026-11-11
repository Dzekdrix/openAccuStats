# openAccuStats

Application Android libre qui estime la **capacité réelle** de la batterie Li-ion du téléphone
à partir de la charge qui y entre pendant une recharge.

- Langage : Kotlin, interface Jetpack Compose, historique Room
- Android 8.0 (API 26) minimum
- Aucune permission réseau, aucun service Google, aucun pistage
- Licence : [GPL-3.0-or-later](LICENSE)

## Principe de mesure

**Capacité ≈ charge entrée (mAh) ÷ (Δniveau / 100)**

Deux méthodes, comparées entre elles :

1. **Intégration du courant** (`BATTERY_PROPERTY_CURRENT_NOW`, toutes les 2 s, méthode des trapèzes).
   L'unité (µA ou mA) et le signe varient selon les constructeurs : ils sont détectés
   automatiquement (`CurrentNormalizer`).
2. **Compteur de charge** de la jauge (`BATTERY_PROPERTY_CHARGE_COUNTER`).

Le niveau Android étant un entier, la mesure démarre et se termine **sur un changement de niveau**,
ce qui supprime l'erreur d'arrondi. Le courant est mesuré côté batterie : l'énergie affichée
(Wh) est celle stockée, sans les pertes du chargeur.

## Compiler

```bash
./gradlew assembleDebug          # APK de debug
./gradlew testDebugUnitTest      # tests de la logique de mesure
./gradlew assembleRelease        # APK release (non signé sans keystore.properties)
```

Ouvrir le dossier dans Android Studio fonctionne aussi directement.

### Signature locale (facultatif)

Créez `keystore.properties` à la racine (il est ignoré par git) :

```properties
storeFile=chemin/vers/ma-cle.jks
storePassword=...
keyAlias=...
keyPassword=...
```

F-Droid n'en a pas besoin : il compile et signe l'application lui-même.

## Versionnage

Le projet suit [Semantic Versioning](https://semver.org/lang/fr/) :
`MAJEUR.MINEUR.CORRECTIF`, avec **versionCode = MAJEUR × 10000 + MINEUR × 100 + CORRECTIF**
(0.1.0 → 100, 1.2.3 → 10203). Un contrôle dans `app/build.gradle.kts` bloque la compilation si
les deux valeurs ne correspondent pas.

Au quotidien, notez vos changements sous `## [Unreleased]` dans [CHANGELOG.md](CHANGELOG.md).
Pour publier :

```bash
./scripts/release.sh minor --dry-run   # aperçu
./scripts/release.sh minor             # patch | minor | major
git push && git push origin v0.2.0
```

Le script met à jour `versionCode`/`versionName`, date la section du CHANGELOG, crée les notes
F-Droid (`fastlane/metadata/android/<langue>/changelogs/<versionCode>.txt`), puis commit et tag.
Pour des notes traduites, déposez avant la release un fichier `changelogs/next.txt` dans chaque
langue : il sera utilisé à la place du texte du CHANGELOG.

## Publication sur F-Droid

- Dépôt public (GitHub, GitLab, Codeberg…) avec les tags `vX.Y.Z`.
- Proposer la fiche [`fdroid/io.github.dzekdrix.openaccustats.yml`](fdroid/io.github.dzekdrix.openaccustats.yml) au dépôt
  [fdroiddata](https://gitlab.com/fdroid/fdroiddata) (merge request).
- Ensuite, chaque nouveau tag est détecté et compilé automatiquement par F-Droid.
- Textes et captures de la fiche : `fastlane/metadata/android/` (captures dans
  `images/phoneScreenshots/`).

Choix déjà faits pour F-Droid : dépendances uniquement open source (Apache 2.0), versions figées,
dépôts Maven publics seulement, bloc de métadonnées de dépendances désactivé
(`dependenciesInfo`), aucune permission réseau.

> L'identifiant `io.github.dzekdrix.openaccustats` (lié au compte GitHub `dzekdrix`) est définitif :
> une fois l'app publiée, il ne peut plus changer.
