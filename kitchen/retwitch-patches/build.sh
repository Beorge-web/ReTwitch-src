#!/usr/bin/env bash
# Builds retwitch-patches.jar: a morphe-cli-compatible patch bundle (.mpp).
#
# Unlike kitchen/patches/*.json (anchor-based find/replace on decoded
# smali text), patches here are ordinary Kotlin compiled against the
# morphe-patcher API bundled inside morphe-cli's "-all" jar. They locate
# their target methods by structural fingerprint (return type, parameter
# types, string constants) instead of by exact obfuscated smali text, so
# they keep working across Twitch releases that only shuffle R8's
# class/method names without changing the method's shape.
#
# Toolchain note: ReTwitch was migrated off the upstream ReVanced patcher
# to Morphe's fork (`app.morphe.patcher`, GPL-3.0 + §7 extra terms). API deltas:
# `apply {}` -> `execute {}`, `classBy{}?.mutableClass` ->
# `mutableClassDefBy{}`; the vendored helper catalogue is under
# retwitch-patches/src/vendor/.
#
# Prerequisites, fetched once by `python3 -m kitchen.deps` (pinned, SHA-256 checked):
#   kitchen/bin/morphe/morphe-desktop-1.20.0-all.jar (morphe-patcher 2.1.0)
#   kitchen/bin/kotlin24/kotlinc/bin/kotlinc (Kotlin 2.4, matches the
#     Kotlin metadata version morphe-patcher was compiled with)
#
# Usage:
#   ./build.sh              # build retwitch-patches.jar
#   ./build.sh --patch APK  # also apply it to APK -> APK.patched.apk (for smoke testing)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KITCHEN_DIR="$(dirname "$HERE")"
MORPHE_JAR="$KITCHEN_DIR/bin/morphe/morphe-desktop-1.20.0-all.jar"
# The patcher inside MORPHE_JAR, written to the bundle manifest like the official
# gradle plugin does. Patcher 2.x picks behaviour by it: >= 2.0.0 decodes
# resources on request instead of all up front.
MORPHE_PATCHER_VERSION="2.1.0"
KOTLINC="$KITCHEN_DIR/bin/kotlin24/kotlinc/bin/kotlinc"
OUT_DIR="$HERE/out"
JAR="$HERE/retwitch-patches.jar"
EXTENSION_DEX="$HERE/resources/retwitch-extension.dex"
# Fixed, persistent signing key -- *not* under kitchen/work/ (which
# `kitchen.py clean`/`rebuild.sh` wipe on every from-scratch rebuild).
# The first build without it lets morphe generate a key (see below) and keeps
# it here; keeping its path stable is what lets `adb install -r` keep working
# across rebuilds instead of hitting INSTALL_FAILED_UPDATE_INCOMPATIBLE from a
# fresh random key each time.
#
# NOTE: This is a *separate* keystore from the old revanced-cli one
# (keystore/retwitch.keystore, BouncyCastle format, incompatible entry
# alias). Migrating the signing toolchain changes the app signature once,
# so the first Morphe-built APK must be installed over a *fresh* uninstall
# (or a `--force` reinstall that clears data). Subsequent rebuilds reuse
# this key and stay install-compatible with each other.
KEYSTORE="$KITCHEN_DIR/keystore/retwitch-morphe.keystore"
KEYSTORE_ALIAS="ReTwitch"

(cd "$KITCHEN_DIR" && python3 -m kitchen.deps)

echo "== Building mod extension dex (SettingsHook, DiagProbe, ...) =="
# Subshell: `python3 -c` needs to run with cwd = $KITCHEN_DIR (its own
# module lookup assumes that), but the rest of this script -- notably the
# `--patch` argument below -- must keep resolving paths relative to
# wherever the *caller* invoked build.sh from, not $KITCHEN_DIR.
(cd "$KITCHEN_DIR" && python3 -c "
from kitchen.config import load_env
from kitchen.mod_builder import build_mod_dex
import shutil
env = load_env()
dex = build_mod_dex(env)
shutil.copy(dex, '$EXTENSION_DEX')
print('extension dex ->', '$EXTENSION_DEX')
") 2>&1 | grep -v '^\$'

echo "== Compiling patches (kotlinc) =="
rm -rf "$OUT_DIR"
# Vendored morphe-patches-library helpers live under src/vendor/ (GPL-3.0,
# see src/vendor/README for attribution); compiled alongside our patches.
"$KOTLINC" -Xskip-prerelease-check -jvm-target 17 \
    -cp "$MORPHE_JAR" \
    -d "$OUT_DIR" \
    "$HERE"/src/*.kt \
    "$HERE"/src/vendor/util/*.kt \
    "$HERE"/src/vendor/resources/*.kt

echo "== Packaging $JAR =="
cp "$EXTENSION_DEX" "$HERE/resources/retwitch-rn.js" "$OUT_DIR/"
python3 "$KITCHEN_DIR/i18n.py" pack "$OUT_DIR/retwitch-i18n.json" "$OUT_DIR/retwitch-i18n-catalog.json"
rm -f "$JAR"
printf 'Patcher-Version: %s\n' "$MORPHE_PATCHER_VERSION" > "$OUT_DIR.manifest"
(cd "$OUT_DIR" && jar cfm "$JAR" "$OUT_DIR.manifest" .)
rm -f "$OUT_DIR.manifest"
rm -f "$OUT_DIR/$(basename "$EXTENSION_DEX")" "$OUT_DIR/retwitch-rn.js" "$OUT_DIR/retwitch-i18n.json" \
    "$OUT_DIR/retwitch-i18n-catalog.json"

echo "== Patches in bundle =="
java -jar "$MORPHE_JAR" list-patches --patches "$JAR"

if [[ "${1:-}" == "--patch" && -n "${2:-}" ]]; then
    APK="$2"
    OUT_APK="${APK%.apk}.patched.apk"
    mkdir -p "$(dirname "$KEYSTORE")"
    # Keep morphe's scratch tree (hundreds of MB of extracted/rebuilt dex +
    # a full copy of the APK) OUT of the workspace. Left inside the repo it
    # (a) is written through the editor's filesystem-sandbox proxy and, more
    # importantly, (b) makes an IDE's file watcher/indexer churn on every
    # patch run, which is what makes the IDE feel like it "hangs" even
    # though the patch itself takes ~14s. morphe otherwise defaults this to
    # a JAR-adjacent `morphe-data/tmp/` and never cleans it up.
    TMP_DIR="${RETWITCH_TMP:-${TMPDIR:-/tmp}/retwitch-morphe}"
    mkdir -p "$TMP_DIR"
    # Older morphe left a ~700MB patching-<timestamp> tree per run (1.20 purges
    # its own by default); /tmp is often RAM.
    rm -rf "$TMP_DIR"/patching-*
    # morphe 1.20 refuses a --keystore that doesn't exist; without the flag it
    # creates morphe-data/morphe.keystore next to its jar (BKS, empty store
    # password, entry "Morphe" with password "Morphe"), which becomes ours below.
    KEYSTORE_ARGS=(--keystore="$KEYSTORE")
    MORPHE_KEYSTORE="$(dirname "$MORPHE_JAR")/morphe-data/morphe.keystore"
    NEW_KEY=""
    if [[ ! -f "$KEYSTORE" ]]; then
        if [[ -e "$MORPHE_KEYSTORE" ]]; then
            echo "ERROR: no $KEYSTORE, and $MORPHE_KEYSTORE already exists. Copy your key to" \
                 "$KEYSTORE, or move $MORPHE_KEYSTORE away to have a new one made." >&2
            exit 1
        fi
        KEYSTORE_ARGS=()
        NEW_KEY=1
    fi
    echo "== Patching $APK -> $OUT_APK (keystore: $KEYSTORE, tmp: $TMP_DIR) =="
    java -jar "$MORPHE_JAR" patch --patches "$JAR" --force \
        "${KEYSTORE_ARGS[@]}" --keystore-entry-alias="$KEYSTORE_ALIAS" \
        -t "$TMP_DIR" -o "$OUT_APK" "$APK"
    if [[ -n "$NEW_KEY" ]]; then
        keytool -changealias -keystore "$MORPHE_KEYSTORE" -storetype BKS -storepass "" -keypass Morphe \
            -alias Morphe -destalias "$KEYSTORE_ALIAS" \
            -provider org.bouncycastle.jce.provider.BouncyCastleProvider -providerpath "$MORPHE_JAR"
        mv "$MORPHE_KEYSTORE" "$KEYSTORE"
        echo "== New signing key: $KEYSTORE. Keep it: updates install only over an APK signed with it. =="
    fi

    # --- Styled-string annotation-span repair -------------------------------
    # morphe's resource recompile (triggered by the mandatory package-rename
    # patch) miscomputes styled-string span offsets: the self-closing
    # <annotation image=.../> spans that wrap the single U+FFFC placeholder on
    # Cheer / community-points / prediction titles collapse from (N,N) to a
    # degenerate (N,N-1). Android drops such spans, so getText() loses the
    # Annotation and Twitch's getSpans(Annotation).first() throws
    # NoSuchElementException on every OS version. The runtime AnnotationSpanGuard
    # patch stops the crash; this restores the spans so the icons also render.
    #
    # It's a pure in-place uint32 rewrite in resources.arsc (offsets/alignment
    # unchanged), using the pre-patch $APK as the correct reference -- which
    # invalidates morphe's signature, so we re-sign below with the very same
    # auto-generated keystore to keep the signer cert (and `adb install -r`)
    # identical to a plain morphe build.
    echo "== Repairing styled-string annotation spans (morphe arsc round-trip fix) =="
    FIXED_APK="$OUT_APK.spanfix"
    rm -f "$FIXED_APK"
    span_rc=0
    # Absolute script path + relative $APK/$OUT_APK left as-is: they resolve
    # against the *caller's* cwd (see the --patch note above), so we must NOT
    # cd into $KITCHEN_DIR here. The script is standalone (no kitchen imports).
    python3 "$KITCHEN_DIR/kitchen/styled_span_fix.py" "$APK" "$OUT_APK" "$FIXED_APK" || span_rc=$?
    if [[ "$span_rc" -eq 0 ]]; then
        # morphe's keystore is BKS with an empty store password and a "Morphe"
        # key password (morphe defaults since 1.9.1; build.sh passes neither, so
        # these are what morphe generated). apksigner can't read BKS without
        # BouncyCastle, so convert to a throwaway PKCS12 (BC provider is bundled
        # in the morphe jar) and sign that.
        RESIGN_STOREPASS="${RETWITCH_KEYSTORE_PASSWORD-}"
        RESIGN_KEYPASS="${RETWITCH_KEYSTORE_ENTRY_PASSWORD-Morphe}"

        APKSIGNER="$(command -v apksigner || true)"
        if [[ -z "$APKSIGNER" ]]; then
            for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" /opt/android-sdk; do
                [[ -n "$base" ]] || continue
                cand="$(ls -1 "$base"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
                [[ -n "$cand" ]] && { APKSIGNER="$cand"; break; }
            done
        fi
        if [[ -z "$APKSIGNER" ]]; then
            echo "ERROR: apksigner not found (need Android SDK build-tools; set ANDROID_HOME) --" \
                 "cannot re-sign the span-repaired APK." >&2
            exit 1
        fi

        P12="$TMP_DIR/retwitch-resign.p12"
        rm -f "$P12"
        keytool -importkeystore \
            -srckeystore "$KEYSTORE" -srcstoretype BKS \
            -srcstorepass "$RESIGN_STOREPASS" -srckeypass "$RESIGN_KEYPASS" -srcalias "$KEYSTORE_ALIAS" \
            -destkeystore "$P12" -deststoretype PKCS12 \
            -deststorepass retwitch -destkeypass retwitch -destalias "$KEYSTORE_ALIAS" \
            -provider org.bouncycastle.jce.provider.BouncyCastleProvider \
            -providerpath "$MORPHE_JAR" >/dev/null

        "$APKSIGNER" sign --ks "$P12" --ks-type PKCS12 \
            --ks-pass pass:retwitch --ks-key-alias "$KEYSTORE_ALIAS" --key-pass pass:retwitch \
            "$FIXED_APK"
        rm -f "$P12" "$FIXED_APK.idsig"
        mv -f "$FIXED_APK" "$OUT_APK"
        echo "== Styled spans repaired and APK re-signed -> $OUT_APK =="
    elif [[ "$span_rc" -eq 10 ]]; then
        rm -f "$FIXED_APK"
        echo "== No corrupted styled spans found -- keeping morphe's signed APK as-is =="
    else
        echo "ERROR: styled-span repair failed (exit $span_rc)." >&2
        exit 1
    fi

    # Release copy named by the mod version, with its changelog (RU + EN) beside it.
    MOD_VERSION="$(python3 "$KITCHEN_DIR/changelog_text.py" --version)"
    cp -f "$OUT_APK" "$(dirname "$OUT_APK")/ReTwitch_${MOD_VERSION}.apk"
    echo "== Release: $(dirname "$OUT_APK")/ReTwitch_${MOD_VERSION}.apk"
    echo "== Changelog: $(python3 "$KITCHEN_DIR/changelog_text.py" "$(dirname "$OUT_APK")")"
fi

echo "Done: $JAR"
