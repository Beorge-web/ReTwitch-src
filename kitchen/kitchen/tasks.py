"""High level build steps, chained together by kitchen.py.

Only `merge` and `clean` remain here. Actual patching lives in
`retwitch-patches/` (revanced-patcher bytecode fingerprints); see
`kitchen.py`'s module docstring for why the old decode/patch/rebuild/
sign pipeline was retired.
"""
import shutil
from pathlib import Path

from .config import Env, WORK_DIR

MERGED_APK = WORK_DIR / "merged.apk"


def merge(env: Env, input_path: Path, force: bool = False) -> Path:
    """Merges a split APK bundle (.apks/.xapk/.apkm or a bare .apk) into one APK."""
    if MERGED_APK.exists() and not force:
        print(f"Reusing existing {MERGED_APK} (use --force to redo).")
        return MERGED_APK

    if MERGED_APK.exists():
        MERGED_APK.unlink()

    if input_path.suffix.lower() == ".apk":
        shutil.copy(input_path, MERGED_APK)
        return MERGED_APK

    from .proc import run

    run([
        str(env.java), "-jar", str(env.apkeditor_jar),
        "m", "-i", str(input_path), "-o", str(MERGED_APK), "-f",
    ])
    return MERGED_APK


def clean() -> None:
    if WORK_DIR.exists():
        shutil.rmtree(WORK_DIR)
    print(f"Removed {WORK_DIR}")
