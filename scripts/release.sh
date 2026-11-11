#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Publie une nouvelle version d'openAccuStats (Semantic Versioning).
#
#   ./scripts/release.sh patch      0.1.0 -> 0.1.1   (corrections)
#   ./scripts/release.sh minor      0.1.0 -> 0.2.0   (nouvelles fonctionnalités)
#   ./scripts/release.sh major      0.1.0 -> 1.0.0   (changements majeurs)
#   ./scripts/release.sh minor --dry-run             (affiche sans rien modifier)
#
# Ce que fait le script :
#   1. vérifie que le dépôt git est propre et que la section [Unreleased]
#      du CHANGELOG.md n'est pas vide ;
#   2. calcule versionName et versionCode (MAJOR*10000 + MINOR*100 + PATCH)
#      et les écrit dans app/build.gradle.kts ;
#   3. transforme [Unreleased] en [X.Y.Z] - date dans CHANGELOG.md ;
#   4. crée fastlane/metadata/android/<langue>/changelogs/<versionCode>.txt
#      pour chaque langue (F-Droid les affiche dans la fiche de l'app).
#      Si une langue contient un fichier changelogs/next.txt, il est utilisé
#      (et renommé) à la place du texte du CHANGELOG : pratique pour fournir
#      des notes traduites ;
#   5. commit "Release vX.Y.Z" et tag annoté vX.Y.Z (rien n'est poussé).
# -----------------------------------------------------------------------------
set -euo pipefail

die() { echo "Erreur : $*" >&2; exit 1; }

BUMP="${1:-}"
DRY_RUN=false
[[ "${2:-}" == "--dry-run" ]] && DRY_RUN=true
[[ "$BUMP" =~ ^(major|minor|patch)$ ]] || die "usage : $0 <major|minor|patch> [--dry-run]"

cd "$(git rev-parse --show-toplevel)" || die "pas dans un dépôt git"

GRADLE="app/build.gradle.kts"
CHANGELOG="CHANGELOG.md"
FASTLANE="fastlane/metadata/android"

[[ -z "$(git status --porcelain)" ]] || die "le dépôt contient des modifications non commitées"

# --- version actuelle ---------------------------------------------------------
CURRENT=$(sed -nE 's/^[[:space:]]*versionName = "([0-9]+\.[0-9]+\.[0-9]+)".*/\1/p' "$GRADLE" | head -1)
[[ -n "$CURRENT" ]] || die "versionName introuvable dans $GRADLE"
IFS=. read -r MAJOR MINOR PATCH <<< "$CURRENT"

case "$BUMP" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
esac
(( MINOR <= 99 && PATCH <= 99 )) || die "MINOR et PATCH doivent rester ≤ 99"

NEW="$MAJOR.$MINOR.$PATCH"
CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))
TAG="v$NEW"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "le tag $TAG existe déjà"

# --- notes de version ---------------------------------------------------------
NOTES=$(awk '/^## \[Unreleased\]/{f=1; next} /^## \[/{f=0} f' "$CHANGELOG" | sed '/^[[:space:]]*$/d')
[[ -n "$NOTES" ]] || die "la section ## [Unreleased] de $CHANGELOG est vide"

echo "Version : $CURRENT -> $NEW (versionCode $CODE)"
echo "Notes :"
echo "$NOTES" | sed 's/^/  /'

if $DRY_RUN; then echo "(dry-run : aucune modification)"; exit 0; fi

# --- build.gradle.kts (édition portable, sans sed -i) ------------------------
tmp=$(mktemp)
sed -E \
  -e "s/^([[:space:]]*versionCode = )[0-9]+/\1$CODE/" \
  -e "s/^([[:space:]]*versionName = )\"[0-9.]+\"/\1\"$NEW\"/" \
  "$GRADLE" > "$tmp" && mv "$tmp" "$GRADLE"

# --- CHANGELOG.md -------------------------------------------------------------
tmp=$(mktemp)
awk -v v="$NEW" -v d="$(date +%Y-%m-%d)" '
  { print }
  /^## \[Unreleased\]/ { print ""; print "## [" v "] - " d }
' "$CHANGELOG" > "$tmp" && mv "$tmp" "$CHANGELOG"

# --- changelogs fastlane (lus par F-Droid, 500 caractères max) ---------------
for dir in "$FASTLANE"/*/; do
  mkdir -p "$dir/changelogs"
  out="$dir/changelogs/$CODE.txt"
  if [[ -f "$dir/changelogs/next.txt" ]]; then
    git mv -f "$dir/changelogs/next.txt" "$out" 2>/dev/null || mv "$dir/changelogs/next.txt" "$out"
  else
    printf '%s\n' "$NOTES" > "$out"
  fi
  len=$(wc -m < "$out")
  (( len <= 500 )) || echo "Attention : $out fait $len caractères (F-Droid tronque au-delà de 500)"
done

# --- commit + tag ---------------------------------------------------------------
git add -A
git commit -q -m "Release $TAG"
git tag -a "$TAG" -m "Version $NEW"

echo
echo "Version $NEW prête (commit + tag $TAG créés localement)."
echo "Pour publier :  git push && git push origin $TAG"
