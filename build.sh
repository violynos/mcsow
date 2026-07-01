#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
./gradlew build

PRISM_MODS="$HOME/.local/share/PrismLauncher/instances/Mod Testing/minecraft/mods"
mkdir -p "$PRISM_MODS"
cp build/libs/mcsow-*.jar "$PRISM_MODS/"
echo "Copied to $PRISM_MODS"
