#!/usr/bin/env bash
# Build the signed Home APK, generate latest.json, and print (or publish) the GitHub release.
# Usage: ./release.sh ["Short release notes"]
set -eu
ROOT="$(cd "$(dirname "$0")" && pwd)"
NOTES="${1:-Bug fixes and improvements}"

"$ROOT/launcher/build.sh"
. "$ROOT/launcher/env.sh"

APK="$OUT/app/outputs/apk/release/app-release.apk"
CODE="$(grep -oE 'versionCode [0-9]+' "$ROOT/launcher/app/build.gradle" | awk '{print $2}')"
VER="$(grep -oE 'versionName "[^"]+"' "$ROOT/launcher/app/build.gradle" | cut -d'"' -f2)"
SHA="$(sha256sum "$APK" | awk '{print $1}')"
TAG="v$VER"
ASSET="home-$TAG.apk"
URL="https://github.com/george-edwards/TameTheTelly-DIY/releases/download/$TAG/$ASSET"

python3 -c '
import json, sys
data = {
    "versionCode": int(sys.argv[1]),
    "versionName": sys.argv[2],
    "url": sys.argv[3],
    "sha256": sys.argv[4],
    "notes": sys.argv[5],
}
with open(sys.argv[6], "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
' "$CODE" "$VER" "$URL" "$SHA" "$NOTES" "$ROOT/latest.json"

cp "$APK" "/tmp/$ASSET"
echo "Built $ASSET (versionCode=$CODE, sha256=$SHA)"
echo "Wrote $ROOT/latest.json"
echo ""
echo "Next steps to publish:"
echo "  1. Upload /tmp/$ASSET to GitHub Release tag $TAG:"
echo "     gh release create $TAG /tmp/$ASSET --title \"Home $TAG\" --notes \"$NOTES\""
echo "  2. Commit and push latest.json:"
echo "     git add latest.json && git commit -m \"Release $TAG\" && git push"
