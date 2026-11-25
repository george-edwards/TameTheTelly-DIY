# Android TV 11 (SDK 30) quirks

Platform-wide quirks observed on Android TV 11 devices, regardless of TV brand or model.

- **`AccessibilityService` volume double-dispatch (`flagRequestFilterKeyEvents`)**: Passing `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN` through (`return false` in `onKeyEvent`) dispatches them twice (+2 / -2 per press). `RemoteKeyService.java` works around this by swallowing the key and calling `AudioManager.adjustSuggestedStreamVolume(...)` directly.
- **`am stack list` format**: Prints `Stack id=<n> bounds=[...]` (unlike Android 14's `RootTask id=`). `Framer.java` parses both formats.
- **Network ADB**: Enabling **USB debugging** in Developer options generally opens port `5555` directly without needing Android 12+ Wireless debugging pairing.
- **WebView YouTube embeds**: Stock Android System WebView includes `; wv)` / `Version/4.0 ` in its User-Agent, which triggers YouTube Error 152-4 when loaded with a `youtube.com` referrer. `WebCamHost.java` strips those tokens and uses `https://au.internal.home/` as `baseUrl` with `strict-origin-when-cross-origin`.
