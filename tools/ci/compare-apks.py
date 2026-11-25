#!/usr/bin/env python3
"""Compare two APKs entry by entry and name what differs.

F-Droid ships our signature only if their buildserver reproduces our APK, so the
comparison has to be exact. Entries named on the command line are allowed to
differ, which is how a known difference is tracked without dropping the guard
over every other entry.
"""

import sys
import zipfile


def entries(path):
    with zipfile.ZipFile(path) as z:
        return [
            (i.filename, i.CRC, i.file_size, i.compress_type, i.date_time)
            for i in z.infolist()
        ]


def main(argv):
    if len(argv) < 3:
        print("usage: compare-apks.py A.apk B.apk [allowed entry ...]", file=sys.stderr)
        return 2
    a, b, allowed = argv[1], argv[2], set(argv[3:])

    with open(a, "rb") as fa, open(b, "rb") as fb:
        if fa.read() == fb.read():
            print("identical, byte for byte")
            return 0

    ea, eb = entries(a), entries(b)
    if not ea or not eb:
        print("one of the APKs holds no entry at all", file=sys.stderr)
        return 2
    if [e[0] for e in ea] != [e[0] for e in eb]:
        print("the entry names or their order differ:", file=sys.stderr)
        print(
            "  " + " ".join(set(e[0] for e in ea) ^ set(e[0] for e in eb)),
            file=sys.stderr,
        )
        return 1

    differ = [x[0] for x, y in zip(ea, eb) if x != y]
    unexpected = [name for name in differ if name not in allowed]
    if unexpected:
        print("these entries differ and must not:", file=sys.stderr)
        for name in unexpected:
            print("  " + name, file=sys.stderr)
        return 1
    # The bytes differ, so something must have, and an empty list would mean the
    # comparison cannot see it.
    if not differ:
        print("the APKs differ but every entry matches", file=sys.stderr)
        return 1
    print("only allowed entries differ: " + " ".join(differ))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
