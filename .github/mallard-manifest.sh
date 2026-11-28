#!/usr/bin/env bash
# .github/mallard-manifest.sh <apk> <version> <code> <tag> <asset> [notes]: the mallard.json that Mallard's updater
# reads (android/core/.../MallardRelease.kt): its name and versionCode, where the APK is on that release, its SHA-256,
# and what's new. The app refuses a manifest whose url isn't one of this repository's mallard-* releases.
set -euo pipefail
apk=$1 version=$2 code=$3 tag=$4 asset=$5 notes=${6:-}
sha=$(sha256sum "$apk" | cut -d' ' -f1)
jq -n --arg v "$version" --argjson c "$code" --arg u "https://github.com/${GITHUB_REPOSITORY:-meltface-80/MusicD-LMS-Remote}/releases/download/$tag/$asset" \
  --arg s "$sha" --arg n "$notes" '{version: $v, code: $c, url: $u, sha256: $s, notes: (if $n == "" then null else $n end)}'
