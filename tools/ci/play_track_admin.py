#!/usr/bin/env python3
"""Move an already-uploaded versionCode between Play tracks, and clear a track.

Play refuses a second upload of a versionCode, so a build that is already on one
track can only reach another one through the Publishing API. The promote and the
clear share a single edit, so either both land or neither does.

Usage:
  play_track_admin.py <sa_json> list
  play_track_admin.py <sa_json> promote <versionCode> <fromTrack> <toTrack>
                      [--notes-dir DIR] [--clear-track TRACK] [--dry-run]

Track names are the API's, not the Console's: alpha is Closed testing, beta is
Open testing.
"""

import argparse
import base64
import json
import os
import re
import sys
import time

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

PKG = "com.httrack.android"
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"
NOTE_LIMIT = 500  # Play's per-language cap, in Unicode characters.
NOTE_PREFIX = "whatsnew-"  # fastlane's naming, so play-publish.yml can read the same files.
TIMEOUT = 30

# Play's release-note locales, restricted to the languages httrack itself ships. httrack's
# Uzbek is deliberately absent: Play has no Uzbek locale, and offering one fails the upload.
PLAY_LOCALES = {
    "bg",
    "cs-CZ",
    "da-DK",
    "de-DE",
    "el-GR",
    "en-US",
    "es-ES",
    "et",
    "fi-FI",
    "fr-FR",
    "hr",
    "hu-HU",
    "it-IT",
    "ja-JP",
    "mk-MK",
    "nl-NL",
    "no-NO",
    "pl-PL",
    "pt-BR",
    "pt-PT",
    "ro",
    "ru-RU",
    "sk",
    "sl",
    "sv-SE",
    "tr-TR",
    "uk",
    "zh-CN",
    "zh-TW",
}


def b64url(b):
    return base64.urlsafe_b64encode(b).rstrip(b"=")


def access_token(sa):
    """Mint an OAuth token from the service-account JSON (RS256 JWT bearer flow)."""
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    claim = {
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/androidpublisher",
        "aud": sa["token_uri"],
        "iat": now,
        "exp": now + 3600,
    }
    signing_input = b64url(json.dumps(header).encode()) + b"." + b64url(json.dumps(claim).encode())
    key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    sig = key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
    assertion = (signing_input + b"." + b64url(sig)).decode()
    r = requests.post(
        sa["token_uri"],
        data={"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer", "assertion": assertion},
        timeout=TIMEOUT,
    )
    r.raise_for_status()
    return r.json()["access_token"]


def load_notes(notes_dir):
    """Read whatsnew-<locale> files into the API's releaseNotes list.

    Both the length and the locale are checked here rather than at PUT time: Play's
    rejection messages are not worth parsing, and a bad note should cost nothing.
    """
    notes, bad = [], []
    for name in sorted(os.listdir(notes_dir)):
        if not name.startswith(NOTE_PREFIX):
            continue
        locale = name.removeprefix(NOTE_PREFIX)
        with open(os.path.join(notes_dir, name), encoding="utf-8") as f:
            text = f.read().strip()
        if len(text) > NOTE_LIMIT:
            bad.append(f"{name}: {len(text)} chars, over the {NOTE_LIMIT} limit")
        if locale not in PLAY_LOCALES:
            bad.append(f"{name}: {locale} is not a Play release-note locale")
        notes.append({"language": locale, "text": text})
    if bad:
        sys.exit("bad release notes:\n  " + "\n  ".join(bad))
    if not notes:
        sys.exit(f"no {NOTE_PREFIX}* files under {notes_dir}")
    return notes


def find_release(track, version_code):
    for rel in track.get("releases", []):
        if version_code in [int(c) for c in rel.get("versionCodes") or []]:
            return rel
    return None


def put_track(session, eid, track, releases):
    """PUT a track body, dropping a locale Play rejects rather than failing the whole run."""
    body = {"track": track, "releases": releases}
    while True:
        r = session.put(f"{BASE}/edits/{eid}/tracks/{track}", json=body, timeout=TIMEOUT)
        if r.ok:
            # Clearing a track answers 200 with no body at all, so do not assume JSON.
            return r.json() if r.content else {}
        present = {n["language"] for rel in body["releases"] for n in rel.get("releaseNotes", [])}
        bad = unsupported_language(r, present)
        if bad is None:
            sys.exit(f"PUT {track} failed: {r.status_code} {r.text}")
        print(f"  dropping unsupported locale {bad}")
        for rel in body["releases"]:
            rel["releaseNotes"] = [n for n in rel.get("releaseNotes", []) if n["language"] != bad]


def unsupported_language(response, present):
    """The locale from `present` that Play's 400 names, or None if it names none of them.

    Only a locale actually in the body can be returned, so the caller always makes progress
    and the retry terminates. Play's wording varies, so this matches rather than parses.
    """
    if response.status_code != 400 or not present:
        return None
    try:
        message = response.json().get("error", {}).get("message", "")
    except ValueError:
        return None
    words = set(re.findall(r"[A-Za-z]{2}(?:-[A-Za-z]{2,4})?", message))
    named = [loc for loc in present if loc in words]
    return named[0] if len(named) == 1 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sa_json")
    ap.add_argument("mode", choices=["list", "promote"])
    ap.add_argument("version_code", nargs="?", type=int)
    ap.add_argument("from_track", nargs="?")
    ap.add_argument("to_track", nargs="?")
    ap.add_argument("--notes-dir", help="directory of <locale>.txt release notes")
    ap.add_argument("--clear-track", help="track to leave with no release, in the same edit")
    ap.add_argument("--dry-run", action="store_true", help="print the plan, commit nothing")
    args = ap.parse_args()

    with open(args.sa_json, encoding="utf-8") as f:
        sa = json.load(f)
    session = requests.Session()
    session.headers["Authorization"] = f"Bearer {access_token(sa)}"

    # Read the notes before opening an edit: a malformed one should abort for free.
    notes = load_notes(args.notes_dir) if args.notes_dir else None

    r = session.post(f"{BASE}/edits", timeout=TIMEOUT)
    r.raise_for_status()
    eid = r.json()["id"]
    try:
        tracks = session.get(f"{BASE}/edits/{eid}/tracks", timeout=TIMEOUT).json().get("tracks", [])
        by_name = {t["track"]: t for t in tracks}

        print("=== tracks before ===")
        for t in tracks:
            rels = [
                f"vc{c}({rel.get('status')})"
                for rel in t.get("releases", [])
                for c in rel.get("versionCodes", [])
            ]
            print(f"  {t['track']:<24} {rels}")

        if args.mode == "list":
            return

        if not (args.version_code and args.from_track and args.to_track):
            sys.exit("promote needs <versionCode> <fromTrack> <toTrack>")
        if args.from_track not in by_name:
            sys.exit(f"no such track: {args.from_track}")
        src = find_release(by_name[args.from_track], args.version_code)
        if src is None:
            sys.exit(f"vc{args.version_code} is not on the {args.from_track} track")
        # A multi-code release would lose its siblings, since the PUT replaces the track.
        codes = src.get("versionCodes") or []
        if len(codes) > 1:
            sys.exit(f"vc{args.version_code} shares a release with {', '.join(codes)}")

        release = {
            "name": src.get("name", str(args.version_code)),
            "versionCodes": [str(args.version_code)],
            "status": "completed",
        }
        if notes:
            release["releaseNotes"] = notes
        elif src.get("releaseNotes"):
            release["releaseNotes"] = src["releaseNotes"]

        plan = [(args.to_track, [release])]
        if args.clear_track:
            if args.clear_track not in by_name:
                sys.exit(f"no such track: {args.clear_track}")
            # Both PUTs share one edit, so clearing the target would just erase the promote.
            if args.clear_track == args.to_track:
                sys.exit(f"--clear-track {args.clear_track} is also the promote target")
            if args.clear_track == "production":
                sys.exit("refusing to unpublish production")
            plan.append((args.clear_track, []))

        if args.dry_run:
            print("=== plan (dry run, nothing committed) ===")
            print(json.dumps({t: rels for t, rels in plan}, indent=2, ensure_ascii=False))
            return

        for track, releases in plan:
            print(f"=== PUT {track} ({len(releases)} release(s)) ===")
            print(
                json.dumps(put_track(session, eid, track, releases), indent=2, ensure_ascii=False)
            )

        c = session.post(f"{BASE}/edits/{eid}:commit", timeout=TIMEOUT)
        if not c.ok:
            sys.exit(f"commit failed: {c.status_code} {c.text}")
        eid = None
        print("committed")

        r = session.post(f"{BASE}/edits", timeout=TIMEOUT)  # fresh edit, just to read back
        r.raise_for_status()
        eid = r.json()["id"]
        tracks = session.get(f"{BASE}/edits/{eid}/tracks", timeout=TIMEOUT).json().get("tracks", [])
        print("=== tracks after ===")
        for t in tracks:
            rels = [
                f"vc{c}({rel.get('status')})"
                for rel in t.get("releases", [])
                for c in rel.get("versionCodes", [])
            ]
            print(f"  {t['track']:<24} {rels}")
    finally:
        if eid:
            session.delete(f"{BASE}/edits/{eid}", timeout=TIMEOUT)


if __name__ == "__main__":
    main()
