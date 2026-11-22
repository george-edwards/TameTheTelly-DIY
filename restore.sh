#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-only
# Undo everything this repo did to the TV, returning it to how it came from the factory (as recorded in
# tvs/<brand>-<product>-<serial>/), then remove Home and restart the TV.
#
# Two ways to run it, doing exactly the same thing:
#   - From a computer:  ./restore.sh <tv-ip-or-hostname>
#   - On the TV:        Home's Settings button (top corner, behind the arrow code) > "Put the TV back".
#                       This file is built into Home (launcher/app/build.gradle), so rebuild and reinstall Home
#                       after changing it.
# Everything after the computer part runs on the TV in Android's sh (mksh): plain sh only, and Home's own
# uninstall stays last.
#
# KEEP IT UP TO DATE: every change made to the TV gets its undo here, in the same commit, newest first under
# "Changes", with the date and the original value (from baseline/). Check it with `sh -n restore.sh` and, once
# Home is installed, `adb shell am broadcast -n au.internal.home/.DevReceiver -a au.internal.home.RESTORE_CHECK`.

if [ ! -x /system/bin/pm ]; then
  # On a computer: copy this file to the TV and run it there.
  [ -n "${1:-}" ] || { echo "usage: $0 <tv-ip-or-hostname>" >&2; exit 2; }
  TV="$1:5555"
  adb connect "$TV" >/dev/null
  adb -s "$TV" push "$0" /data/local/tmp/home-restore.sh >/dev/null || exit 1
  exec adb -s "$TV" shell sh /data/local/tmp/home-restore.sh
fi

echo "Putting the TV back"

# ---------- Changes (newest first) ----------
# Examples of the shape each entry takes; replace with the real ones as they're made:
#
# # YYYY-MM-DD disabled vendor extras (baseline/packages_disabled.txt: all were enabled = "default")
# for p in com.vendor.one com.vendor.two; do pm default-state --user 0 "$p"; done
# # (if a package was explicitly enabled in the baseline, use: pm enable --user 0 <pkg>)
#
# # YYYY-MM-DD screensaver off, screen-off timeout raised (originals: enabled=1, timeout=600000)
# settings put secure screensaver_enabled 1
# settings put system screen_off_timeout 600000

# ---------- Home itself (always here once Home is installed) ----------

# Home's adb key was accepted on the TV ("Always allow") so it can size the timed apps' windows. Uninstalling Home
# (last line) deletes its private key, which makes the trusted entry useless; to remove the entry too: Settings >
# Developer options > Revoke USB debugging authorisations. The 7-day expiry for unused adb keys was switched off
# (originally unset):
settings delete global adb_allowed_connection_time

# Freeform windows enabled so Home can open the timed apps in a window inside the bedtime scene (originally unset).
# Needs the restart at the end.
settings delete global enable_freeform_support
settings delete global force_resizable_activities
# Home sets the TV's density lower while a timed app is in its window (Framer.windowDensity) and back
# when the child leaves; in case it was left lowered:
wm density reset

# Home's built-in Remote buttons feature uses an accessibility service (RemoteKeyService); originally none were enabled:
settings delete secure enabled_accessibility_services
settings put secure accessibility_enabled 0

# The stock launcher (and any recovery app that guards it) disabled so Home could be home. Re-enable them before
# setting the home activity. FILL IN from baseline/home_resolve.txt, e.g. on Google TV:
#   pm default-state --user 0 com.google.android.tungsten.setupwraith
#   pm default-state --user 0 com.google.android.apps.tv.launcherx
#   cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/.home.HomeActivity

# Last, in the background: this removes Home (with its "display over other apps" and usage access) while it may be
# the one running this, then restarts the TV to finish.
echo "Removing Home and restarting the TV"
setsid sh -c 'sleep 3; pm uninstall au.internal.home; settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0; rm -f /data/local/tmp/home-restore.sh; reboot' \
  </dev/null >/dev/null 2>&1 &
# adb ends the whole shell session as soon as this script exits: give setsid a moment to get clear of it first
# (without this the uninstall and restart never happen; found on the first TV).
sleep 2
echo "Done"
