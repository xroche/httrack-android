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
import sys
import time

import requests
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

PKG = "com.httrack.android"
BASE = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}"
NOTE_LIMIT = 500  # Play's per-language cap on release notes.
TIMEOUT = 30


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
    """Read <locale>.txt into the API's releaseNotes list, rejecting over-long ones."""
    notes, over = [], []
    for name in sorted(os.listdir(notes_dir)):
        if not name.endswith(".txt"):
            continue
        with open(os.path.join(notes_dir, name), encoding="utf-8") as f:
            text = f.read().strip()
        if len(text) > NOTE_LIMIT:
            over.append(f"{name} ({len(text)} chars)")
        notes.append({"language": name[:-4], "text": text})
    if over:
        sys.exit(f"release notes over Play's {NOTE_LIMIT}-char limit: {', '.join(over)}")
    if not notes:
        sys.exit(f"no <locale>.txt under {notes_dir}")
    return notes


def find_release(track, version_code):
    for rel in track.get("releases", []):
        if version_code in [int(c) for c in rel.get("versionCodes", [])]:
            return rel
    return None


def put_track(session, eid, track, releases):
    """PUT a track body, dropping any locale Play rejects rather than failing the run."""
    body = {"track": track, "releases": releases}
    while True:
        r = session.put(f"{BASE}/edits/{eid}/tracks/{track}", json=body, timeout=TIMEOUT)
        if r.ok:
            return r.json()
        bad = unsupported_language(r)
        if bad is None:
            sys.exit(f"PUT {track} failed: {r.status_code} {r.text}")
        dropped = False
        for rel in body["releases"]:
            keep = [n for n in rel.get("releaseNotes", []) if n["language"] != bad]
            dropped |= len(keep) != len(rel.get("releaseNotes", []))
            rel["releaseNotes"] = keep
        # Retrying an unchanged body would spin forever, so a locale we cannot find is fatal.
        if not dropped:
            sys.exit(f"PUT {track} failed on locale {bad!r}, which is not in the body: {r.text}")
        print(f"  dropping unsupported locale {bad}")


def unsupported_language(response):
    """The locale Play rejected in a 400, or None if the error is something else."""
    if response.status_code != 400:
        return None
    message = response.json().get("error", {}).get("message", "")
    if "language" not in message.lower():
        return None
    # e.g. "Invalid language: uz" / "... language 'uz' is not supported"
    for token in message.replace("'", " ").replace('"', " ").replace(":", " ").split():
        if 2 <= len(token) <= 6 and token.replace("-", "").isalpha() and token[0].islower():
            return token
    return None


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
