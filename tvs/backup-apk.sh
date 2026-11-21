#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Keep a package's factory APK(s) and its `dumpsys package` before disabling or removing it:
#   ./tvs/backup-apk.sh <tv-ip-or-hostname> <tv-folder> <package>...
# e.g. ./tvs/backup-apk.sh 192.168.1.50 tvs/TCL-G05_4K_GB-080C30B309D0800BC com.tcl.browser
# Writes <tv-folder>/apks/<package>/. Reinstall later with `adb install-multiple <tv-folder>/apks/<pkg>/*.apk`.
set -eu
[ $# -ge 3 ] || { echo "usage: $0 <tv> <tv-folder> <package>..." >&2; exit 2; }
TV="$1:5555"; DIR="$2"; shift 2
adb connect "$TV" >/dev/null
for p in "$@"; do
  out="$DIR/apks/$p"
  mkdir -p "$out"
  adb -s "$TV" shell dumpsys package "$p" | tr -d '\r' > "$out/dumpsys_package_before.txt"
  for f in $(adb -s "$TV" shell pm path "$p" | tr -d '\r' | sed 's/^package://'); do
    adb -s "$TV" pull "$f" "$out/" >/dev/null
  done
  echo "$p: $(ls "$out" | grep -c '\.apk$') apk(s)"
done
