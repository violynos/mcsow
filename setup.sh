#!/bin/bash
set -e

echo "=== McSow Setup ==="

# 1. Install JDK 21 (Loom 1.14+ requires Java 21 to run Gradle)
echo "[1/5] Installing JDK 21..."
sudo pacman -S --noconfirm jdk21-openjdk
sudo archlinux-java set java-21-openjdk
echo "Java: $(java -version 2>&1 | head -1)"

# 2. Generate Gradle wrapper
echo "[2/5] Generating Gradle wrapper..."
/tmp/gradle-8.10/gradle-8.10/bin/gradle wrapper --gradle-version 8.10

# 3. Make wrapper executable
echo "[3/5] Making wrapper executable..."
chmod +x gradlew

# 4. Build
echo "[4/5] Building McSow..."
./gradlew build

echo ""
echo "=== Setup complete! ==="
echo "Jar: build/libs/mcsow-*.jar"
echo ""
echo "To install: copy the jar to your PrismLauncher mods folder."
echo "To edit: open this folder in your editor."
