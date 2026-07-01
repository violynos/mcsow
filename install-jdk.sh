#!/bin/bash
set -e
echo "Installing JDK 17..."
sudo pacman -S --noconfirm jdk17-openjdk
echo "Setting Java 17 as default..."
sudo archlinux-java set java-17-openjdk
java -version
echo "JDK 17 installed."
echo ""
echo "Now run the gradle wrapper to finish setup:
  cd ~/git/mcsow && gradle wrapper --gradle-version 8.10"
