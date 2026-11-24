# TCL Smart TV Pro (`TCL-G08_4K_GB`)

- **Brand / Name**: `TCL` / `G08_4K_GB`
- **Model / Device**: `Smart TV Pro` / `G08`
- **Android**: 14 / SDK 34 (`TCL/G08_4K_GB/G08:14/UTT2.250416.001/AU02:user/release-keys`)
- **UI display**: 1920×1080 @ 320 dpi (4K physical panel 3840×2160, 1080p override UI)
- **Freeform windows**: `android.software.freeform_window_management` is **not** declared in `features.txt` (unlike `TCL-G05_4K_GB`); `enable_freeform_support=1` + `force_resizable_activities=1` can still be set, and timed apps fall back to full screen if windowing isn't supported
- **Verified**: Home `2.18.9` as default launcher, reboot, `RESTORE_CHECK` (`LocalAdb`), Ambient Mode Google Photos screensaver (`dreamx` + `com.google.android.pano.action.SLEEP`), and Remote buttons remapper verified OK (2026-11-24)

## Curated bloat tiers

Factory state is noted in parentheses (`default` → undo with `pm default-state --user 0`; `enabled` → undo with `pm enable --user 0`).

### Tier 0 — Vendor telemetry & OTA
- `com.tcl.ttvs` (`default`) — TCL telemetry, T-Cast, Alexa integration, OTA updater

### Tier 1 — TCL extras
- `com.tcl.smartalexa` (`enabled`) — TCL Alexa wrapper (**note**: explicitly `enabled` from factory on G08, unlike `default` on G05)
- `com.tcl.waterfall.overseas` (`default`) — TCL content/ad feed
- `com.tcl.channelplus` (`default`) — TCL Channel+ FAST channels
- `com.tcl.messagebox` (`default`) — TCL promotional notifications
- `com.tcl.dashboard` (`default`) — TCL dashboard overlay
- `com.tcl.usercenter` (`default`) — TCL account / user centre
- `com.tcl.tv.tclhome_passive` (`default`) — TCL Home passive background service
- `com.tcl.partnercustomizer` (`default`) — TCL partner customizer
- `com.tcl.gamebar` (`default`) — TCL Game Bar
- `com.tcl.notereminder` (`default`) — TCL note/reminder app
- `com.tcl.esticker` (`default`) — In-store demo e-sticker
- `com.tcl.ocean.instructions` (`default`) — Built-in manual/instructions
- `com.tcl.browser` (`default`) — TCL web browser
- `com.tcl.magiconnectfree` (`default`) — TCL MagiConnect multi-screen helper (replaces `MultiScreenInteraction_TV` from G05; Chromecast & AirPlay are separate)
- `com.tcl.guard` (`default`) — TCL Safety Guard
- `com.tcl.t_solo` (`default`) — TCL T-solo ("Listen Alone" Bluetooth audio-only app)
- `com.tcl.gallery` (`enabled`) — TCL media gallery

Optional additional TCL tiles left enabled (can be hidden on Home or disabled if unused):
- `com.tcl.exhibit` — T-Exhibition artwork app
- `com.tcl.suspension` — Quick panel overlay

### Tier 2 — Google extras
- `com.google.android.youtube.tvmusic` (`default`) — YouTube Music
- `com.google.android.play.games` (`default`) — Google Play Games
- `com.google.android.syncadapters.calendar` (`default`) — Calendar sync adapter
- `com.google.android.videos` (`enabled`) — Google TV / Play Movies & TV
- *(Optional)* `com.google.android.apps.tv.dreamx` (`default`) — Google TV Ambient screensaver; **keep enabled** if the household wants their Google Photos screensaver roll to keep working

### Tier 3 — Stock launcher (only once Home is installed and tested)
- `com.google.android.tungsten.setupwraith` (`default`) — Recovery screen that grabs Home if `launcherx` is disabled alone; **disable first**, re-enable before `launcherx`
- `com.google.android.apps.tv.launcherx` (`default`) — Stock Google TV launcher (`com.google.android.apps.tv.launcherx/.home.HomeActivity`)

## Model quirks
- **Ambient Mode / Google Photos screensaver**: Keeps working with `launcherx` disabled as long as `com.google.android.apps.tv.dreamx` stays enabled.
  - Android 14 locks `cmd dreams` to root and `com.android.systemui/.Somnambulator` exits without starting a dream.
  - Ambient Mode settings open via `am start -a com.google.android.tv.settings.ambient`.
  - Starting the screensaver directly uses `am start -a com.google.android.pano.action.SLEEP` (resolved by `com.android.tv.settings/.device.display.daydream.DaydreamVoiceAction`) — available as the **Screensaver** action in Home's Remote buttons menu.
- **Keep enabled**: `com.tcl.tv`, `com.tcl.tvinput` (HDMI/TV), `com.tcl.airplay2`, `com.mediatek.AirplayAPK` / `com.mediatek.airplaydaemon` (AirPlay), `com.google.android.apps.mediashell` (Chromecast), `com.tcl.autopair` (Bluetooth remote pairing), `com.android.tv.settings`.
- **Screenshots**: Strip any leading bytes before `\x89PNG` when piping `adb exec-out screencap -p` (same TCL quirk as G05).
- **Android 14 `adb install -r` appops reset**: Reinstalling Home resets `SYSTEM_ALERT_WINDOW` and `GET_USAGE_STATS` back to `default` — always re-run `appops set au.internal.home SYSTEM_ALERT_WINDOW allow` and `appops set au.internal.home GET_USAGE_STATS allow` after every `adb install -r`.
- **Android 14 `am stack list`**: Prints `RootTask id=...` instead of `Stack id=...` (supported by `Framer.java`).
