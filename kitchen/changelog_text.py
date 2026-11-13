#!/usr/bin/env python3
"""Writes the current mod version's changelog (RU + EN) as plain text.

Reads BuildInfo.VERSION_NAME and that version's entry from Changelog.ENTRIES
({version, Russian items, English items}, items joined with "\\n").

Usage:
  changelog_text.py OUT_DIR     # writes OUT_DIR/ReTwitch_<version>_changelog.txt, prints its path
  changelog_text.py --version   # prints the version only
  changelog_text.py --selftest
"""
import os
import re
import sys

MOD = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mod", "src", "tv", "retwitch", "mod")


def version(build_info_src):
    m = re.search(r'VERSION_NAME\s*=\s*"([^"]+)"', build_info_src)
    if not m:
        raise SystemExit("VERSION_NAME not found in BuildInfo.java")
    return m.group(1)


def entry(changelog_src, ver):
    """The {version, ru, en} entry as three strings, Java literals and escapes resolved."""
    start = changelog_src.find('{"%s",' % ver)
    if start < 0:
        raise SystemExit("no Changelog entry for " + ver)
    args, current, i = [], [], start + 1
    while i < len(changelog_src):
        ch = changelog_src[i]
        if ch == '"':
            j, buf = i + 1, []
            while changelog_src[j] != '"':
                if changelog_src[j] == "\\":
                    nxt = changelog_src[j + 1]
                    buf.append({"n": "\n", "t": "\t"}.get(nxt, nxt))
                    j += 2
                else:
                    buf.append(changelog_src[j])
                    j += 1
            current.append("".join(buf))
            i = j + 1
            continue
        if ch == "," or ch == "}":
            args.append("".join(current))
            current = []
            if ch == "}":
                break
        i += 1
    if len(args) < 3:
        raise SystemExit("malformed Changelog entry for " + ver)
    return args[0], args[1], args[2]


def render(ver, ru, en):
    def items(text):
        return "\n".join("- " + line for line in text.split("\n") if line.strip())
    return "ReTwitch %s\n\nRU:\n%s\n\nEN:\n%s\n" % (ver, items(ru), items(en))


def selftest():
    src = '''ENTRIES = {
            {"1.0", "Первое \\"в кавычках\\"\\n"
                    + "Второе", "First\\n" + "Second, with a comma"},
            {"0.9", "Старое", "Old"},'''
    ver, ru, en = entry(src, "1.0")
    assert ver == "1.0", ver
    assert ru == 'Первое "в кавычках"\nВторое', ru
    assert en == "First\nSecond, with a comma", en
    assert render(ver, ru, en) == ('ReTwitch 1.0\n\nRU:\n- Первое "в кавычках"\n- Второе\n\n'
                                   'EN:\n- First\n- Second, with a comma\n')
    assert version('String VERSION_NAME = "1.0";') == "1.0"
    print("changelog_text selftest OK")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--selftest":
        selftest()
        return
    with open(os.path.join(MOD, "BuildInfo.java"), encoding="utf-8") as f:
        ver = version(f.read())
    if len(sys.argv) > 1 and sys.argv[1] == "--version":
        print(ver)
        return
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    with open(os.path.join(MOD, "Changelog.java"), encoding="utf-8") as f:
        _, ru, en = entry(f.read(), ver)
    out = os.path.join(sys.argv[1], "ReTwitch_%s_changelog.txt" % ver)
    with open(out, "w", encoding="utf-8") as f:
        f.write(render(ver, ru, en))
    print(out)


if __name__ == "__main__":
    main()
