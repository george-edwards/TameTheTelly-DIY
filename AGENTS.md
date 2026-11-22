# Google TV Home: brief for the agent

You're setting up a Google TV (or Android TV) that nobody here has worked on before: a new make, model and
household. You get onto it over adb from this laptop, look around until you understand it, record how it came
from the factory, and then change it, one reversible step at a time, until it's the TV described below.

Everything done to the TV must be undoable, and is: every change gets its undo line in `restore.sh` and a row in
`CHANGES.md` in the same commit. The household can undo the lot themselves from Home's **Put the TV back**
button, or anyone can with `./restore.sh <tv>` from a computer.

This repo was split out of a repo for one TCL TV, where all of this was done first (Oct–Nov 2026). The
launcher and the method are generic; anything that turned out to be specific to that TV is called out below as
"on the TCL".

## The outcome we want

A calm TV a young child can use, and grown-ups can still use as normal:

1. **Our own Home screen** (`launcher/`, package `au.internal.home`) instead of the stock launcher: no ads, no
   autoplaying trailers, no "recommended for you". Big app tiles that the household can order, favourite and
   hide; a clock, a greeting, the weather; optional camera feeds (RTSP) beside the apps; five themes.
2. **Kids' time**: a daily allowance (e.g. 30 minutes) shared by the timed apps (YouTube by default, or any set
   of apps, or all apps on Home). A timed app opens in a window inside a bedtime scene; the scene moves from
   morning to sunset to night as the time is used; the volume eases down and the picture dims at the end; when
   it's used up the app pauses and a Goodnight screen takes over until the next morning (4am). Three scenes:
   Sunset hills (neutral), Fairy garden (girls), Steam train (boys).
3. **Grown-ups**, behind an arrow code on the remote, in four top-corner icons (up from the top row of apps):
   - **Adult / Kids mode toggle** (person/face icon): switches all timed apps between normal full-screen adult mode
     and timed kids' mode (Kids → Adult requires the arrow code; Adult → Kids is one click).
   - **Parental controls** (padlock icon): daily limit, restart today, extra time today (+5 to +120 min),
     restricted apps, restricted scene, preview, and **Adult mode schedule** (auto-toggle to adult mode at bedtime
     and back to kids' mode later). Holding OK on a restricted app's tile also offers a quick one-app "Grown-up
     time" shortcut.
   - **Remote buttons** (remote icon): press any non-essential remote button and pick an app or TV action (Home,
     TV Settings, Picture settings, Do nothing); timed apps route through `WatchActivity` and grown-ups apps ask
     for the code. Uses a built-in `AccessibilityService` (`RemoteKeyService`) auto-enabled and self-healed over
     `LocalAdb`.
   - **Settings** (sliders icon): Home theme, cameras, grown-up code, family name, weather, bin night, reset, and
     **Put the TV back**.
   Grown-ups-only apps (SmartTube) ask for the code. Pressing Home closes any open menu cleanly.
4. **Less clutter**: the vendor's and Google's extras the household doesn't use are disabled (not uninstalled),
   after checking each tier doesn't break casting, HDMI inputs, the remote, settings or a reboot.
5. **Put the TV back**: the last item in Config (behind the code, then a confirm) runs `restore.sh` on the
   TV: stock launcher back, every disabled app back, every setting back, Home removes itself, the TV restarts.

Ask the household (through George) what they actually want before assuming: which apps are the kids', how many
minutes, which scene, whether there are cameras, what the bins are. Not every TV needs every part.

## Ground rules

- **Look first, change second.** No change before `tvs/snapshot.sh` has saved the baseline. Read the TV, don't
  guess: package names, launchers and settings differ between makes.
- **One change at a time**, then check it (and have the household check anything you can't see: casting, HDMI,
  the remote, a reboot) before the next. Write the `CHANGES.md` row with what was verified and what wasn't.
- **Every change gets its undo** in `restore.sh`, in the same commit, with the original value from the baseline.
  `pm default-state` puts back a package that was "default"; use `pm enable` only if the baseline shows it was
  explicitly enabled. Keep the factory APK of anything you disable (`tvs/backup-apk.sh`) in case it's ever lost.
- **Disable, never uninstall** system apps (`pm disable-user --user 0 <pkg>`). Never factory-reset, never flash,
  never root.
- **Someone may be watching.** Before sending key presses or launching anything, check what's on screen
  (`adb shell dumpsys activity activities | grep mResumedActivity`). If it isn't the home screen, leave it and
  do the non-interactive checks, or ask. Put the TV back on Home when you've finished testing.
- **Ask before** anything hard to undo or that the household will notice (disabling the stock launcher, a reboot
  while they're using it, uninstalling something they installed).
- Commit each step on its own (`git commit` in this repo); the history is the story of the TV.

## Getting onto the TV over adb

1. On the TV: Settings > System (or Device Preferences) > About > press **Android TV OS build** seven times to get
   Developer options. In Developer options turn on **USB debugging** (on most Google TVs this also allows
   network adb on port 5555).
2. Find the TV's IP (Settings > Network, or the router). Give it a fixed lease if you can; note it in `CHANGES.md`.
3. `adb connect <ip>:5555`; the TV shows "Allow debugging?": tick **Always allow** and OK. Then
   `adb -s <ip>:5555 shell getprop ro.product.model` should answer.
4. Newer Google TV (Android 12+) may only offer **Wireless debugging**: Developer options > Wireless debugging >
   Pair device with pairing code, then `adb pair <ip>:<pair-port> <code>` and `adb connect <ip>:<port>`; the port
   changes, so prefer `adb tcpip 5555` once connected, to get a fixed 5555.
5. Screenshots: `adb exec-out screencap -p > shot.png`. Some vendors print junk before the PNG (the TCL did):
   strip everything before the `\x89PNG` signature. Keys: `adb shell input keyevent KEYCODE_HOME` (DPAD_UP,
   DPAD_CENTER, BACK, MENU…).

`adb` is on this laptop (`/usr/bin/adb`). Use `adb -s <ip>:5555` on every command so nothing goes to the wrong
device.

## Sniffing around (before any change)

1. **Snapshot**: `./tvs/snapshot.sh <ip>` writes `tvs/<brand>-<product>-<serial>/baseline/` (packages,
   settings, home launcher, getprop, display, features, runtime state). Add the TV to the table in
   `tvs/README.md`. Commit.
2. **Check `models/`**: look for `models/<brand>-<product>.md` (using `ro.product.brand` and `ro.product.name`
   from `baseline/getprop.txt`). If a curated profile already exists for this model, use its verified tiers and
   quirks as your starting plan (still verifying each package exists on this TV and asking before disabling).
3. Work out, and note in the TV's folder:
   - Android version, screen size and density (`baseline/display.txt`; the scenes assume 1920x1080 at 320 dpi;
     a 4K UI at 1080p is fine, a different size needs a look).
   - **The stock launcher** and anything that guards it: `baseline/home_resolve.txt`, `home_candidates.txt`. On
     Google TV it's `com.google.android.apps.tv.launcherx`, and `com.google.android.tungsten.setupwraith` grabs
     Home with a recovery screen if launcherx is disabled on its own (so disable setupwraith first, and re-enable
     both together).
   - **Freeform windows**: `grep freeform baseline/features.txt` (`android.software.freeform_window_management`).
     Without it the timed apps stay full screen and the scene crops their edges; everything else still works.
   - The vendor's packages (`baseline/packages_system.txt`): which are the launcher, the TV input/HDMI app,
     settings, cast receiver, remote pairing, OTA updater, telemetry, ads/"recommendations", app stores. Look up
     anything unclear (`dumpsys package <pkg>` shows its activities and services) before deciding on it.
   - Whether the vendor has a picture API Home can use (on the TCL, `com.tcl.tvmanager` for the backlight and the
     Blue Light Filter, found by dexdump of a vendor app). Optional; Home skips it elsewhere.
   - The remote: which buttons are app buttons (`adb shell getevent -l` while pressing them), and whether the
     vendor maps them in settings (on the TCL: `settings global CustomKey_<code>`).
3. Write a short summary for George: what the TV is, what's on it, and a proposed plan (which tiers to disable,
   what to keep). Get a yes before changing anything.

## Building and installing Home

- Toolchain (once per boot; it lives in RAM): `launcher/setup-toolchain.sh`. Build: `launcher/build.sh` (prints
  the APK path; all build output goes to `/dev/shm`, never into the repo). Tests and screenshots of the real
  layouts and scenes, no TV needed: `launcher/render.sh` (or `launcher/render.sh SceneTest`); look at the PNGs.
- The signing key is `launcher/keystore.jks` with its passwords in `launcher/keystore.properties`, committed on
  purpose so any checkout can build an update that installs over the last one. Don't rotate it once Home is on a
  TV (a different key means uninstalling Home, which loses its settings).
- Before installing, set the household's defaults in `launcher/app/src/main/res/values/config.xml` (look for
  "SET FOR EACH HOUSEHOLD" and "MEASURE ON EACH TV") and `cameras.xml`, or leave them and use Config on the TV.

Install, in this order, each with its undo already in `restore.sh` (the "Home itself" section has most of them):

1. `adb install -r <apk>`.
2. Permissions Home needs, granted over adb:
   `adb shell appops set au.internal.home SYSTEM_ALERT_WINDOW allow` and
   `adb shell appops set au.internal.home GET_USAGE_STATS allow`.
3. Freeform windows: `settings put global enable_freeform_support 1` and `force_resizable_activities 1` (some
   apps declare themselves unresizeable and otherwise refuse a window: Netflix does), then reboot.
4. Home's own adb connection, used to size the timed apps' windows and by Put the TV back: open a timed app from
   Home once; the TV asks "Allow debugging?" for "Home@tv": **Always allow**. Then
   `settings put global adb_allowed_connection_time 0` so the key doesn't expire after 7 days unused. Check with
   `adb shell am broadcast -n au.internal.home/.DevReceiver -a au.internal.home.RESTORE_CHECK` (copies
   restore.sh onto the TV through Home's connection and checks it parses; runs nothing): "ok … lines".
5. Make Home the home screen: `cmd package set-home-activity --user 0 au.internal.home/.MainActivity`. Google TV
   ignores this while its own launcher is enabled: disable `setupwraith` then `launcherx` (with George's OK; they
   go in restore.sh's "Home itself" section), set Home again, press Home on the remote.
6. Screensaver: Home is happy to stay up (cameras, clock). If the household wants that, `screensaver_enabled 0`
   and a longer `screen_off_timeout`, both with undo lines.
7. Favourites: check `adb -s <ip>:5555 shell dumpsys usagestats` (`In-memory yearly stats`) for the TV's top 4
   most-used launchable apps and flag them as favourites via
   `adb -s <ip>:5555 shell am broadcast -n au.internal.home/.DevReceiver -a au.internal.home.FAVS --es pkgs <pkg1>,<pkg2>,<pkg3>,<pkg4>`,
   then `am force-stop au.internal.home` and `input keyevent KEYCODE_HOME` so they appear at the top immediately.
8. Optional: remap broken or branded remote buttons (e.g. YouTube, Netflix, Settings, Picture) directly on the TV
   via the top-corner **Remote buttons** icon (or over adb with `DevReceiver` `KEY`). No third-party key mapper
   needed; `restore.sh` already resets accessibility.

## Check the window (per TV)

The timed app's window has to land in the scene's video hole (`Scene.HOLE`, 240,100 to 1680,910 at 1920x1080).
Home puts each timed app in a "multi-window" task (windowing mode 6) exactly that size: unlike a freeform window
it has no title bar, so the app sees a plain 16:9 screen and nothing needs measuring. Apps that hand themselves
off to a new full-screen task as they start (YouTube, and YouTube again after a profile is picked) are moved back
in: Home starts an invisible `FrameHolder` in the hole and `am stack move-task`s the app's task into it. Check on
each TV:

- Open each timed app from Home (YouTube: pick a profile and play a video too) and screenshot with the scene on:
  the app should fill the hole, nothing of it cropped and no strip of Home showing.
- `am stack list` shows each task's root bounds and windowing mode (`multi-window` when framed);
  `adb shell am broadcast -n au.internal.home/.DevReceiver -a au.internal.home.STATUS` lists what Home's framing
  did lately.
- While a timed app is in its window Home sets the TV's density to 3/4 of its own (`wm density 240` on a 320 dpi
  1080p TV), so the window is the 960x540 dp screen every TV app is designed for and apps lay out as full screen,
  just smaller. It goes back (`wm density reset`) a few seconds after the child leaves, and restore.sh resets it.
- An app that breaks in a window anyway goes in config.xml's `unwindowable_apps` with the reason (Spotify is there
  already: it sizes itself in pixels from the whole screen, so its menus flicker between two layouts); it's then
  never timed and Config explains why if someone ticks it.

## Make it theirs (Config on the TV, or config.xml)

Grown-ups button > code > Config: Home theme, timed apps theme (the scene, previewed as you move through the
list), **timed apps** (pick apps, or All apps), time allowance, cameras on/off, camera names and addresses, the
grown-up code, family name for the greeting, weather (Australian postcode, then suburb, from the built-in GeoNames
list `launcher/app/src/main/assets/au_postcodes.tsv`; outside Australia set weather_place/lat/lon/tz in
config.xml or over adb), bin night, Reset these options to default, Put the TV back. With All apps, add the TV's own settings and tool apps to `untimed_packages` in
config.xml so a grown-up can always reach them. Adb does the same as the menu:
`adb shell am broadcast -n au.internal.home/.DevReceiver -a au.internal.home.SET --es key <key> --es value <v>`
(keys in `HomeConfig.java`). Other adb controls for testing the scene live (scrub through the day, a 10-second
demo, preview over anything) are listed at the top of `DevReceiver.java`.

## Debloating

Work in tiers, smallest risk first, testing after each (casting, HDMI inputs, remote, settings, a reboot):

1. The vendor's extras (their app store, browser, gallery, "tips", game bar, voice assistants other than
   Google's, telemetry, ad/recommendation services, "smart" home-screen widgets).
2. Google's extras the household doesn't use (Play Games, Play Movies, YouTube Music, the ambient screensaver,
   calendar sync).
3. The stock launcher, only once Home is installed and working (see above).

Before each: `./tvs/backup-apk.sh <ip> tvs/<tv> <pkg>...`, then `pm disable-user --user 0 <pkg>` for each, and the
matching `pm default-state` lines in restore.sh. Never disable: Settings, the TV input/HDMI app, Play services,
Play Store, the keyboard, the cast receiver, remote/Bluetooth pairing, package installer/permission controller.
If something breaks, undo that tier straight away with its restore lines.

If a profile exists in `models/<brand>-<product>.md` (e.g. `models/TCL-G05_4K_GB.md`), start from its curated
tiers instead of rediscovering packages from scratch. If this is a **previously unseen TV model**, once you have
finished debloating and verified nothing broke, save a new `models/<brand>-<product>.md` (see `models/README.md`)
recording the verified disabled packages (with each package's factory state: `default` vs `enabled`) and any
model-specific quirks so future TVs of the same model can reuse it.

## Checking your work

- `launcher/render.sh`: all tests pass; look at the screenshots of anything you changed.
- On the TV after each install: `dumpsys package au.internal.home | grep versionName`, Home is running, nothing
  in the crash buffer (`adb logcat -b crash -d | grep au.internal.home`), and a screenshot.
- `sh -n restore.sh` and RESTORE_CHECK after every change to restore.sh. Don't run the restore itself on a
  household's TV to test it (it undoes everything); if it must be tested, warn George first and set the TV up
  again afterwards from this repo.
- Log the finished change in `CHANGES.md` (date, what, how it was verified, what's still to check).

## Map of the repo

- `launcher/`: Home. Java in `app/src/main/java/au/internal/home/` (start with `MainActivity`,
  `ScreenTimeService` for kids' time, `Scene`/`SkyScene` and the three scenes, `HomeConfig` + `SettingsMenu` for
  Config, `Restore` for Put the TV back, `LocalAdb` + `Framer` for the windows). Defaults in
  `res/values/config.xml`.
- `restore.sh`: the TV's undo list, run from a computer or by Home's button (built into the APK).
- `models/`: curated per-model debloat lists and quirks (`<brand>-<product>.md`), reused when setting up another
  TV of the same model and updated whenever a new model is verified.
- `tvs/`: one folder per TV: factory baseline, factory APKs of anything disabled, original settings.
  `snapshot.sh` and `backup-apk.sh` make them.
- `CHANGES.md`: every change made to the TV, with how it was checked.
- `third-party-apps.md`: apps from outside the Play Store we've used, with sources and checksums.
- `design/README.md`: the scenes.
