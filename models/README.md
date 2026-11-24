# Known TV models

One file per TV model, named `<brand>-<product>.md` using Android's `ro.product.brand` and `ro.product.name`
(the same prefix `tvs/snapshot.sh` uses, without the per-unit serial number).

## Registry

| Model | Profile | Android | UI | Freeform | Notes |
|---|---|---|---|---|---|
| TCL Smart TV Pro (`TCL-G05_4K_GB`) | [TCL-G05_4K_GB.md](TCL-G05_4K_GB.md) | 11 | 1080p @ 320 dpi | Yes | Full rollback verified; `com.tcl.tvmanager` backlight/blue-light API |
| TCL Smart TV Pro (`TCL-G08_4K_GB`) | [TCL-G08_4K_GB.md](TCL-G08_4K_GB.md) | 14 (SDK 34) | 1080p @ 320 dpi | Works (undeclared) | `dreamx` Google Photos screensaver works with `launcherx` off; `RootTask` framing + `appops` boot self-heal verified |

## How to use this folder

1. **Before debloating a TV**, check whether `models/<brand>-<product>.md` already exists (or a close sibling from
   the same brand).
   - If a match exists, propose its verified tiers directly to the household — still checking that every package is
     actually installed on this TV, recording its factory state from `baseline/`, backing up APKs first, and
     adding the matching undo lines to `restore.sh`.
2. **After debloating and verifying an unseen TV model**, add a new `models/<brand>-<product>.md` following the
   format of [TCL-G05_4K_GB.md](TCL-G05_4K_GB.md):
   - Device identity (`brand`, `name`, `model`, `device`, Android version, UI resolution/dpi).
   - Verified disabled packages grouped by tier, noting each package's factory state (`default` vs `enabled`) so
     `restore.sh` knows whether to use `pm default-state` or `pm enable`.
   - Model-specific quirks (stock launcher + guard app, freeform support, picture APIs, `screencap` quirks, etc.).
   - No personal or household data (no serial numbers, IPs, names, camera URLs, or passwords).
