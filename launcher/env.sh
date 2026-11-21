# Sourced by build.sh and render.sh. The toolchain, Gradle's caches and everything a build writes live in RAM
# SPDX-License-Identifier: GPL-3.0-only
# (/dev/shm, wiped on reboot: run ./setup-toolchain.sh again after one), so builds don't wear the SSD and the
# repo only ever holds sources.
T=/dev/shm/bubble_boy/tc
export JAVA_HOME=$T/jdk-17.0.21+9 ANDROID_HOME=$T/sdk
export GRADLE_USER_HOME=/dev/shm/bubble_boy/gradle-home ANDROID_USER_HOME=/dev/shm/bubble_boy/android-home
# Java's resolver prefers IPv6 here, which has no route out; curl falls back but Gradle doesn't.
export JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true
cd "$(dirname "${BASH_SOURCE[0]}")"
[ -x $T/gradle-8.9/bin/gradle ] || { echo "No toolchain in $T: run ./setup-toolchain.sh first" >&2; exit 1; }
# Build output for this checkout (the same path launcher/build.gradle computes).
OUT=/dev/shm/bubble_boy/out/$(pwd | sed -E 's/[^A-Za-z0-9]+/_/g; s/^_//')
# Gradle's per-project cache (normally launcher/.gradle) goes there too.
gradle() { $T/gradle-8.9/bin/gradle --project-cache-dir "$OUT/gradle-project" "$@"; }
