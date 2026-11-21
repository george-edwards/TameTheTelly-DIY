#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Read-only snapshot of a TV before anything is changed: ./tvs/snapshot.sh <tv-ip-or-hostname>
# Writes tvs/<brand>-<ro.product.name>-<ro.serialno>/baseline/ and prints the folder. Changes nothing on the TV.
set -eu
[ -n "${1:-}" ] || { echo "usage: $0 <tv-ip-or-hostname>" >&2; exit 2; }
TV="$1:5555"
adb connect "$TV" >/dev/null
A() { adb -s "$TV" shell "$@" | tr -d '\r'; }
DIR="$(dirname "$0")/$(A getprop ro.product.brand)-$(A getprop ro.product.name)-$(A getprop ro.serialno)"
B="$DIR/baseline"
if [ -e "$B" ]; then echo "$B already exists: a baseline is only taken once, before the first change" >&2; exit 1; fi
mkdir -p "$B"
A getprop > "$B/getprop.txt"
A pm list packages -f -i -U > "$B/packages_all.txt"
A pm list packages -s > "$B/packages_system.txt"
A pm list packages -3 > "$B/packages_user.txt"
A pm list packages -e > "$B/packages_enabled.txt"
A pm list packages -d > "$B/packages_disabled.txt"
for t in global secure system; do A settings list $t > "$B/settings_$t.txt"; done
A cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$B/home_resolve.txt"
A cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$B/home_candidates.txt"
A cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER > "$B/apps_on_home.txt"
A dumpsys meminfo > "$B/meminfo.txt"
A cat /proc/meminfo > "$B/proc_meminfo.txt"
A top -b -n 1 > "$B/top.txt"
A ps -A > "$B/ps.txt"
A dumpsys activity services > "$B/services.txt" 2>/dev/null || true
A df -h > "$B/df.txt"
A dumpsys diskstats > "$B/diskstats.txt"
A dumpsys deviceidle whitelist > "$B/deviceidle_whitelist.txt"
A wm size > "$B/display.txt"; A wm density >> "$B/display.txt"
A pm list features > "$B/features.txt"
A uptime > "$B/uptime.txt"
cat > "$B/README.md" <<README
# Baseline snapshot, $(date +%F)

Read-only capture of the TV ($1, $(A getprop ro.product.model), Android $(A getprop ro.build.version.release),
build $(A getprop ro.build.display.id)) before any changes, by tvs/snapshot.sh. Nothing on the TV was modified.

- packages_*.txt: package lists (all with path, installer and uid; system; user; enabled; disabled)
- settings_{global,secure,system}.txt: full settings tables
- home_*.txt: current home activity and candidates; apps_on_home.txt: TV launcher apps
- display.txt, features.txt: screen size/density, system features (look for freeform_window_management)
- meminfo.txt, proc_meminfo.txt, top.txt, ps.txt, services.txt, uptime.txt: runtime state
- df.txt, diskstats.txt: storage
- getprop.txt: system properties
README
echo "$DIR"
