#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
./gradlew build

PRISM_MODS="$HOME/.local/share/PrismLauncher/instances/Mod Testing/minecraft/mods"
mkdir -p "$PRISM_MODS"
for f in build/libs/mcsow-*.jar; do
    case "$f" in *-sources.jar) ;; *) cp "$f" "$PRISM_MODS/" ;; esac
done
echo "Copied to $PRISM_MODS"
