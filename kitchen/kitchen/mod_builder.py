"""
Builds the extra dex injected into the rebuilt APK.

PurpleTV compiles its extra code through a full multi-module Gradle/Hilt
project (app + Twitch + monolith) invoked via `gradlew.bat genDex`, which
is heavy, Windows-coupled, and only builds on a fully working Gradle/AGP
setup.

For code that only needs plain Android SDK APIs (no dependency on
Twitch's own, obfuscated, internal classes) we don't need Gradle at all:
javac against `android.jar` + `d8` produces an equivalent classes.dex in
a couple of seconds. This keeps the "kitchen" self-contained and fast.
Bigger mods that truly need to call into Twitch's own classes can still
be added later as a separate, optional Gradle module without affecting
this path.

The third-party emote fetcher also vendors a small, real, unmodified copy
of OkHttp (and its own dependencies) straight into this same dex,
compiled and `d8`-merged alongside our own sources. This is deliberate:
Twitch's own build almost certainly bundles an OkHttp of its own, but R8
full-mode renames most of its classes on each release, so there is no
stable name to bind against.

We used to vendor OkHttp under its real, unrenamed `okhttp3.*` package on
the assumption that Twitch's R8 renames *every* one of its own copies, so
there could be no clash. That assumption turned out to be false for a few
internal classes: `okhttp3.internal.connection.RouteException` (at least)
survives R8 under its real name in Twitch's dex, but with methods our code
needs stripped (its `getLastConnectException()` getter is inlined away).
With two dexes defining the same class, Twitch's (searched first) wins, and
our vendored retry path then calls a method that copy no longer has ->
runtime `NoSuchMethodError`. To remove the clash for good, the vendored
OkHttp/Okio are *relocated* (shaded) into a private `tv.retwitch.vendor.*`
package at build time via jarjar -- see `RELOCATION_RULES` and
`_relocate_jar` below. jarjar rewrites the references inside our own
compiled classes too, so `mod/src` keeps importing plain `okhttp3.*`/`okio.*`
and never has to know about the relocation. The Kotlin stdlib is left
shared on purpose: relocating it breaks Kotlin intrinsics/metadata, and it
is a stable artifact both copies agree on in practice.

Deliberately *not* vendoring an image-loading library (Glide, Coil, ...)
for the same reason, the hard way: unlike OkHttp's main API surface,
Glide's own consumer ProGuard rules force it to keep several of its
classes under their real names in *any* app that uses it (including,
almost certainly, Twitch's own bundled copy) -- so vendoring Glide here
reintroduced exactly the collision this whole vendoring strategy exists
to avoid, causing a same-name-different-version class to silently win at
runtime. Any other full-featured
image library carries the same category of risk to some degree.
Emote image decoding instead uses only platform classes
(`android.graphics.ImageDecoder`/`Movie`/`BitmapFactory`, see
`EmoteImageCache`), which can never collide with anything Twitch bundles,
plus this same vendored OkHttp for the actual network fetch + its own
`okhttp3.Cache` for on-disk persistence.
"""

import os
import shutil
import urllib.request
import zipfile
from pathlib import Path

from .config import BIN_DIR, Env, LIBS_DIR, MOD_DIR, WORK_DIR
from .proc import run

MOD_BUILD_DIR = WORK_DIR / "mod_build"

# --- Vendored-OkHttp/Okio relocation (shading) -----------------------------
# Private package the real okhttp3.*/okio.* get moved into so they can never
# clash with Twitch's own bundled OkHttp (see the module docstring for the
# NoSuchMethodError this fixes). Kotlin stdlib is intentionally NOT relocated.
RELOCATED_PACKAGE = "tv.retwitch.vendor"
RELOCATION_RULES = (
    f"rule okhttp3.** {RELOCATED_PACKAGE}.okhttp3.@1\n"
    f"rule okio.** {RELOCATED_PACKAGE}.okio.@1\n"
)
# Which vendored jars get relocated. kotlin-stdlib stays shared, so it is
# absent here and passed through to d8 untouched.
RELOCATED_JAR_NAMES = ["okhttp-4.12.0.jar", "okio-jvm-3.6.0.jar"]

# AppMetrica alone gets a private kotlin stdlib: Twitch's own (shared, R8-shrunk)
# kotlin classes win at runtime and lack methods Kotlin libraries call (31.8.0:
# Bugsnag, the previous crash reporter, died on kotlin.Pair.component1() ->
# NoSuchMethodError). Our code talks to AppMetrica through Java types only, so
# only AppMetrica + a copy of the stdlib are rewritten; OkHttp and our classes
# keep the shared stdlib.
SDK_KOTLIN_RULES = f"rule kotlin.** {RELOCATED_PACKAGE}.kotlin.@1\n"
KOTLIN_STDLIB_JAR_NAME = "kotlin-stdlib-2.0.0.jar"

# Statistics + crash reports (see CrashReporter): classes.jar of the
# io.appmetrica.analytics:*:8.6.0 AARs from Maven Central, renamed
# appmetrica-<artifact>-8.6.0.jar. Only what `analytics` references directly.
# Left out on purpose, AppMetrica looks them up by class name and runs without
# them: identifiers (advertising id), location, screenshot, billing,
# ad-revenue, product-flow, remote-permissions, reporter-extension, ndk-crashes
# (native .so this dex-only build can't ship) and network-okhttp (would need
# unrelocated okhttp3, which clashes with Twitch's). installreferrer and
# play-services-appset are checked with Class.forName before use.
# The AAR manifest's AppMetricaService is added by CrashReporterPatch.kt.
APPMETRICA_JAR_NAMES = [
    f"appmetrica-{name}-8.6.0.jar"
    for name in (
        "analytics",
        "analytics-appsetid",
        "analytics-common-logger",
        "analytics-core-api",
        "analytics-core-utils",
        "analytics-location-api",
        "analytics-logger",
        "analytics-modules-api",
        "analytics-ndkcrashes-api",
        "analytics-network",
        "analytics-network-api",
        "analytics-network-legacy",
        "analytics-network-tasks",
        "analytics-proto",
    )
]

# Relocation tool: pantsbuild's jarjar fork + ASM 7 (reads Java 8 bytecode,
# which is what we compile to). Fetched on demand into BIN_DIR/jarjar. Only
# jarjar's `process` CLI is used (ant is needed for its ant task only, so it
# is not fetched). Override the whole dir with RETWITCH_JARJAR_DIR for offline
# builds.
_JARJAR_DIR = BIN_DIR / "jarjar"
_JARJAR_MAIN = "org.pantsbuild.jarjar.Main"
_MAVEN = "https://repo1.maven.org/maven2"
_JARJAR_TOOL_JARS = [
    (f"{_MAVEN}/org/pantsbuild/jarjar/1.7.2", "jarjar-1.7.2.jar"),
    (f"{_MAVEN}/org/ow2/asm/asm/7.0", "asm-7.0.jar"),
    (f"{_MAVEN}/org/ow2/asm/asm-tree/7.0", "asm-tree-7.0.jar"),
    (f"{_MAVEN}/org/ow2/asm/asm-commons/7.0", "asm-commons-7.0.jar"),
]

# Real, unmodified library jars merged into the extension dex alongside our
# own sources (see module docstring for why). Keep this list to what's
# actually reachable from mod/src -- every extra jar is extra dex size.
VENDOR_JAR_NAMES = [
    # HTTP client for the emote API/CDN fetchers (and disk cache for
    # fetched images, via okhttp3.Cache -- see EmoteHttp).
    "okhttp-4.12.0.jar",
    "okio-jvm-3.6.0.jar",
    "kotlin-stdlib-2.0.0.jar",
    # AppMetrica (APPMETRICA_JAR_NAMES) keeps its own `io.appmetrica.*`
    # packages: Twitch bundles none of it.
    *APPMETRICA_JAR_NAMES,
]

# Jars needed only on the javac *compile* classpath, never merged into the
# dex. AppMetrica's public method signatures carry androidx.annotation
# (@NonNull/@Nullable, CLASS retention) annotations; javac wants the types
# resolvable while reading them. The classes themselves already exist in
# Twitch's own runtime dex, so d8-merging our own copy would only risk a
# duplicate-class clash -- hence compile-only.
COMPILE_ONLY_JAR_NAMES = [
    "annotation-jvm-1.8.2.jar",
]

# A handful of these AndroidX/Kotlin artifacts publish as Kotlin
# Multiplatform: the "bare" artifact name (e.g. `collection-1.4.5.jar`,
# `annotation-1.8.2.jar`, `kotlin-stdlib-jdk8-2.0.0.jar`) resolves to a
# near-empty common-metadata jar (klib linkdata, no real .class files --
# Gradle's dependency resolution picks the right platform variant behind
# the scenes via module metadata, but a plain `curl` by version number
# does not). The real JVM/Android classes live under a `-jvm`/`-android`
# classifier instead (fetched from Google's Maven, e.g.
# https://dl.google.com/android/maven2/androidx/collection/collection-jvm/1.4.5/collection-jvm-1.4.5.jar).
# This bit us once already: a plain `collection-1.4.5.jar` silently
# compiled and `d8`-merged fine (0 classes in, 0 classes out, no error),
# but crashed at runtime with `NoClassDefFoundError: androidx/collection/ArrayMap`
# the first time Glide actually touched it. If you add a new vendor jar
# here, check it actually contains `.class` files before trusting it:
#   unzip -l kitchen/libs/<name>.jar | grep -c '\.class$'


def find_sources(src_dir: Path) -> list[Path]:
    return sorted(src_dir.rglob("*.java"))


def vendor_jars() -> list[Path]:
    jars = []
    for name in VENDOR_JAR_NAMES:
        path = LIBS_DIR / name
        if not path.exists():
            raise FileNotFoundError(
                f"Missing vendored dependency {path}. Run `python3 -m kitchen.deps` in kitchen/ "
                "(pinned in kitchen/deps.py)."
            )
        jars.append(path)
    return jars


def compile_only_jars() -> list[Path]:
    jars = []
    for name in COMPILE_ONLY_JAR_NAMES:
        path = LIBS_DIR / name
        if not path.exists():
            raise FileNotFoundError(
                f"Missing compile-only dependency {path}. Run `python3 -m kitchen.deps` in kitchen/ "
                "(pinned in kitchen/deps.py)."
            )
        jars.append(path)
    return jars


def _jarjar_dir() -> Path:
    override = os.environ.get("RETWITCH_JARJAR_DIR")
    return Path(override) if override else _JARJAR_DIR


def _ensure_reloc_tools() -> list[Path]:
    """Fetch (once) the jarjar + ASM jars used for relocation; return their classpath."""
    tool_dir = _jarjar_dir()
    tool_dir.mkdir(parents=True, exist_ok=True)
    cp: list[Path] = []
    for base, name in _JARJAR_TOOL_JARS:
        dest = tool_dir / name
        if not dest.exists():
            url = f"{base}/{name}"
            print(f"Downloading relocation tool {url} ...")
            with urllib.request.urlopen(url) as resp:
                dest.write_bytes(resp.read())
        cp.append(dest)
    return cp


def _jar_from_dir(classes_dir: Path, out_jar: Path) -> None:
    """Zip a compiled-classes tree into a jar jarjar can read."""
    with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(classes_dir.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(classes_dir).as_posix())


def _relocate_jar(env: Env, tool_cp: list[Path], rules_file: Path, in_jar: Path, out_jar: Path,
                  moved: tuple[str, ...] = ("okhttp3/", "okio/")) -> Path:
    """Run jarjar `process` with `rules_file` on `in_jar` -> `out_jar` (`moved`: packages that must be gone)."""
    if out_jar.exists():
        out_jar.unlink()
    run(
        [
            str(env.java),
            "-cp",
            os.pathsep.join(str(j) for j in tool_cp),
            _JARJAR_MAIN,
            "process",
            str(rules_file),
            str(in_jar),
            str(out_jar),
        ]
    )
    if not out_jar.exists():
        raise FileNotFoundError(f"jarjar did not produce {out_jar}")
    # jarjar silently *drops* any class it can't parse (e.g. bytecode newer
    # than the bundled ASM understands), which would turn into a runtime
    # ClassNotFoundError. Guard against a relocation that lost the real,
    # renamed packages entirely.
    with zipfile.ZipFile(out_jar) as zf:
        names = zf.namelist()
    if any(n.startswith(moved) and n.endswith(".class") for n in names):
        raise RuntimeError(f"{out_jar.name}: relocation left un-shaded {moved} classes behind")
    return out_jar


def build_mod_dex(env: Env) -> Path:
    src_dir = MOD_DIR / "src"
    sources = find_sources(src_dir)
    if not sources:
        raise FileNotFoundError(f"No .java sources found under {src_dir}")

    if MOD_BUILD_DIR.exists():
        shutil.rmtree(MOD_BUILD_DIR)
    classes_dir = MOD_BUILD_DIR / "classes"
    dex_dir = MOD_BUILD_DIR / "dex"
    reloc_dir = MOD_BUILD_DIR / "reloc"
    classes_dir.mkdir(parents=True)
    dex_dir.mkdir(parents=True)
    reloc_dir.mkdir(parents=True)

    javac = shutil.which("javac")
    if not javac:
        raise FileNotFoundError("Could not find `javac` on PATH (needs a JDK).")

    jars = vendor_jars()
    # Compile-only jars (androidx.annotation for AppMetrica's annotated
    # signatures) are on the javac classpath but must NOT be dexed -- see
    # COMPILE_ONLY_JAR_NAMES.
    compile_cp = jars + compile_only_jars()

    # Compile our sources against the *original* okhttp3.*/okio.* -- mod/src
    # imports the real package names; the relocation below rewrites those
    # references in our compiled bytecode, so the source never mentions the
    # private vendor package.
    run(
        [
            javac,
            "-source",
            "8",
            "-target",
            "8",
            "-encoding",
            "UTF-8",
            "-nowarn",
            "-bootclasspath",
            # core-lambda-stubs (next to d8, as AGP does) lets javac emit
            # lambdas; d8 desugars them, so nothing extra ships.
            os.pathsep.join(str(j) for j in [env.android_jar, env.d8.parent / "core-lambda-stubs.jar"]
                            if j.exists()),
            "-cp",
            os.pathsep.join(str(j) for j in compile_cp),
            "-d",
            str(classes_dir),
            *[str(s) for s in sources],
        ]
    )

    # Relocate okhttp3.*/okio.* out of their real packages so they can't clash
    # with Twitch's own bundled (R8-mangled) OkHttp at runtime. jarjar rewrites
    # references inside *every* input class, so both the libraries and our own
    # compiled classes end up pointing at tv.retwitch.vendor.*.
    tool_cp = _ensure_reloc_tools()
    rules_file = MOD_BUILD_DIR / "jarjar-rules.txt"
    rules_file.write_text(RELOCATION_RULES, encoding="UTF-8")

    mod_classes_jar = MOD_BUILD_DIR / "mod-classes.jar"
    _jar_from_dir(classes_dir, mod_classes_jar)

    reloc_inputs = [mod_classes_jar] + [LIBS_DIR / name for name in RELOCATED_JAR_NAMES]
    reloc_outputs = [
        _relocate_jar(env, tool_cp, rules_file, in_jar, reloc_dir / in_jar.name)
        for in_jar in reloc_inputs
    ]

    sdk_rules = MOD_BUILD_DIR / "jarjar-rules-sdk.txt"
    sdk_rules.write_text(SDK_KOTLIN_RULES, encoding="UTF-8")
    for name in APPMETRICA_JAR_NAMES:
        reloc_outputs.append(_relocate_jar(env, tool_cp, sdk_rules, LIBS_DIR / name,
                                           reloc_dir / name, moved=("kotlin/",)))
    reloc_outputs.append(_relocate_jar(env, tool_cp, sdk_rules, LIBS_DIR / KOTLIN_STDLIB_JAR_NAME,
                                       reloc_dir / "kotlin-stdlib-sdk.jar", moved=("kotlin/",)))

    # Jars that are NOT relocated (the shared kotlin stdlib) go straight to d8 untouched.
    relocated_names = set(RELOCATED_JAR_NAMES) | set(APPMETRICA_JAR_NAMES)
    passthrough_jars = [LIBS_DIR / name for name in VENDOR_JAR_NAMES if name not in relocated_names]

    run(
        [
            str(env.d8),
            "--release",
            "--output",
            str(dex_dir),
            "--lib",
            str(env.android_jar),
            *[str(j) for j in reloc_outputs],
            *[str(j) for j in passthrough_jars],
        ]
    )

    out = dex_dir / "classes.dex"
    if not out.exists():
        raise FileNotFoundError(f"d8 did not produce {out}")
    return out
