# TCL Smart TV Pro (`TCL-G05_4K_GB`)

- **Brand / Name**: `TCL` / `G05_4K_GB`
- **Model / Device**: `Smart TV Pro` / `G05`
- **Android**: 11 (`TCL/G05_4K_GB/G05:11/AR2101/AR25:user/release-keys`)
- **UI display**: 1920×1080 @ 320 dpi (4K panel, 1080p UI)
- **Freeform windows**: supported (`android.software.freeform_window_management`), works cleanly with `enable_freeform_support=1` + `force_resizable_activities=1` + `wm density 240` while framed
- **Verified**: Chromecast, HDMI inputs, TV Settings, Bluetooth remote (`TCL_RC902V_A1`), reboot, and full "Put the TV back" rollback all verified OK (2026-10-28 – 2026-11-22)

## Curated bloat tiers

Factory state is noted in parentheses (`default` → undo with `pm default-state --user 0`; `enabled` → undo with `pm enable --user 0`).

### Tier 0 — Vendor telemetry & OTA
- `com.tcl.ttvs` (`default`) — TCL telemetry, T-Cast, Alexa integration, OTA updater

### Tier 1 — TCL extras
- `com.tcl.smartalexa` (`default`) — TCL Alexa wrapper
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
- `com.tcl.MultiScreenInteraction_TV` (`default`) — TCL multi-screen casting helper (Chromecast is separate)
- `com.tcl.gallery` (`enabled`) — TCL media gallery

### Tier 2 — Google extras
- `com.google.android.youtube.tvmusic` (`default`) — YouTube Music
- `com.google.android.play.games` (`default`) — Google Play Games
- `com.google.android.syncadapters.calendar` (`default`) — Calendar sync adapter
- `com.google.android.apps.tv.dreamx` (`default`) — Google TV Ambient screensaver (restore `screensaver_components` to `com.google.android.apps.tv.dreamx/.service.Backdrop` after re-enabling)
- `com.google.android.videos` (`enabled`) — Google TV / Play Movies & TV

### Tier 3 — Stock launcher (only once Home is installed and tested)
- `com.google.android.tungsten.setupwraith` (`default`) — Recovery screen that grabs Home if `launcherx` is disabled alone; **disable first**, re-enable before `launcherx`
- `com.google.android.apps.tv.launcherx` (`default`) — Stock Google TV launcher (`com.google.android.apps.tv.launcherx/.home.HomeActivity`)

## Model quirks
- **Picture API**: exposes `com.tcl.tvmanager` (`TvBlueLight` / `TvBacklight` work automatically; factory defaults were Blue Light Filter ON, Backlight 100).
- **Screenshots**: `adb exec-out screencap -p` prints extra bytes before the `\x89PNG` header — strip everything before `\x89PNG`.
- **Factory-disabled packages** (already disabled out of the box; leave alone): `air.jp.co.fujitv.fodviewer.tv`, `com.tcl.logkit`, `jp.happyon.android`, `jp.unext.tv.player`, `tv.abema`, `tv.wuaki.apptv`.
