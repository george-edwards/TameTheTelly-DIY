# Known TV models & Android version quirks

This folder is the shared knowledge base for everything learned across TV models and Android TV versions — not just
debloating, but also window framing, overlays, video decoders, remote-button services, screensavers, permissions,
and ADB quirks.

- **One file per TV model**: `<brand>-<product>.md` using Android's `ro.product.brand` and `ro.product.name`
  (the same prefix `tvs/snapshot.sh` uses, without the per-unit serial number).
- **One file per Android TV version**: `android-<major>.md` for platform-wide quirks that apply across brands.

## Android version quirks

| Android | Profile | Key quirks |
|---|---|---|
| Android TV 11 (SDK 30) | [android-11.md](android-11.md) | `AccessibilityService` volume double-dispatch; `Stack id=` framing; WebView UA Error 152-4 |
| Android TV 14 (SDK 34) | [android-14.md](android-14.md) | `RootTask id=` framing; `0.8` overlay opacity cap; `appops` boot/reinstall reset; `dreamx` `SLEEP` screensaver |

## TV model registry

| Model | Profile | Android | UI | Freeform | Notes |
|---|---|---|---|---|---|
| TCL Smart TV Pro (`TCL-G05_4K_GB`) | [TCL-G05_4K_GB.md](TCL-G05_4K_GB.md) | [11](android-11.md) | 1080p @ 320 dpi | Yes | Full rollback verified; `com.tcl.tvmanager` picture API; `APP_AUTO_START` + AV1 decoder limit |
| TCL Smart TV Pro (`TCL-G08_4K_GB`) | [TCL-G08_4K_GB.md](TCL-G08_4K_GB.md) | [14](android-14.md) | 1080p @ 320 dpi | Works (undeclared) | `dreamx` Google Photos screensaver works with `launcherx` off; `RootTask` framing + `appops` boot self-heal verified |

## How to use this folder

1. **Before working on any TV** (setup, debloating, launcher changes, debugging), read **both**:
   - `models/android-<major>.md` for its Android version, and
   - `models/<brand>-<product>.md` (or the closest sibling from the same brand) if one exists.
   - If a model profile matches, propose its verified tiers directly to the household — still checking that every
     package is installed on this TV, recording its factory state from `baseline/`, backing up APKs first, and
     adding the matching undo lines to `restore.sh`.
2. **Whenever you discover a new quirk or verify a new TV model**, record it here right away (not only for
   debloating — include framing, overlays, codecs/decoders, remote keys, permissions, screensavers, ADB, etc.):
   - Put **platform/OS-level quirks** in `models/android-<major>.md` (creating it if needed) and add it to the table above.
   - Put **vendor/model-specific quirks** and curated bloat tiers in `models/<brand>-<product>.md` (following
     [TCL-G05_4K_GB.md](TCL-G05_4K_GB.md)), noting each disabled package's factory state (`default` vs `enabled`).
   - Keep personal/household data out (no serial numbers, IPs, names, camera URLs, or passwords).
