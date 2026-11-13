"""
Tool discovery and directory layout for the ReTwitch kitchen.

Actual APK patching happens in `retwitch-patches/` via morphe-desktop
(see `retwitch-patches/build.sh`). This module only resolves what's needed to
(a) merge a split `.apks`/`.xapk`/`.apkm` into one APK for it to patch,
and (b) compile the mod's Java sources to the extension dex (`javac`/`d8`).
Missing pinned tools and jars are fetched by `python3 -m kitchen.deps`.

All paths can be overridden with environment variables, see `_env_path`.
"""
import os
import shutil
from dataclasses import dataclass
from pathlib import Path

KITCHEN_DIR = Path(__file__).resolve().parent.parent
BIN_DIR = KITCHEN_DIR / "bin"
LIBS_DIR = KITCHEN_DIR / "libs"
MOD_DIR = KITCHEN_DIR / "mod"
WORK_DIR = KITCHEN_DIR / "work"
KEYSTORE_DIR = KITCHEN_DIR / "keystore"

TARGET_PACKAGE = "tv.twitch.android.app"


def _env_path(name: str) -> Path | None:
    value = os.environ.get(name)
    return Path(value) if value else None


def _find_on_path(name: str) -> Path | None:
    found = shutil.which(name)
    return Path(found) if found else None


def _android_home() -> Path | None:
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        p = _env_path(var)
        if p and p.exists():
            return p
    return None


def _latest_build_tools() -> Path | None:
    home = _android_home()
    if not home:
        return None
    bt = home / "build-tools"
    if not bt.exists():
        return None
    candidates = sorted(bt.iterdir(), key=lambda p: p.name, reverse=True)
    return candidates[0] if candidates else None


def _build_tool(name: str) -> Path:
    override = _env_path(f"RETWITCH_{name.upper()}")
    if override:
        return override

    bt = _latest_build_tools()
    if bt and (bt / name).exists():
        return bt / name

    on_path = _find_on_path(name)
    if on_path:
        return on_path

    raise FileNotFoundError(
        f"Could not find `{name}`. Install Android SDK build-tools "
        f"(ANDROID_HOME/ANDROID_SDK_ROOT) or set RETWITCH_{name.upper()}."
    )


def _latest_platform_jar() -> Path:
    override = _env_path("RETWITCH_ANDROID_JAR")
    if override:
        return override

    home = _android_home()
    if home:
        platforms = sorted((home / "platforms").glob("android-*"), reverse=True)
        for p in platforms:
            jar = p / "android.jar"
            if jar.exists():
                return jar

    raise FileNotFoundError(
        "Could not find android.jar. Install an Android SDK platform or "
        "set RETWITCH_ANDROID_JAR explicitly."
    )


def _apkeditor_jar() -> Path:
    override = _env_path("RETWITCH_APKEDITOR_JAR")
    if override:
        return override

    bundled = BIN_DIR / "APKEditor.jar"
    if bundled.exists():
        return bundled

    raise FileNotFoundError(
        "Could not find kitchen/bin/APKEditor.jar. Run `python3 -m kitchen.deps` "
        "in kitchen/, or set RETWITCH_APKEDITOR_JAR."
    )


@dataclass(frozen=True)
class Env:
    java: Path
    apkeditor_jar: Path
    d8: Path
    android_jar: Path


def load_env() -> Env:
    WORK_DIR.mkdir(parents=True, exist_ok=True)

    java = _find_on_path("java")
    if not java:
        raise FileNotFoundError("Could not find `java` on PATH.")

    return Env(
        java=java,
        apkeditor_jar=_apkeditor_jar(),
        d8=_build_tool("d8"),
        android_jar=_latest_platform_jar(),
    )
