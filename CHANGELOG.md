# Changelog

Toutes les évolutions notables d'openAccuStats sont listées ici.
Format : [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/), versions : [Semantic Versioning](https://semver.org/lang/fr/).

Ajoutez vos changements sous **[Unreleased]** au fil du développement ;
`./scripts/release.sh` les transforme en version au moment de la publication.

## [Unreleased]

## [0.8.1] - 2026-11-11

- Correction : la tâche « branchement » se reprogrammait en boucle tant que le téléphone restait
  branché, jusqu'à être bloquée par Android (erreur « schedule() called more than 250 times » dans
  le journal). Devenue inutile depuis le suivi continu, elle est supprimée ; la vérification
  périodique (15 min) suffit pour relancer le service s'il a été arrêté.
- Accueil : « Charge entrée » devient « Charge stockée » pendant une charge et « Charge restituée »
  pendant une décharge, comme dans l'historique.

## [0.8.0] - 2026-11-11

- L'application s'appelle désormais **openAccuStats** (anciennement BattCap), avec le nouvel
  identifiant `io.github.dzekdrix.openaccustats` : elle s'installe comme une nouvelle app, à côté
  de l'ancienne, sans reprendre son historique.
- Outils de build mis à jour : Gradle 9.3.1, Android Gradle Plugin 8.13.2, Kotlin 2.3.21,
  KSP 2.3.12 et Room 2.8.5 (compatible KSP2). Le build accepte désormais un JDK de 17 à 25.

## [0.7.0] - 2026-11-11

- Le suivi tourne désormais en continu dès qu'il est activé, charge comme décharge, plutôt que
  de se limiter au branchement ; il se relance automatiquement après un redémarrage du téléphone
  s'il était activé avant.
- Nouveau : les cycles de décharge sont eux aussi mesurés et enregistrés dans l'historique
  (capacité totale estimée, pourcentage de départ et de fin, étiquette « Décharge »), et affichés
  en direct sur l'Accueil pendant qu'ils sont en cours, comme pour une charge.
- Le suivi en continu est maintenant protégé par un filet d'erreur à chaque étape (démarrage du
  service, réception de la batterie, écriture en base…) : un incident imprévu est consigné dans
  le journal de diagnostic au lieu de faire planter l'application.
- Correction : la charge entrée après avoir atteint 100 % (phase à tension constante du
  chargeur) est désormais comptée jusqu'au débranchement, au lieu d'être ignorée.

## [0.6.0] - 2026-11-11

- Démarrage au branchement fiabilisé : vérification périodique de secours (15 min),
  remise en place du suivi après un redémarrage ou une mise à jour de l'app.
- Nouveau : diagnostic du suivi dans les Paramètres (état des tâches, exemption,
  groupe de veille Android, journal des événements copiable).

## [0.5.0] - 2026-11-11

- Nouveau : écran Historique dans le menu latéral (10 dernières charges, ou tout
  l'historique via un bouton segmenté) ; l'historique quitte l'Accueil.
- Toutes les charges sont enregistrées, même très courtes ; les charges de moins
  de 30 % sont marquées « Partielle » et affichées en carte à contour.
- Cartes de l'historique : « Capacité totale estimée » et « Charge stockée » (mAh)
  pendant le cycle, niveaux séparés par un tiret, date de début et de fin de la charge
  (début inconnu pour les charges enregistrées avant cette version).
- La capacité moyenne est pondérée par le niveau gagné (une charge de 50 % pèse
  deux fois moins qu'une charge de 100 %) et n'inclut que les charges d'au moins 30 %.

## [0.4.0] - 2026-11-11

- Plus de notification pendant l'attente d'une charge : la notification n'apparaît
  que pendant la charge (nécessite d'exempter l'app de l'optimisation de batterie ;
  sinon une notification d'attente minimale, sans icône, reste affichée).

## [0.3.0] - 2026-11-11

- Nouveau : menu latéral avec trois écrans, Accueil, Statistiques et Paramètres.
- Nouveau : écran Statistiques avec la courbe de la capacité estimée au fil du temps.
- Paramètres : la capacité nominale y est déplacée et affiche la valeur détectée,
  la valeur configurée et celle réellement utilisée.
- La charge en cours distingue la capacité totale estimée de la charge et de
  l'énergie réellement entrées pendant la charge.

## [0.2.0] - 2026-11-11

- Nouveau : indicateur « charge menée jusqu'au bout » dans l'historique
  (statut FULL ou chute du courant en fin de charge).
- Correction : les charges faites écran éteint n'étaient presque pas mesurées
  (veille profonde) ; le processeur reste désormais éveillé pendant la charge.
- Correction : la mesure en cours est enregistrée aussi à l'arrêt du suivi,
  et la carte indique pourquoi la dernière charge a été enregistrée ou non.
- Correction : le courant s'affiche aussi hors charge (négatif en décharge),
  et « non disponible » si l'appareil ne le fournit pas.
- Proposition d'exempter l'app de l'optimisation de batterie.

## [0.1.0] - 2026-10-22

- Première version : estimation de la capacité de la batterie pendant la charge
  (intégration du courant et compteur de charge de la jauge).
- Énergie stockée (Wh) et santé par rapport à la capacité nominale.
- Historique des charges et moyenne des mesures fiables.
- Interface en français et en anglais.
