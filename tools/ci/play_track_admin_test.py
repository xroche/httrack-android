#!/usr/bin/env python3
"""Self-contained test for play_track_admin: stubs the Play API, asserts on the requests sent.

Run: python3 tools/ci/play_track_admin_test.py
"""

import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import play_track_admin as pta  # noqa: E402

TRACKS = {
    "tracks": [
        {
            "track": "internal",
            "releases": [{"name": "3.50-beta-1", "versionCodes": ["89"], "status": "completed"}],
        },
        {
            "track": "alpha",
            "releases": [{"name": "3.49.14.86", "versionCodes": ["86"], "status": "completed"}],
        },
    ]
}


class FakeResponse:
    def __init__(self, status=200, payload=None):
        self.status_code = status
        self._payload = payload if payload is not None else {}
        self.ok = 200 <= status < 300
        self.text = json.dumps(self._payload)

    def json(self):
        return self._payload

    def raise_for_status(self):
        if not self.ok:
            raise AssertionError(self.text)


class FakeSession:
    """A Play API just complete enough to drive the script. reject_locale forces one 400."""

    def __init__(self, reject_locale=None):
        self.headers = {}
        self.puts = []
        self.committed = False
        self.deleted = []
        self.reject_locale = reject_locale
        self._rejected = False

    def post(self, url, **kw):
        if url.endswith(":commit"):
            self.committed = True
            return FakeResponse(200, {})
        return FakeResponse(200, {"id": "edit-1"})

    def get(self, url, **kw):
        return FakeResponse(200, TRACKS)

    def put(self, url, json=None, **kw):
        track = url.rsplit("/", 1)[-1]
        langs = [n["language"] for r in json["releases"] for n in r.get("releaseNotes", [])]
        if self.reject_locale and self.reject_locale in langs and not self._rejected:
            self._rejected = True
            return FakeResponse(
                400, {"error": {"message": f"Invalid language: {self.reject_locale}"}}
            )
        self.puts.append((track, json))
        return FakeResponse(200, json)

    def delete(self, url, **kw):
        self.deleted.append(url)
        return FakeResponse(200, {})


def run(argv, session):
    """Invoke main() with the network and the credential stubbed out."""
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        f.write("{}")
        sa = f.name
    buf = io.StringIO()
    try:
        with mock.patch.object(pta, "access_token", return_value="tok"), mock.patch.object(
            pta.requests, "Session", return_value=session
        ), mock.patch.object(sys, "argv", ["play_track_admin.py", sa] + argv), redirect_stdout(buf):
            pta.main()
    finally:
        os.unlink(sa)
    return buf.getvalue()


class Test(unittest.TestCase):
    def notes_dir(self):
        d = tempfile.mkdtemp()
        for loc, text in (("en-US", "hello"), ("fr-FR", "bonjour"), ("uz", "salom")):
            with open(os.path.join(d, loc + ".txt"), "w", encoding="utf-8") as f:
                f.write(text + "\n")
        return d

    def test_promote_and_clear_share_one_edit(self):
        s = FakeSession()
        run(
            [
                "promote",
                "89",
                "internal",
                "beta",
                "--notes-dir",
                self.notes_dir(),
                "--clear-track",
                "alpha",
            ],
            s,
        )
        self.assertEqual([t for t, _ in s.puts], ["beta", "alpha"])
        beta = dict(s.puts)["beta"]["releases"][0]
        self.assertEqual(beta["versionCodes"], ["89"])
        self.assertEqual(beta["status"], "completed")
        self.assertEqual(beta["name"], "3.50-beta-1")  # carried over from the source release
        self.assertEqual({n["language"] for n in beta["releaseNotes"]}, {"en-US", "fr-FR", "uz"})
        self.assertEqual(dict(s.puts)["alpha"]["releases"], [])
        self.assertTrue(s.committed)

    def test_dry_run_commits_nothing(self):
        s = FakeSession()
        out = run(
            [
                "promote",
                "89",
                "internal",
                "beta",
                "--notes-dir",
                self.notes_dir(),
                "--clear-track",
                "alpha",
                "--dry-run",
            ],
            s,
        )
        self.assertEqual(s.puts, [])
        self.assertFalse(s.committed)
        self.assertIn("dry run", out)
        self.assertTrue(s.deleted, "the dry run must abandon its edit")

    def test_rejected_locale_is_dropped_and_reported(self):
        s = FakeSession(reject_locale="uz")
        out = run(
            [
                "promote",
                "89",
                "internal",
                "beta",
                "--notes-dir",
                self.notes_dir(),
                "--clear-track",
                "alpha",
            ],
            s,
        )
        beta = dict(s.puts)["beta"]["releases"][0]
        self.assertEqual({n["language"] for n in beta["releaseNotes"]}, {"en-US", "fr-FR"})
        self.assertIn("dropping unsupported locale uz", out)
        self.assertTrue(s.committed)

    def test_unknown_version_code_aborts_before_any_put(self):
        s = FakeSession()
        with self.assertRaises(SystemExit):
            run(["promote", "77", "internal", "beta", "--notes-dir", self.notes_dir()], s)
        self.assertEqual(s.puts, [])
        self.assertFalse(s.committed)

    def test_over_long_note_aborts_before_the_edit_opens(self):
        d = self.notes_dir()
        with open(os.path.join(d, "de-DE.txt"), "w", encoding="utf-8") as f:
            f.write("x" * (pta.NOTE_LIMIT + 1))
        s = FakeSession()
        with self.assertRaises(SystemExit):
            run(["promote", "89", "internal", "beta", "--notes-dir", d], s)
        self.assertEqual(s.puts, [])

    def test_shipped_notes_are_within_the_limit(self):
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        notes = pta.load_notes(os.path.join(root, "play", "release-notes"))
        self.assertGreaterEqual(len(notes), 30)
        self.assertIn("en-US", [n["language"] for n in notes])


if __name__ == "__main__":
    unittest.main(verbosity=2)
