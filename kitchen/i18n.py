#!/usr/bin/env python3
"""Translations of the mod's UI beyond Russian and English.

Every UI text is a Java pair (Russian, English) that ends up in Strings.pick(ru, en): t("...", "..."),
new Toggle(key, "...", "...", ...), .about("...", "...") and so on. This script finds such pairs (a literal
with Cyrillic, a comma, a literal without it) and keeps them in kitchen/i18n/<lang>.csv for a
translator: columns en, ru, <lang>, where. The mod looks a translation up by the English text, so a
changed English string loses its translation until it is translated again.

Usage:
  i18n.py export LANG              # create/refresh i18n/LANG.csv, keeping translations already there
  i18n.py pull LANG ACCEPTED.csv   # merge an export of accepted suggestions (en, LANG) into i18n/LANG.csv
  i18n.py pack OUT.json [CATALOG.json]
      # all i18n/*.csv -> {"lang": {"en": "translation"}}; the catalog is [[en, ru, where], ...] for the
      # in-app translation files (the build calls this)
  i18n.py --selftest
"""
import csv
import glob
import json
import os
import re
import sys

KITCHEN = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(KITCHEN, "mod", "src")
I18N = os.path.join(KITCHEN, "i18n")
# Version history stays English for other languages.
SKIP_FILES = {"Changelog.java"}
CYRILLIC = re.compile("[а-яА-ЯёЁ]")
ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "s": " ", '"': '"', "'": "'", "\\": "\\"}


def tokens(src):
    """(kind, value, line) for Java source: 'str' (decoded literal), 'op' (one char), 'word'; comments dropped."""
    out = []
    i, line, n = 0, 1, len(src)
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
        elif c.isspace():
            i += 1
        elif src.startswith("//", i):
            i = src.find("\n", i)
            i = n if i < 0 else i
        elif src.startswith("/*", i):
            end = src.find("*/", i + 2)
            end = n if end < 0 else end + 2
            line += src.count("\n", i, end)
            i = end
        elif src.startswith('"""', i):
            raise ValueError("text block at line %d: not supported" % line)
        elif c in "\"'":
            start_line, j, buf = line, i + 1, []
            while src[j] != c:
                if src[j] == "\\":
                    e = src[j + 1]
                    if e == "u":
                        k = j + 1
                        while src[k] == "u":
                            k += 1
                        buf.append(chr(int(src[k:k + 4], 16)))
                        j = k + 4
                        continue
                    if e in "01234567":
                        m = re.match("[0-7]{1,3}", src[j + 1:])
                        buf.append(chr(int(m.group(0), 8)))
                        j += 1 + len(m.group(0))
                        continue
                    buf.append(ESCAPES[e])
                    j += 2
                else:
                    buf.append(src[j])
                    j += 1
            out.append(("str" if c == '"' else "char", "".join(buf), start_line))
            i = j + 1
        elif c.isalnum() or c in "_$":
            j = i
            while j < n and (src[j].isalnum() or src[j] in "_$."):
                j += 1
            out.append(("word", src[i:j], line))
            i = j
        else:
            out.append(("op", c, line))
            i += 1
    return out


def expressions(toks):
    """Splits the token stream into comma/paren-delimited pieces: (text or None if not literal-only, line, has_str)."""
    pieces, cur = [], []
    for t in toks:
        if t[0] == "op" and t[1] in ",();{}":
            pieces.append((cur, t[1]))
            cur = []
        else:
            cur.append(t)
    pieces.append((cur, None))
    out = []
    for cur, end in pieces:
        if not cur:
            out.append((None, 0, False, end))
            continue
        literal = all(t[0] == "str" if k % 2 == 0 else t == ("op", "+", t[2]) for k, t in enumerate(cur)) \
            and len(cur) % 2 == 1
        has_str = any(t[0] == "str" for t in cur)
        text = "".join(t[1] for t in cur if t[0] == "str") if literal else None
        has_cyr = any(t[0] == "str" and CYRILLIC.search(t[1]) for t in cur)
        out.append((text, cur[0][2], has_cyr, end))
    return out


def pairs(src):
    """(ru, en, line) pairs and lines of Cyrillic text that is not in such a pair (glued with code: needs %s).

    Arguments go as N Russian literals, then N English ones: (ru, en) or compare(ru1, ru2, en1, en2)."""
    exprs = expressions(tokens(src))
    found, loose = [], []
    k = 0
    while k < len(exprs):
        n = 0
        while k + n < len(exprs) and exprs[k + n][2] and exprs[k + n][0] is not None and exprs[k + n][3] == ",":
            n += 1
        en = exprs[k + n:k + 2 * n]
        if n and len(en) == n and all(e[0] is not None and not e[2] for e in en) \
                and all(e[3] == "," for e in en[:-1]):
            found += [(exprs[k + i][0], en[i][0], exprs[k + i][1]) for i in range(n)]
            k += 2 * n
            continue
        if exprs[k][2]:
            loose.append(exprs[k][1])
        k += 1
    return found, loose


def scan():
    """{en: {"ru": ..., "where": [file:line]}} over the mod sources, plus unpaired Cyrillic locations."""
    strings, loose = {}, []
    for path in sorted(glob.glob(os.path.join(SRC, "**", "*.java"), recursive=True)):
        if os.path.basename(path) in SKIP_FILES:
            continue
        with open(path, encoding="utf-8") as f:
            src = f.read()
        rel = os.path.relpath(path, SRC).replace(os.sep, "/").removeprefix("tv/retwitch/mod/")
        found, bad = pairs(src)
        for ru, en, line in found:
            entry = strings.setdefault(en, {"ru": ru, "where": []})
            entry["where"].append("%s:%d" % (rel, line))
        loose += ["%s:%d" % (rel, line) for line in bad]
    return strings, loose


def read_csv(path, lang):
    if not os.path.exists(path):
        return {}
    # utf-8-sig: spreadsheets save the CSV with a BOM.
    with open(path, encoding="utf-8-sig", newline="") as f:
        return {row["en"]: row.get(lang, "") for row in csv.DictReader(f)}


def write_csv(path, lang, strings, translations):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["en", "ru", lang, "where"])
        for en, entry in strings.items():
            w.writerow([en, entry["ru"], translations.get(en, ""), " ".join(entry["where"])])


def export(lang):
    strings, loose = scan()
    path = os.path.join(I18N, lang + ".csv")
    old = read_csv(path, lang)
    write_csv(path, lang, strings, old)
    dropped = [en for en, tr in old.items() if tr and en not in strings]
    done = sum(1 for en in strings if old.get(en))
    print("%s: %d strings, %d translated" % (path, len(strings), done))
    for en in dropped:
        print("dropped (English text changed or removed): %r -> %r" % (en, old[en]))
    if loose:
        print("Cyrillic outside a (ru, en) pair, not exported (glued with code? use %%s): %s" % " ".join(loose))


PLACEHOLDER = re.compile(r"%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[sdfxc%n]")


def placeholders(text):
    return sorted(m.group(0) for m in PLACEHOLDER.finditer(text))


def plain(text):
    return (text.replace("\u2014", "-").replace("\u2013", "-").replace("\u2026", "...").replace("\u2192", ">")
            .replace("\u200b", ""))


def pull(lang, accepted_path):
    """Accepted suggestions from the site into i18n/LANG.csv; Russian lives in the Java sources, so it is only listed."""
    strings, _ = scan()
    # The site keeps what translators typed; public texts use plain typography (no long dashes, ellipsis or arrows).
    accepted = {plain(en): plain(text) for en, text in read_csv(accepted_path, lang).items()}
    if lang == "ru":
        for en, text in accepted.items():
            if en in strings and text and text != strings[en]["ru"]:
                print("%s\n  %r -> %r" % (" ".join(strings[en]["where"]), strings[en]["ru"], text))
        return
    path = os.path.join(I18N, lang + ".csv")
    current = read_csv(path, lang)
    changed = 0
    for en, text in accepted.items():
        if not text.strip():
            continue
        if en not in strings:
            print("skipped, no such English text any more: %r" % en)
        elif placeholders(en) != placeholders(text):
            print("skipped, placeholders differ: %r -> %r" % (en, text))
        elif current.get(en) != text:
            current[en] = text
            changed += 1
    write_csv(path, lang, strings, current)
    print("%s: %d strings changed" % (path, changed))


def catalog():
    strings, _ = scan()
    return [[en, entry["ru"], " ".join(entry["where"])] for en, entry in strings.items()]


def pack(out, catalog_out=None):
    if catalog_out:
        with open(catalog_out, "w", encoding="utf-8") as f:
            json.dump(catalog(), f, ensure_ascii=False, separators=(",", ":"))
    with open(out, "w", encoding="utf-8") as f:
        json.dump(packed(), f, ensure_ascii=False, separators=(",", ":"))


def packed():
    """Translations whose %s/%d set differs from the English are left out: String.format would throw."""
    data = {}
    for path in sorted(glob.glob(os.path.join(I18N, "*.csv"))):
        lang = os.path.splitext(os.path.basename(path))[0]
        data[lang] = {}
        for en, tr in read_csv(path, lang).items():
            if not tr.strip():
                continue
            if placeholders(en) != placeholders(tr):
                print("[i18n] %s: placeholders differ, left in English: %r -> %r" % (lang, en, tr))
                continue
            data[lang][en] = tr
    return data


def selftest():
    src = '''
        // t("Комментарий", "Comment")
        label(t("Реклама", "An ad"), 1, 2);
        new Toggle("key", "Объявления", "Announcements", false)
            .about("Строка \\"в кавычках\\"\\n" + "дальше",
                    "Quoted \\"line\\"\\n" + "next");
        x = pick("Скачано " + n + "%", "Downloaded " + n + "%");
        y = pick("%d мин", "%d min");
        z = "Только русский";
        c.compare("До", "После", "Before", "After");
    '''
    found, loose = pairs(src)
    assert [(r, e) for r, e, _ in found] == [
        ("Реклама", "An ad"),
        ("Объявления", "Announcements"),
        ('Строка "в кавычках"\nдальше', 'Quoted "line"\nnext'),
        ("%d мин", "%d min"),
        ("До", "Before"),
        ("После", "After"),
    ], found
    assert len(loose) == 2, loose  # glued with n, and the lone Russian literal
    assert found[2][2] == 5, found[2]
    assert placeholders("Level 2, 45% to go") == [] and placeholders("50%% de %d") == ["%%", "%d"]
    assert placeholders("%s banned %s%s") == placeholders("%s baneó a %s%s")
    import tempfile
    global I18N
    saved = I18N, globals()["scan"]
    with tempfile.TemporaryDirectory() as tmp:
        I18N = tmp
        globals()["scan"] = lambda: ({"Ban %s": {"ru": "Бан %s", "where": ["A.java:1"]},
                                      "Speed": {"ru": "Скорость", "where": ["B.java:2"]}}, [])
        accepted = os.path.join(tmp, "accepted.csv")
        with open(accepted, "w", encoding="utf-8-sig", newline="") as f:
            csv.writer(f).writerows([["en", "de"], ["Ban %s", "Bannen"], ["Speed", "Tempo"], ["Gone", "Weg"]])
        pull("de", accepted)
        assert read_csv(os.path.join(tmp, "de.csv"), "de") == {"Ban %s": "", "Speed": "Tempo"}
        I18N, globals()["scan"] = saved
    print("i18n selftest ok")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--selftest"]:
        selftest()
    elif len(args) == 2 and args[0] == "export" and re.fullmatch("[a-z]{2,3}", args[1]) and args[1] not in ("ru", "en"):
        export(args[1])
    elif len(args) == 3 and args[0] == "pull" and re.fullmatch("[a-z]{2,3}", args[1]) and args[1] != "en":
        pull(args[1], args[2])
    elif len(args) in (2, 3) and args[0] == "pack":
        pack(*args[1:])
    else:
        sys.exit(__doc__)
