#!/usr/bin/env bash
# Release tag -> versionName / versionCode. Must match Updater.code() in the app.
#   versionCode = (major*10000 + minor*100 + patch) * 100 + stage
#   stage = 99 for a final release, the beta number (1..98) for a pre-release (v2.5-beta2 -> 2)
# Usage: tools/version.sh v2.4.1        prints: name=2.4.1 code=2040199 prerelease=false
set -euo pipefail
tag=${1:?usage: version.sh <tag>}
name=${tag#v}; name=${name#V}
base=${name%%[-+ ]*}
if ! [[ $base =~ ^[0-9]{1,4}(\.[0-9]{1,2}){0,2}$ ]]; then
  echo "error: '$tag' is not a version tag (expected vMAJOR.MINOR[.PATCH][-betaN])" >&2; exit 1
fi
IFS=. read -r ma mi pa <<< "$base"
mi=${mi:-0}; pa=${pa:-0}
stage=99; pre=false
rest=${name:${#base}}
if [[ $rest == -* ]]; then
  pre=true
  suf=${rest#-}; suf=${suf%%+*}
  n=$(grep -oE '[0-9]+$' <<< "$suf" || true)
  n=${n:-1}; n=$((10#${n:0:3}))
  (( n < 1 )) && n=1; (( n > 98 )) && n=98
  stage=$n
fi
code=$(( (10#$ma * 10000 + 10#$mi * 100 + 10#$pa) * 100 + stage ))
echo "name=$name"
echo "code=$code"
echo "prerelease=$pre"
