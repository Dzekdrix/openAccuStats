# Changelog

Toutes les évolutions notables de BattCap sont listées ici.
Format : [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/), versions : [Semantic Versioning](https://semver.org/lang/fr/).

Ajoutez vos changements sous **[Unreleased]** au fil du développement ;
`./scripts/release.sh` les transforme en version au moment de la publication.

## [Unreleased]

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
