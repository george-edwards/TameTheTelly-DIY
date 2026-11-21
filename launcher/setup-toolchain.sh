#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Fetch the build toolchain into RAM (/dev/shm is wiped on reboot). ~500 MB download.
set -eu
T=/dev/shm/bubble_boy/tc
B=https://dl.google.com/android/repository
mkdir -p $T && cd $T
[ -d jdk-17.0.21+9 ] || { curl -sSL https://api.adoptium.net/v3/binary/version/jdk-17.0.21%2B9/linux/x64/jdk/hotspot/normal/eclipse | tar xz; \
  cp /etc/ssl/certs/java/cacerts jdk-17.0.21+9/lib/security/cacerts; }
[ -d gradle-8.9 ] || { curl -sSLo g.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip && unzip -q g.zip && rm g.zip; }
if [ ! -d sdk/platforms/android-34 ]; then
  mkdir -p sdk/platforms sdk/build-tools sdk/licenses
  curl -sSLo p.zip $B/platform-34-ext7_r03.zip && unzip -q p.zip -d sdk/platforms && rm p.zip
  curl -sSLo b.zip $B/build-tools_r34-linux.zip && unzip -q b.zip -d sdk/build-tools && mv sdk/build-tools/android-* sdk/build-tools/34.0.0 && rm b.zip
fi
# Android SDK licence (accepted 2026-11-19): the hash `sdkmanager --licenses` writes. Gradle won't
# auto-install Platform-Tools (needed by the paparazzi tests) without it.
mkdir -p sdk/licenses
printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee' > sdk/licenses/android-sdk-license
echo "Toolchain ready in $T"
