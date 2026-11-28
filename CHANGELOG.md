# Changelog

## v26.11.28.2 — 2026-11-28
- Add automatic background updates (checks every 6 hours, downloads in the background, and installs when the TV is asleep or idle overnight), with an Automatic/Manual toggle in Settings.
- Manual Check for updates now downloads automatically with a progress bar and prompts to install once verified locally.

## v26.11.28.1 — 2026-11-28
- Keep the camera label vignette anchored to the top of the frame when `labelOffsetLines` lowers the label.
- Add an About screen in Settings showing the installed version, last updated date, and changelog.
- Snap World Cam live streams to the live edge reliably on cold load and standby prefetch promotion.

## v26.11.27.13 — 2026-11-27
- World Cam stream pre-fetch, live videoId cache, and performance fixes.

## v26.11.27.11 — 2026-11-27
- Random World Cam selector strictly picks confirmed-online streams.
- Add new World Cams (Oregon Metro South, Sticky Candy Making Sydney, Sushi Chef Yogi).

## v26.11.27.10 — 2026-11-27
- Support myearthcam.com streams and add new World Cams (Brazil Pizza Time, Sydney Sea-Eagle, Kanazawa, Kyoto, Davao, Key West, Makkah).

## v26.11.27.9 — 2026-11-27
- Standalone EarthCam HLS resolver and player, plus new World Cams.

## v26.11.27.8 — 2026-11-27
- Refine World Cam auto-advance timer pill shape, margin, and wording; add 3 new streams.

## v26.11.27.7 — 2026-11-27
- Add visible World Cam auto-advance countdown pill with duration and return-shuffle menu.

## v26.11.27.6 — 2026-11-27
- Zero-lag live start for World Cams (clear resume storage and click live badge only if behind).
- Sort World Cams catalog by longitude.

## v26.11.27.5 — 2026-11-27
- Add per-stream `labelOffsetLines` support (for streams with a top HUD like Sen 4K ISS Earth).

## v26.11.27.4 — 2026-11-27
- Wrap-around scrolling in the All World Cams picker.

## v26.11.27.3 — 2026-11-27
- Split Developer options unlock into a simpler Step 1 of 2 prompt.

## v26.11.27.2 — 2026-11-27
- Add continent outlines on the World Cam daylight map, country flags on stream labels, live-edge DVR snap, and clearer ADB setup steps.

## v26.11.27.1 — 2026-11-27
- Add World Cam daylight map picker, random-on-return + pin, camera-globe corner icon, and fast online checker.
- Prompt to enable USB debugging when ADB is turned off.

## v26.11.26.1 — 2026-11-27
- Add 37 new World Cams, async online checker, fullscreen unmute, and cleaner YouTube embeds.

## v2.21.12 — 2026-11-26
- Add reversible RTSP camera Hide/Show and single-feed World Cam mode.

## v2.21.11 — 2026-11-26
- Grant TCL `APP_AUTO_START` so RemoteKeyService survives reinstalls.

## v2.21.10 — 2026-11-26
- Refresh World Cams catalog asynchronously every 5 minutes using ETag.

## v2.21.9 — 2026-11-26
- Remove status banner and switch Adult mode corner icon to orange.

## v2.21.8 — 2026-11-26
- Toggle World Cam sound while fullscreen (Up/Down/Play or Hold OK).

## v2.21.7 — 2026-11-26
- Glyph-only top-corner state icons without focus-like highlight.

## v2.21.6 — 2026-11-26
- Add top-corner camera cycle icon and always-visible World Cams remote button actions.

## v2.21.5 — 2026-11-26
- Add Previous World Cams remote button action.

## v2.21.4 — 2026-11-26
- Fast canonical YouTube live ID resolution and auto-HD ABR in fullscreen.

## v2.21.3 — 2026-11-26
- Add Next World Cams remote button action.

## v2.21.2 — 2026-11-26
- Resolve YouTube `/channel/<id>/live` URLs to exact video IDs before embedding.

## v2.21.1 — 2026-11-26
- Shared WebCamHost with H.264 codec shim for simultaneous dual World Cams.

## v2.21.0 — 2026-11-26
- Add in-app GitHub Releases self-updater (Settings → Check for updates).

## v2.20.1 — 2026-11-25
- Show app version in the Settings menu title.

## v2.20.0 — 2026-11-25
- Add World Cams public live streams with remote catalog and hearting.

## v2.19.0 — 2026-11-25
- Add floating RTSP camera overlay over fullscreen apps.

## v2.18.10 — 2026-11-25
- Fresh scene journey after expiry top-up and fix stale demo tile badge.
- Support Android 14 RootTask framing, keep Home scrim opaque behind windowed apps, and self-heal appops + RemoteKeyService on boot.
- Add Screensaver action to the remote button remapper.

## v2.18.9 — 2026-11-22
- Fix double volume step (+2/-2) when RemoteKeyService is enabled.

## v2.18.8 — 2026-11-22
- Add `DevReceiver` `FAVS` command for setting favourite apps over ADB.

## v2.18.7 — 2026-11-22
- Extend full-screen Sky background behind camera cards.

## v2.18.6 — 2026-11-22
- Simplify and reorder the Parental controls menu.

## v2.18.5 — 2026-11-22
- Auto-synthesised app banners, shorter remote button labels, and two-column settings menus.

## v2.18.4 — 2026-11-22
- Home screen responsiveness pass and top-row scroll fix.

## v2.18.3 — 2026-11-22
- Faster Goodnight exit, updated Parental controls label/icon, Grown-up time return fix, and capped time display.

## v2.18.2 — 2026-11-22
- Simplify pass across v2.16.0–v2.18.1.

## v2.18.1 — 2026-11-21
- Initial TameTheTelly DIY release: Home launcher, kids' time scenes, remote button remapper, and Put the TV back.
