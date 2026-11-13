#!/usr/bin/env python3
"""Repair styled-string annotation span offsets in a patched APK's resources.arsc.

## Why this exists

morphe-cli recompiles the resource table whenever a patch touches resources
(the mandatory one is the package rename). That recompile round-trips the
global string pool and *miscomputes the character offsets of styled-string
annotation spans*: every self-closing ``<annotation .../>`` span that wraps the
single U+FFFC (object-replacement) placeholder -- i.e. the ``image=`` inline
icons on Cheer / community-points / etc. titles -- collapses from an inclusive
``(N, N)`` (one char) to a degenerate ``(N, N-1)`` (``lastChar < firstChar``).

Android's styled-resource loader discards such degenerate spans, so
``Context.getText(id)`` comes back without the ``Annotation`` span, Twitch's
``getSpans(Annotation).first()`` sees an empty array and throws
``NoSuchElementException: Array is empty.`` -- an uncaught crash reproduced
across every manufacturer/OS version, because the corruption is baked into our
``resources.arsc`` (device-independent). See
``mod/src/tv/retwitch/mod/AnnotationSpanGuard.java`` and
``retwitch-patches/src/AnnotationSpanGuardPatch.kt`` for the runtime guard; this
script fixes the *root cause* so the icons also render in the right place.

## What it does

Using the pre-patch APK (whose arsc APKEditor/ARSCLib wrote *correctly*) as a
reference, it rewrites the patched APK's arsc span offsets back to the originals.
The repair is a pure in-place overwrite of the 4-byte ``firstChar``/``lastChar``
fields inside the string pool's style records -- no string bytes move, so the
arsc length, every zip entry offset and the whole-file alignment are preserved
(only the ``resources.arsc`` zip CRC-32 is refreshed). The caller must re-sign
afterwards, since editing invalidates the v2/v3 signature.

Matching is by (decoded string value, tuple of span tag names): robust to
morphe reordering the pool or renaming classes, and it never touches a span
whose string content changed.
"""

from __future__ import annotations

import struct
import sys
import zipfile
import zlib
from dataclasses import dataclass


@dataclass
class Span:
    tag: str
    first: int
    last: int
    record_off: int  # byte offset of this span record (name field) within the arsc


@dataclass
class Styled:
    value: str
    spans: list


def _parse_string_pool(data: bytes):
    """Parse the global string pool (first chunk after the table header).

    Returns (styled_entries, decode) where styled_entries is a list of Styled
    for the first ``styleCount`` strings (the styled ones), each span carrying
    its absolute record offset within ``data`` for in-place editing.
    """
    table_type, table_hs = struct.unpack_from("<HH", data, 0)
    if table_type != 0x0002:
        raise ValueError("not a resource table (type=0x%04x)" % table_type)

    off = table_hs
    chunk_type, _hs, _sz = struct.unpack_from("<HHI", data, off)
    if chunk_type != 0x0001:
        raise ValueError("first chunk after table header is not a string pool")

    str_count, style_count, flags, strings_start, styles_start = struct.unpack_from(
        "<IIIII", data, off + 8
    )
    utf8 = bool(flags & 0x100)
    str_off_base = off + 28
    style_off_base = off + 28 + str_count * 4

    def string_pos(i):
        return off + strings_start + struct.unpack_from("<I", data, str_off_base + i * 4)[0]

    def style_pos(i):
        return off + styles_start + struct.unpack_from("<I", data, style_off_base + i * 4)[0]

    def _len_u8(p):
        n = data[p]
        p += 1
        if n & 0x80:
            n = ((n & 0x7F) << 8) | data[p]
            p += 1
        return n, p

    def _len_u16(p):
        n = struct.unpack_from("<H", data, p)[0]
        p += 2
        if n & 0x8000:
            n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
            p += 2
        return n, p

    def decode(i):
        p = string_pos(i)
        if utf8:
            _clen, p = _len_u8(p)
            blen, p = _len_u8(p)
            return data[p : p + blen].decode("utf-8", "replace")
        clen, p = _len_u16(p)
        return data[p : p + clen * 2].decode("utf-16-le", "replace")

    styled = []
    for i in range(style_count):
        p = style_pos(i)
        spans = []
        while True:
            name, first, last = struct.unpack_from("<III", data, p)
            if name == 0xFFFFFFFF:
                break
            spans.append(Span(decode(name), first, last, p))
            p += 12
        if spans:
            styled.append(Styled(decode(i), spans))
    return styled


def _reference_map(ref_arsc: bytes):
    """key=(value, (tag, ...)) -> [(first, last), ...] from the correct arsc."""
    ref = {}
    for s in _parse_string_pool(ref_arsc):
        key = (s.value, tuple(sp.tag for sp in s.spans))
        offsets = [(sp.first, sp.last) for sp in s.spans]
        prev = ref.get(key)
        if prev is not None and prev != offsets:
            # Same text + tags but different offsets: ambiguous. Keep the first;
            # this is not expected (identical text implies identical positions).
            continue
        ref[key] = offsets
    return ref


def repair_arsc(target_arsc: bytearray, ref_arsc: bytes):
    """Repair morphe-corrupted styled-span offsets in ``target_arsc`` in place.

    Two corruption modes are undone, using the pre-patch ``ref_arsc`` (whose spans
    were written correctly by APKEditor/ARSCLib) to tell morphe's damage apart
    from spans stock Twitch already ships degenerate:

    * **matched strings** (same decoded value + span tag names as in the
      reference) get their exact original ``(firstChar, lastChar)`` restored.
      This fixes the dominant self-closing-``<annotation/>`` collapse
      ``(N,N) -> (N,N-1)`` and, by construction, leaves untouched the handful of
      spans stock Twitch itself ships degenerate (they equal the reference).
    * **unmatched strings** (value changed because morphe stripped the
      whitespace-preserving double quotes, e.g. ``"%1$s" -> %1$s``, which also
      shifts later offsets) can't be compared to the reference, so any span that
      is degenerate (``lastChar < firstChar``) is repaired to ``lastChar =
      firstChar``. ``firstChar`` still correctly anchors the U+FFFC placeholder
      after the shift, so this restores exactly the intended one-char span.

    Returns (fixed_spans, matched_strings, unmatched_strings). Mutates
    ``target_arsc`` in place.
    """
    ref = _reference_map(ref_arsc)
    fixed = matched = unmatched = 0
    for s in _parse_string_pool(target_arsc):
        key = (s.value, tuple(sp.tag for sp in s.spans))
        good = ref.get(key)
        if good is not None and len(good) == len(s.spans):
            matched += 1
            for sp, (gf, gl) in zip(s.spans, good):
                if (sp.first, sp.last) != (gf, gl):
                    struct.pack_into("<II", target_arsc, sp.record_off + 4, gf, gl)
                    fixed += 1
        else:
            unmatched += 1
            for sp in s.spans:
                if sp.last < sp.first:
                    struct.pack_into("<II", target_arsc, sp.record_off + 4, sp.first, sp.first)
                    fixed += 1
    return fixed, matched, unmatched


def _find_arsc_in_zip(apk_bytes: bytes):
    """Locate resources.arsc: return (data_off, length, local_hdr_off, central_hdr_off).

    Raises if it is not a STORED (uncompressed) entry -- the in-place patch relies
    on the stored bytes being the literal arsc.
    """
    name = b"resources.arsc"

    # End of central directory record (scan back for the signature).
    eocd = apk_bytes.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("no EOCD found")
    cd_off = struct.unpack_from("<I", apk_bytes, eocd + 16)[0]
    cd_count = struct.unpack_from("<H", apk_bytes, eocd + 10)[0]

    p = cd_off
    for _ in range(cd_count):
        if apk_bytes[p : p + 4] != b"PK\x01\x02":
            raise ValueError("bad central directory header")
        method = struct.unpack_from("<H", apk_bytes, p + 10)[0]
        nlen = struct.unpack_from("<H", apk_bytes, p + 28)[0]
        elen = struct.unpack_from("<H", apk_bytes, p + 30)[0]
        clen = struct.unpack_from("<H", apk_bytes, p + 32)[0]
        lho = struct.unpack_from("<I", apk_bytes, p + 42)[0]
        entry_name = apk_bytes[p + 46 : p + 46 + nlen]
        if entry_name == name:
            if method != 0:
                raise ValueError("resources.arsc is not STORED (method=%d)" % method)
            # Local header: data starts after name+extra (extra len can differ
            # from the central one).
            l_nlen = struct.unpack_from("<H", apk_bytes, lho + 26)[0]
            l_elen = struct.unpack_from("<H", apk_bytes, lho + 28)[0]
            data_off = lho + 30 + l_nlen + l_elen
            comp_size = struct.unpack_from("<I", apk_bytes, lho + 18)[0]
            return data_off, comp_size, lho, p
        p += 46 + nlen + elen + clen
    raise ValueError("resources.arsc not found in central directory")


def fix_apk(ref_apk: str, in_apk: str, out_apk: str) -> int:
    """Repair ``in_apk``'s styled spans using ``ref_apk`` and write ``out_apk``.

    Returns the number of span offsets fixed. ``out_apk`` still needs re-signing.
    """
    with zipfile.ZipFile(ref_apk) as z:
        ref_arsc = z.read("resources.arsc")

    with open(in_apk, "rb") as f:
        apk = bytearray(f.read())

    data_off, length, lho, cho = _find_arsc_in_zip(apk)
    arsc = bytearray(apk[data_off : data_off + length])

    fixed, matched, unmatched = repair_arsc(arsc, ref_arsc)
    print(
        "[styled-span-fix] fixed=%d spans across matched=%d styled strings "
        "(unmatched=%d)" % (fixed, matched, unmatched)
    )
    if fixed == 0:
        # Nothing to do: write the input through unchanged so callers have a
        # single output path, but skip the CRC dance.
        with open(out_apk, "wb") as f:
            f.write(apk)
        return 0

    apk[data_off : data_off + length] = arsc
    new_crc = zlib.crc32(bytes(arsc)) & 0xFFFFFFFF
    struct.pack_into("<I", apk, lho + 14, new_crc)   # local file header CRC
    struct.pack_into("<I", apk, cho + 16, new_crc)   # central directory CRC

    with open(out_apk, "wb") as f:
        f.write(apk)
    return fixed


def main(argv):
    if len(argv) != 4:
        print(
            "usage: styled_span_fix.py <reference.apk> <patched-in.apk> <fixed-out.apk>",
            file=sys.stderr,
        )
        return 2
    fixed = fix_apk(argv[1], argv[2], argv[3])
    # Exit 10 == "nothing to repair" so the caller can skip the (otherwise
    # pointless) re-sign and keep morphe's still-valid signature. Any real
    # failure raises and surfaces as a non-zero exit instead.
    return 0 if fixed > 0 else 10


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
