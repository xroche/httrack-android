#!/usr/bin/env python3
"""Rebuild app/src/main/res/raw/resources.zip, the in-app Help/docs bundle.

getResourceFile() unpacks this archive at first run. html/ and templates/ mirror
the pinned httrack engine; license/ is a static Android-only asset (the engine
ships the licence only as plain text). Re-run after bumping the httrack submodule
so refreshed docs, such as the Android help page, actually ship.

The unpacker creates no parent directories for file entries, so the archive must
carry an explicit directory entry ordered before every file it contains; a C sort
over the tree guarantees that. Output is deterministic (fixed mtime, sorted) so a
docs-only rebuild yields a minimal diff.
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENGINE = os.path.join(ROOT, "app", "src", "main", "jni", "httrack")
STATIC = os.path.join(ROOT, "tools", "resources")
OUT = os.path.join(ROOT, "app", "src", "main", "res", "raw", "resources.zip")

# (source tree, arc prefix). html/ and templates/ track the engine; license/ is ours.
SOURCES = [
    (os.path.join(ENGINE, "html"), "html"),
    (os.path.join(ENGINE, "templates"), "templates"),
    (os.path.join(STATIC, "license"), "license"),
]
EXCLUDE = {"Makefile.am"}
MTIME = (2026, 1, 1, 0, 0, 0)


def collect(src_root, prefix):
    """Yield (arcname, path-or-None); None marks a directory entry."""
    yield prefix + "/", None
    for dirpath, dirnames, filenames in os.walk(src_root):
        rel = os.path.relpath(dirpath, src_root)
        base = prefix if rel == "." else os.path.join(prefix, rel)
        for d in dirnames:
            yield os.path.join(base, d).replace(os.sep, "/") + "/", None
        for f in filenames:
            if f in EXCLUDE:
                continue
            yield os.path.join(base, f).replace(os.sep, "/"), os.path.join(dirpath, f)


def main():
    if not os.path.isdir(os.path.join(ENGINE, "html")):
        sys.exit("engine html/ missing — run: git submodule update --init --recursive")

    entries = {}
    for src_root, prefix in SOURCES:
        for arcname, path in collect(src_root, prefix):
            entries[arcname] = path

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        for arcname in sorted(entries):
            path = entries[arcname]
            info = zipfile.ZipInfo(arcname, date_time=MTIME)
            if path is None:
                info.external_attr = (0o40755 << 16) | 0x10
                z.writestr(info, b"")
            else:
                info.external_attr = 0o644 << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(path, "rb") as fh:
                    z.writestr(info, fh.read())

    files = sum(1 for p in entries.values() if p is not None)
    print(f"wrote {OUT} ({files} files)")


if __name__ == "__main__":
    main()
