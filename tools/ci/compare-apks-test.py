#!/usr/bin/env python3
"""Exercise compare-apks.py. Each case pairs a verdict with a control, because the
first shell version of this comparison passed on four inputs it could not see."""

import os
import subprocess
import sys
import tempfile
import zipfile

SUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "compare-apks.py")
DEX = "classes.dex"
FIXED = (2026, 1, 1, 0, 0, 0)


def write(path, items):
    with zipfile.ZipFile(path, "w") as z:
        for name, data, method, date in items:
            info = zipfile.ZipInfo(name, date_time=date)
            info.compress_type = method
            z.writestr(info, data)


def run(a, b, *allowed):
    p = subprocess.run(
        [sys.executable, SUT, a, b, *allowed], capture_output=True, text=True
    )
    return p.returncode, p.stdout + p.stderr


def main():
    fails = 0
    tmp = tempfile.mkdtemp()
    base = [
        ("lib/arm64-v8a/libhttrack.so", b"native", zipfile.ZIP_DEFLATED, FIXED),
        (DEX, b"dex-one", zipfile.ZIP_DEFLATED, FIXED),
    ]

    def case(name, items_b, want, fragment, *allowed):
        nonlocal fails
        a = os.path.join(tmp, "a.apk")
        b = os.path.join(tmp, "b.apk")
        write(a, base)
        write(b, items_b)
        got, out = run(a, b, *allowed)
        if got != want or fragment not in out:
            print('FAIL %s: wanted %d with "%s", got %d' % (name, want, fragment, got))
            print("     " + out.replace("\n", "\n     ").strip())
            fails += 1
        else:
            print("ok   " + name)

    case("the same bytes", base, 0, "identical", DEX)
    case(
        "only the dex differs",
        [base[0], (DEX, b"dex-two", zipfile.ZIP_DEFLATED, FIXED)],
        0,
        "only allowed entries differ: " + DEX,
        DEX,
    )
    case(
        "the dex differs and nothing allows it",
        [base[0], (DEX, b"dex-two", zipfile.ZIP_DEFLATED, FIXED)],
        1,
        DEX,
    )
    case(
        "a native library differs",
        [
            ("lib/arm64-v8a/libhttrack.so", b"other", zipfile.ZIP_DEFLATED, FIXED),
            base[1],
        ],
        1,
        "libhttrack.so",
        DEX,
    )
    # The shell version compared name, size and CRC only, so each of these passed.
    case(
        "the dex is stored rather than deflated",
        [base[0], (DEX, b"dex-one", zipfile.ZIP_STORED, FIXED)],
        0,
        "only allowed entries differ",
        DEX,
    )
    case(
        "a library is stored rather than deflated",
        [
            ("lib/arm64-v8a/libhttrack.so", b"native", zipfile.ZIP_STORED, FIXED),
            base[1],
        ],
        1,
        "libhttrack.so",
        DEX,
    )
    case(
        "a timestamp differs",
        [
            (
                "lib/arm64-v8a/libhttrack.so",
                b"native",
                zipfile.ZIP_DEFLATED,
                (2027, 2, 2, 1, 1, 1),
            ),
            base[1],
        ],
        1,
        "libhttrack.so",
        DEX,
    )
    case(
        "the entry order is permuted",
        [base[1], base[0]],
        1,
        "order differ",
        DEX,
    )
    case(
        "an entry name holds a space",
        [base[0], base[1], ("assets/a b.txt", b"x", zipfile.ZIP_DEFLATED, FIXED)],
        1,
        "a b.txt",
        DEX,
    )
    case(
        "an entry named like the allowed one with a suffix",
        [
            base[0],
            (DEX, b"dex-one", zipfile.ZIP_DEFLATED, FIXED),
            (DEX + " backup", b"x", zipfile.ZIP_DEFLATED, FIXED),
        ],
        1,
        DEX + " backup",
        DEX,
    )

    # An unreadable APK must not read as equal.
    a = os.path.join(tmp, "a.apk")
    b = os.path.join(tmp, "truncated.apk")
    write(a, base)
    with open(a, "rb") as f:
        data = f.read()
    with open(b, "wb") as f:
        f.write(data[: len(data) // 2])
    got, out = run(a, b, DEX)
    if got == 0:
        print("FAIL a truncated APK read as equal")
        fails += 1
    else:
        print("ok   a truncated APK is refused")

    if not fails:
        print("all compare-apks checks passed")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
