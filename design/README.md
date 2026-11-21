# Bedtime scenes

The world around a timed app while kids' time is running, on the scene tile on Home, and on the Goodnight screen.
All three are drawn entirely in code (no artwork files) on `SkyScene`, in a 1920x1080 reference space with the
video in `Scene.HOLE`: a sky from morning through sunset to night as the allowance is used (progress 0–0.7 day,
0.7–0.9 golden hour, 0.9–1 nightfall, 1 asleep), a sun travelling up the left, over the top and down the right,
clouds, then stars, a rising moon and fireflies. Goodnight is the same world at night with a big sleeping moon and a
breathing OK ring.

- **Sunset hills** (`SunScene`, neutral): hills, a tree and a house whose window lights at dusk; the minutes left
  ride on the sun, then hang in the sky where it set.
- **Fairy garden** (`FairyScene`, girls): a fairy flies along a flower meadow, leaving sparkles, to a pink toadstool
  house and goes in at nightfall; cherry blossom, pink and lilac sky, glitter for fireflies; minutes on the big
  white spot of the toadstool's cap.
- **Steam train** (`TrainScene`, boys): a red steam engine chuffs along the track, past a signal that turns red at
  nightfall, into its engine shed; snowy mountains and pines; minutes on the shed's round plaque.

Rules the scenes keep: the destination is visible all along; the traveller moves continuously and only forwards
with the allowance (not the clock); nothing is drawn over the picture; the child's scene has no words, only the
minutes number; the end is calm and final (no "more time" offered).

To see them: `launcher/render.sh SceneTest` (screenshots go to RAM; it prints where). To add a scene, subclass
`SkyScene` (palette arrays + `drawLand` + `drawMinutes`), add it to `HomeConfig.SCENES`/`SCENE_NAMES` and
`Scene.create`, and to `SceneTest`.
