#!/usr/bin/env python3
"""
ReTwitch kitchen - a small, Linux-friendly toolchain for rebuilding Twitch
APKs with ReTwitch's mods, built around real Android SDK tools (aapt2/d8)
plus morphe-desktop for the actual patching.

Patching itself does *not* happen here anymore: it moved to
`retwitch-patches/` (a morphe-patcher bundle) because structural
bytecode fingerprints survive R8 re-obfuscating class/method names on
every Twitch release, unlike this script's old anchor-based text patches
on decoded smali. This script now only does the one thing morphe
can't: turning a split `.apks`/`.xapk`/`.apkm` bundle into a single base
APK for it to patch.

Usage:
    python3 kitchen.py merge -f Twitch+31.9.0.apks   # .apks -> work/merged.apk
    cd retwitch-patches && ./build.sh --patch ../work/merged.apk
    python3 kitchen.py clean                          # wipe work/
"""
import argparse
import sys
from pathlib import Path

from kitchen import tasks
from kitchen.config import load_env


def main() -> int:
    parser = argparse.ArgumentParser(description="ReTwitch kitchen")
    parser.add_argument(
        "step",
        choices=["merge", "clean"],
        help="Which pipeline step to run",
    )
    parser.add_argument("-f", "--file", type=Path, help="Input .apks/.xapk/.apkm/.apk (required for merge)")
    parser.add_argument("--force", action="store_true", help="Redo merge even if cached output exists")
    args = parser.parse_args()

    if args.step == "clean":
        tasks.clean()
        return 0

    env = load_env()

    try:
        if args.step == "merge":
            if not args.file:
                parser.error("--file is required for this step")
            tasks.merge(env, args.file, force=args.force)

    except FileNotFoundError as e:
        print(f"\nERROR: {e}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
