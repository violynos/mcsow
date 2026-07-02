#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

VERSION=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
echo "=== mcsow $VERSION ==="

./gradlew build

PRISM_MODS="$HOME/.local/share/PrismLauncher/instances/Mod Testing/minecraft/mods"
mkdir -p "$PRISM_MODS"
rm -f "$PRISM_MODS"/mcsow-*.jar
cp "build/libs/mcsow-${VERSION}.jar" "$PRISM_MODS/"
echo "Copied to $PRISM_MODS"
