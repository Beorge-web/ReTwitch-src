"""Pinned third-party downloads, each checked by SHA-256.

`python3 -m kitchen.deps` (run from kitchen/; rebuild.sh and retwitch-patches/build.sh
do it) fetches whatever is missing: the build tools into bin/ and the library jars
the extension dex is built from into libs/ (see VENDOR_JAR_NAMES and
COMPILE_ONLY_JAR_NAMES in mod_builder.py). Files already in place are left alone.
"""
import hashlib
import io
import shutil
import sys
import urllib.request
import zipfile
from pathlib import Path

from .config import BIN_DIR, LIBS_DIR

_MAVEN = "https://repo1.maven.org/maven2"
_GOOGLE = "https://dl.google.com/dl/android/maven2"
_GITHUB = "https://github.com"

# dest -> (url, sha256)
TOOLS = {
    BIN_DIR / "APKEditor.jar": (
        f"{_GITHUB}/REAndroid/APKEditor/releases/download/V1.4.5/APKEditor-1.4.5.jar",
        "71999a1f28cf6b457aff17c139436349cd6ea30d75a0f9cd52f07bd52e21897b"),
    BIN_DIR / "morphe" / "morphe-desktop-1.20.0-all.jar": (
        f"{_GITHUB}/MorpheApp/morphe-desktop/releases/download/v1.20.0/morphe-desktop-1.20.0-all.jar",
        "679f579b3ad743ac0ca697ef71e57430d00be59411b04494afd1845c43fb79f0"),
}

# Unpacked into KOTLIN_DIR (the zip holds a top-level kotlinc/).
KOTLIN_DIR = BIN_DIR / "kotlin24"
KOTLIN_ZIP = (f"{_GITHUB}/JetBrains/kotlin/releases/download/v2.4.0/kotlin-compiler-2.4.0.zip",
              "ba1b9e6eb6ddc3275079224f2e9ea4a2b02eef7d59ce2d38404f04b22613c20a")

# libs/<name> -> (url, sha256 of the download, member to extract from an AAR or None)
_APPMETRICA = {
    "analytics": "b9b93b57adb47e30da3cc55e1790986d7633c3a9fb383823da4039b200631c4b",
    "analytics-appsetid": "7950a347170dbb08032b6a03c832eacf10cc0b4ef9baa37c66e531a4abc88009",
    "analytics-common-logger": "aa0062416800d11de468fd04131c895ccbc95add51250e4953b515c43c56e6c8",
    "analytics-core-api": "e11a7aee0ea9c383c7891e412a57cfe6d90f903a749d609faf6ce041908679a0",
    "analytics-core-utils": "d08f2c43553d0794d8d9ca2cf716f9a12ef0f881c8bfea55bf632bd43dc804d1",
    "analytics-location-api": "83d1a9aa80fb7d627d656a616ab3811756ad8f3f463fd576c5ac9ebafe7a3bca",
    "analytics-logger": "a688b56125503af4211ca1598e33ae76ed4bf78dbf59c72f30e0fd287ed75630",
    "analytics-modules-api": "34656b3d5bac3b997126c67ac64868a164493ef7f2ebaf63454a285abaf102f8",
    "analytics-ndkcrashes-api": "393bc915b939762e44be18c6acd5084056b7c4190fe5f2008269075d97343db5",
    "analytics-network": "45852049a71b470fedbf2a6c5a39dd02d1b7ad855b706b57efca0c082b4ecfc5",
    "analytics-network-api": "9978bb63054276f5928a6ae472912d33c6a8ead03344313155e916558d7f0dc2",
    "analytics-network-legacy": "9c268a00cda10d4ea6b4d2a58d2e5153e37ee36932cd8825ad8cea95ee6d49a8",
    "analytics-network-tasks": "8fb423c70130215a3177204f9e1a0ae0d7559a40b6e15889e46df7c44cf1e0ce",
    "analytics-proto": "ff02253410cc9562e2734a75454105e3bda73f2771dabd741764def016bc45fd",
}
LIBS = {
    "okhttp-4.12.0.jar": (
        f"{_MAVEN}/com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar",
        "b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0", None),
    "okio-jvm-3.6.0.jar": (
        f"{_MAVEN}/com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar",
        "67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412", None),
    "kotlin-stdlib-2.0.0.jar": (
        f"{_MAVEN}/org/jetbrains/kotlin/kotlin-stdlib/2.0.0/kotlin-stdlib-2.0.0.jar",
        "240938c4aab8e73e888703e3e7d3f87383ffe5bd536d6d5e3c100d4cd0379fcf", None),
    "annotation-jvm-1.8.2.jar": (
        f"{_GOOGLE}/androidx/annotation/annotation-jvm/1.8.2/annotation-jvm-1.8.2.jar",
        "9aab326d9492800991854360ac248f493ce7f7c3183519309b78ace9e240f6f6", None),
    **{
        f"appmetrica-{name}-8.6.0.jar": (
            f"{_MAVEN}/io/appmetrica/analytics/{name}/8.6.0/{name}-8.6.0.aar", sha, "classes.jar")
        for name, sha in _APPMETRICA.items()
    },
}


def _download(url: str, sha256: str) -> bytes:
    print(f"Downloading {url}", flush=True)
    with urllib.request.urlopen(url) as resp:
        data = resp.read()
    got = hashlib.sha256(data).hexdigest()
    if got != sha256:
        raise RuntimeError(f"{url}: SHA-256 is {got}, expected {sha256}")
    return data


def _write(dest: Path, data: bytes) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    part.write_bytes(data)
    part.replace(dest)


def fetch() -> None:
    for dest, (url, sha) in TOOLS.items():
        if not dest.exists():
            _write(dest, _download(url, sha))

    if not (KOTLIN_DIR / "kotlinc" / "bin" / "kotlinc").exists():
        part = KOTLIN_DIR.with_name(KOTLIN_DIR.name + ".part")
        shutil.rmtree(part, ignore_errors=True)
        with zipfile.ZipFile(io.BytesIO(_download(*KOTLIN_ZIP))) as zf:
            zf.extractall(part)
        # zipfile drops the executable bits.
        for script in (part / "kotlinc" / "bin").iterdir():
            script.chmod(0o755)
        shutil.rmtree(KOTLIN_DIR, ignore_errors=True)
        part.rename(KOTLIN_DIR)

    for name, (url, sha, member) in LIBS.items():
        dest = LIBS_DIR / name
        if dest.exists():
            continue
        data = _download(url, sha)
        if member:
            with zipfile.ZipFile(io.BytesIO(data)) as zf:
                data = zf.read(member)
        _write(dest, data)


if __name__ == "__main__":
    try:
        fetch()
    except Exception as e:  # noqa: BLE001 -- one readable line instead of a traceback
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)
