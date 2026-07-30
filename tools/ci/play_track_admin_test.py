#!/usr/bin/env python3
"""Self-contained test for play_track_admin: stubs the Play API, asserts on the requests sent.

Run: python3 tools/ci/play_track_admin_test.py
"""

import copy
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

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

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
        {"track": "beta", "releases": []},
        {"track": "production", "releases": []},
    ]
}


class FakeResponse:
    """payload=None models a body-less 200, which is what Play answers a track clear with."""

    def __init__(self, status=200, payload=None):
        self.status_code = status
        self._payload = payload
        self.ok = 200 <= status < 300
        self.text = "" if payload is None else json.dumps(payload)
        self.content = self.text.encode()

    def json(self):
        if not self.content:
            raise ValueError("Expecting value: line 1 column 1 (char 0)")
        return self._payload

    def raise_for_status(self):
        if not self.ok:
            raise AssertionError(self.text)


class FakeSession:
    """A Play API just complete enough to drive the script.

    Every URL is checked against the real endpoint shapes, so a request aimed at the wrong
    edit, package or path fails here rather than silently passing.
    """

    def __init__(self, reject_message=None, reject_times=1, fail_after_commit=False):
        self.fail_after_commit = fail_after_commit
        self.headers = {}
        self.puts = []
        self.commits = []
        self.deleted = []
        self.reject_message = reject_message
        self.reject_times = reject_times
        self._edits = 0

    def _edit_id(self, url, suffix=""):
        prefix = f"{pta.BASE}/edits/"
        assert url.startswith(prefix), url
        rest = url.removeprefix(prefix)
        assert rest.endswith(suffix), url
        eid = rest.removesuffix(suffix)
        assert eid in [f"edit-{n}" for n in range(1, self._edits + 1)], f"stale edit id: {url}"
        return eid

    def post(self, url, **kw):
        if url.endswith(":commit"):
            self.commits.append(self._edit_id(url, ":commit"))
            return FakeResponse(200, {})
        assert url == f"{pta.BASE}/edits", url
        if self.commits and self.fail_after_commit:
            raise ConnectionError("network died after the commit")
        self._edits += 1
        return FakeResponse(200, {"id": f"edit-{self._edits}"})

    def get(self, url, **kw):
        self._edit_id(url, "/tracks")
        return FakeResponse(200, copy.deepcopy(TRACKS))

    def put(self, url, json=None, **kw):
        track = url.rsplit("/", 1)[-1]
        self._edit_id(url, f"/tracks/{track}")
        if self.reject_message is not None and self.reject_times > 0:
            self.reject_times -= 1
            return FakeResponse(400, {"error": {"message": self.reject_message}})
        self.puts.append((track, copy.deepcopy(json)))
        return FakeResponse(200) if not json["releases"] else FakeResponse(200, json)

    def delete(self, url, **kw):
        self.deleted.append(self._edit_id(url))
        return FakeResponse(200, {})


def notes_dir(locales=("en-US", "fr-FR", "de-DE")):
    d = tempfile.mkdtemp()
    for loc in locales:
        with open(os.path.join(d, pta.NOTE_PREFIX + loc), "w", encoding="utf-8") as f:
            f.write("note for " + loc)
    return d


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


def promote(*extra, notes=None):
    return ["promote", "89", "internal", "beta", "--notes-dir", notes or notes_dir()] + list(extra)


class Test(unittest.TestCase):
    def test_promote_and_clear_share_one_edit(self):
        s = FakeSession()
        run(promote("--clear-track", "alpha"), s)
        self.assertEqual([t for t, _ in s.puts], ["beta", "alpha"])
        beta = dict(s.puts)["beta"]["releases"][0]
        self.assertEqual(beta["versionCodes"], ["89"])
        self.assertEqual(beta["status"], "completed")
        self.assertNotIn("userFraction", beta)
        self.assertEqual(beta["name"], "3.50-beta-1")  # carried over from the source release
        self.assertEqual({n["language"] for n in beta["releaseNotes"]}, {"en-US", "fr-FR", "de-DE"})
        self.assertEqual(dict(s.puts)["alpha"]["releases"], [])
        self.assertEqual(len(s.commits), 1)

    def test_the_committed_edit_is_never_deleted(self):
        s = FakeSession()
        run(promote(), s)
        self.assertEqual(len(s.commits), 1)
        self.assertNotIn(s.commits[0], s.deleted)  # Play rejects a delete of a committed edit
        self.assertEqual(s.deleted, ["edit-2"])  # only the read-back edit is abandoned

    def test_a_failure_after_the_commit_leaves_the_committed_edit_alone(self):
        s = FakeSession(fail_after_commit=True)
        with self.assertRaises(ConnectionError):
            run(promote(), s)
        self.assertEqual(len(s.commits), 1)
        self.assertEqual(s.deleted, [])  # deleting a committed edit is an API error

    def test_a_body_less_200_on_the_clear_is_not_an_error(self):
        s = FakeSession()
        run(promote("--clear-track", "alpha"), s)
        self.assertEqual([t for t, _ in s.puts], ["beta", "alpha"])
        self.assertEqual(len(s.commits), 1)

    def test_dry_run_commits_nothing(self):
        s = FakeSession()
        out = run(promote("--clear-track", "alpha", "--dry-run"), s)
        self.assertEqual(s.puts, [])
        self.assertEqual(s.commits, [])
        self.assertIn("dry run", out)
        self.assertEqual(s.deleted, ["edit-1"])

    def test_rejected_locale_is_dropped_and_reported(self):
        s = FakeSession(reject_message="Invalid language: de-DE")
        out = run(promote(), s)
        beta = dict(s.puts)["beta"]["releases"][0]
        self.assertEqual({n["language"] for n in beta["releaseNotes"]}, {"en-US", "fr-FR"})
        self.assertIn("dropping unsupported locale de-DE", out)
        self.assertEqual(len(s.commits), 1)

    def test_two_rejected_locales_need_two_retries(self):
        # Play keeps refusing while any bad locale remains, so one drop per round trip.
        s = FakeSession(reject_message="languages de-DE, fr-FR not supported", reject_times=2)
        with self.assertRaises(SystemExit):  # ambiguous: names two, drops neither
            run(promote(), s)
        self.assertEqual(s.commits, [])

    def test_an_error_naming_no_locale_in_the_body_aborts(self):
        # The old parser scraped the first short word and could loop on an unchanged body.
        for message in (
            "Invalid language code: xx. Please use a supported language.",
            "Invalid value at 'releases[0].release_notes[0].language'",
            "Language ZH-CN is not a supported language.",
            "",
        ):
            s = FakeSession(reject_message=message, reject_times=99)
            with self.assertRaises(SystemExit, msg=message):
                run(promote(), s)
            self.assertEqual(s.puts, [], message)

    def test_clearing_the_promote_target_aborts(self):
        s = FakeSession()
        with self.assertRaises(SystemExit):
            run(
                [
                    "promote",
                    "89",
                    "internal",
                    "alpha",
                    "--notes-dir",
                    notes_dir(),
                    "--clear-track",
                    "alpha",
                ],
                s,
            )
        self.assertEqual(s.puts, [])
        self.assertEqual(s.commits, [])

    def test_clearing_production_aborts(self):
        s = FakeSession()
        with self.assertRaises(SystemExit):
            run(promote("--clear-track", "production"), s)
        self.assertEqual(s.puts, [])

    def test_multi_code_source_release_aborts(self):
        s = FakeSession()
        with mock.patch.dict(
            TRACKS["tracks"][0],
            {"releases": [{"name": "x", "versionCodes": ["88", "89"], "status": "completed"}]},
        ):
            with self.assertRaises(SystemExit):
                run(promote(), s)
        self.assertEqual(s.puts, [])

    def test_unknown_version_code_aborts_before_any_put(self):
        s = FakeSession()
        with self.assertRaises(SystemExit):
            run(["promote", "77", "internal", "beta", "--notes-dir", notes_dir()], s)
        self.assertEqual(s.puts, [])
        self.assertEqual(s.commits, [])

    def test_a_note_of_exactly_the_limit_is_accepted(self):
        d = notes_dir()
        with open(os.path.join(d, pta.NOTE_PREFIX + "it-IT"), "w", encoding="utf-8") as f:
            f.write("x" * 500)
        self.assertEqual(len(pta.load_notes(d)), 4)

    def test_a_note_one_over_the_limit_aborts_before_the_edit_opens(self):
        d = notes_dir()
        with open(os.path.join(d, pta.NOTE_PREFIX + "it-IT"), "w", encoding="utf-8") as f:
            f.write("x" * 501)
        s = FakeSession()
        with self.assertRaises(SystemExit):
            run(promote(notes=d), s)
        self.assertEqual(s.puts, [])

    def test_a_locale_play_does_not_accept_aborts(self):
        s = FakeSession()
        with self.assertRaises(SystemExit):  # httrack ships Uzbek; Play has no such locale
            run(promote(notes=notes_dir(("en-US", "uz"))), s)
        self.assertEqual(s.puts, [])

    def test_shipped_notes_load_clean(self):
        notes = pta.load_notes(os.path.join(ROOT, "distribution", "whatsnew"))
        self.assertEqual(len(notes), 29)
        self.assertIn("en-US", [n["language"] for n in notes])

    def test_shipped_notes_carry_no_trailing_whitespace(self):
        # play-publish.yml's action sends the file bytes verbatim, and they count towards 500.
        d = os.path.join(ROOT, "distribution", "whatsnew")
        for name in sorted(os.listdir(d)):
            with open(os.path.join(d, name), encoding="utf-8") as f:
                raw = f.read()
            self.assertEqual(raw, raw.strip(), name)
            self.assertLessEqual(len(raw), pta.NOTE_LIMIT, name)


if __name__ == "__main__":
    unittest.main(verbosity=2)
