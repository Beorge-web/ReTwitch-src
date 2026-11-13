#!/usr/bin/env bash
# Rebuilds the whole ReTwitch APK from scratch: wipes kitchen/work/, re-merges
# the split Twitch bundle into one base APK, then applies every
# retwitch-patches/ patch to it.
#
# This just chains the two pipeline steps (kitchen.py merge, retwitch-patches/build.sh)
# plus a `kitchen.py clean` up front, so a fresh install
# never picks up a stale cached merged.apk or an old retwitch-patches.jar.
#
# Usage:
#   ./rebuild.sh                          # uses ../Twitch+31.9.0.apks
#   ./rebuild.sh /path/to/Twitch+X.Y.Z.apks
#
# Output: kitchen/work/merged.patched.apk (already zipaligned + signed with
# revanced-cli's debug key -- ready for `adb install -r`).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

INPUT_APKS="${1:-$HERE/../Twitch+31.9.0.apks}"
# Relative paths are resolved against the caller's cwd, not kitchen/.
if [[ "$INPUT_APKS" != /* ]]; then
    INPUT_APKS="$(pwd)/${INPUT_APKS#./}"
fi
INPUT_APKS="$(cd "$(dirname "$INPUT_APKS")" && pwd)/$(basename "$INPUT_APKS")"

cd "$HERE"
OUT_APK="$HERE/work/merged.patched.apk"

if [[ ! -f "$INPUT_APKS" ]]; then
    echo "ERROR: input bundle not found: $INPUT_APKS" >&2
    echo "Usage: $0 [/path/to/Twitch+X.Y.Z.apks]" >&2
    exit 1
fi

echo "== [1/3] Wiping $HERE/work/ (kitchen.py clean), fetching missing tools =="
python3 kitchen.py clean
python3 -m kitchen.deps

echo
echo "== [2/3] Merging $INPUT_APKS -> work/merged.apk =="
python3 kitchen.py merge -f "$INPUT_APKS" --force

echo
echo "== [3/3] Building + applying retwitch-patches -> work/merged.patched.apk =="
(cd retwitch-patches && ./build.sh --patch ../work/merged.apk)

echo
if [[ -f "$OUT_APK" ]]; then
    echo "Done: $OUT_APK"
    echo "Install with: adb install -r \"$OUT_APK\""
else
    echo "ERROR: expected output APK missing: $OUT_APK" >&2
    exit 1
fi
