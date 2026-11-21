# Google TV Home

Read `AGENTS.md` first: it's the brief (the outcome we want, the ground rules, how to get onto the TV over adb,
what to look at before changing anything, building and installing the launcher, and how to keep everything
undoable).

@AGENTS.md

The short version:

- The goal is a calm, child-friendly TV: our own launcher (`launcher/`) instead of the stock one, kids' time with
  a bedtime scene, grown-ups menus behind an arrow code, the clutter disabled, and a one-press **Put the TV back**.
- Baseline first (`tvs/snapshot.sh`), then one reversible change at a time. Every change gets its undo in
  `restore.sh` and a row in `CHANGES.md`, in the same commit.
- Check what's on screen before pressing keys on the TV: someone may be watching it.
- Ask George before anything the household will notice or that's hard to undo.
