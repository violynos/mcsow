#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

VERSION=$(grep '^mod_version=' gradle.properties | cut -d= -f2)
echo "=== mcsow $VERSION ==="

./gradlew build

for DEST in \
    "$HOME/.local/share/PrismLauncher/instances/Mod Testing/minecraft/mods" \
    "$HOME/testserver/mods"; do
    mkdir -p "$DEST"
    rm -f "$DEST"/mcsow-*.jar
    cp "build/libs/mcsow-${VERSION}.jar" "$DEST/"
    echo "Copied to $DEST"
done
