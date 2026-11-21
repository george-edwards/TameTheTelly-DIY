#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Build the Home launcher APK. Run ./setup-toolchain.sh first after a reboot (JDK 17, Gradle 8.9, SDK platform 34
# + build-tools 34, fetched into /dev/shm with plain curl because sdkmanager can't download from this sandbox).
# The APK, like all build output, is written to RAM (see env.sh); the path is printed at the end.
set -eu
. "$(dirname "$0")/env.sh"
gradle -q assembleRelease
ls -la "$OUT/app/outputs/apk/release/app-release.apk"
