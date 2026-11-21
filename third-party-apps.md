# Third-party apps

None of these come with a TV, so they're not kept in the repo. Download from the source and check the sha256
before installing; record what's installed in `CHANGES.md` and its `pm uninstall` in `restore.sh`.

| App | Package | Why | Source | sha256 |
|---|---|---|---|---|
| SmartTube 32.68 (F-Droid build, code 2458) | `app.smarttube.fdroid` | Grown-ups' YouTube without ads (SponsorBlock); Home keeps it grown-ups only (`grownup_packages`) | https://f-droid.org/repo/app.smarttube.fdroid_2458.apk (built by F-Droid from source and signed with F-Droid's key; preferred over the developer's own build, whose build machine was compromised in late 2025) | `d88cafc1b204b7cdb7784e4f559051025fa1a5dd4985875121592d21925dafda` |

Remote-button remapping is built directly into Home (top-corner **Remote buttons** icon), so third-party key mappers like Key Mapper are no longer needed.

Newer versions may exist; if you use one, check its signature and source the same way and update this table.

Tried on the first TV and not recommended: Projectivy Launcher (Google TV ignored it as home, not FOSS),
FLauncher and LTvLauncher (FOSS launchers; the household preferred our own).

Home (`au.internal.home`) is built from `launcher/`.
