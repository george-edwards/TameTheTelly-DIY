#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
# Run the tests and render the launcher's real layouts/drawables on the JVM with Paparazzi (no TV or emulator
# needed): ./render.sh, or ./render.sh SceneTest for one class. The PNGs and test reports go to RAM (see env.sh);
# the folder is printed at the end.
# Camera stills (gitignored) are stand-ins; any 640x360 jpgs named cam1/cam2 in app/src/test/resources (plain grey without them).
set -eu
. "$(dirname "$0")/env.sh"
gradle -q recordPaparazziDebug ${1:+--tests "$1"}
echo "Screenshots: $OUT/snapshots/images/"
echo "Test report: $OUT/app/reports/tests/testDebugUnitTest/index.html"
