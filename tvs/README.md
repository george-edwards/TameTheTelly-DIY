# TVs

One folder per physical TV, holding what it was like from the factory, before anything in this repo touched it.
Only factory state goes here: our own Home builds and third-party APKs don't.

Folder name: `<brand>-<ro.product.name>-<ro.serialno>`, e.g. `TCL-G05_4K_GB-080C30B309D0800BC`.
`./tvs/snapshot.sh <tv>` reads them off the TV and creates the folder.

Inside each folder:

- `baseline/`: read-only snapshot taken before the first change (`snapshot.sh`: packages, settings tables,
  getprop, home launcher, display, features, runtime state).
- `apks/<pkg>/`: the factory APK(s) of every package we disabled or removed, plus `dumpsys package` from before
  (`backup-apk.sh`). Reinstall with `adb install-multiple apks/<pkg>/*.apk` if a package is ever lost.
- `settings/`: original values of settings we changed that aren't obvious from `baseline/`.
- `picture/`: original picture settings that live in the vendor's middleware rather than Android (set by hand
  from the remote), e.g. backlight and blue-light filter on a TCL.

| Folder | TV | Notes |
|---|---|---|
