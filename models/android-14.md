# Android TV 14 (SDK 34) quirks

Platform-wide quirks observed on Android TV 14 devices, regardless of TV brand or model.

- **`am stack list` format (`RootTask id=`)**: Prints `RootTask id=<n> bounds=[...]` instead of `Stack id=<n>`. `Framer.java` parses both formats.
- **Overlay opacity cap (`MAXIMUM_OBSCURING_OPACITY_FOR_TOUCH`)**: Android 14 caps `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCHABLE` windows at `alpha=0.8`. `MainActivity` uses an opaque black scrim (`1.0f`, initialized visible so density-change recreation doesn't flash Home behind a windowed app) to prevent Home tiles from bleeding through the bedtime scene.
- **`appops` reset on reboot / reinstall & slow `adbd`**: `SYSTEM_ALERT_WINDOW` and `GET_USAGE_STATS` can reset back to `default` on reboot or `adb install -r`, and `adbd` takes a few seconds to come up after boot. `ScreenTimeService` self-heals both `appops` and `RemoteKeyService` over `LocalAdb` with fast exponential backoff (starting at 10 s).
- **Ambient Mode / Screensaver (`dreamx`)**: `cmd dreams` is locked to root and `com.android.systemui/.Somnambulator` exits without starting a dream.
  - Open Ambient Mode settings via `am start -a com.google.android.tv.settings.ambient`.
  - Trigger the screensaver directly via `am start -a com.google.android.pano.action.SLEEP` (resolved by `com.android.tv.settings/.device.display.daydream.DaydreamVoiceAction`, exposed as **Screensaver** in Remote buttons).
- **Wireless debugging (Android 12+)**: Some devices only offer Developer options > **Wireless debugging** (pairing code + ephemeral port); run `adb tcpip 5555` once connected to get a fixed port.
- **Freeform windows may be undeclared**: `android.software.freeform_window_management` may be absent from `pm list features` even when multi-window framing still works cleanly with `enable_freeform_support=1` + `force_resizable_activities=1` + `wm density 240`. Always test rather than assuming from `features.txt`.
