#!/usr/bin/env python3
"""Tests for play_reviews.py, stubbing what Play really returns, not what is convenient."""

import io
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import play_reviews as pr  # noqa: E402


class Resp:
    def __init__(self, status=200, payload=None, content=b""):
        self.status_code = status
        self._payload = payload
        self.content = content

    def json(self):
        return self._payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise AssertionError(f"unexpected raise_for_status on {self.status_code}")


class Decode(unittest.TestCase):
    """Play writes the bulk CSVs UTF-16; reading them as UTF-8 is the whole trap."""

    def test_utf16_le_with_bom(self):
        raw = '"Star Rating"\n"5"\n'.encode("utf-16")  # utf-16 emits the LE BOM
        self.assertEqual(pr.decode_report(raw).splitlines()[0], '"Star Rating"')

    def test_utf16_be_with_bom(self):
        raw = b"\xfe\xff" + '"Star Rating"'.encode("utf-16-be")
        self.assertEqual(pr.decode_report(raw), '"Star Rating"')

    def test_utf8_bom_and_plain(self):
        self.assertEqual(pr.decode_report('"a"'.encode("utf-8-sig")), '"a"')
        self.assertEqual(pr.decode_report(b'"a"'), '"a"')

    def test_utf16_csv_parses_into_columns(self):
        raw = "Star Rating,Review Text\n5,good\n".encode("utf-16")
        rows = list(__import__("csv").DictReader(io.StringIO(pr.decode_report(raw))))
        self.assertEqual(rows[0]["Star Rating"], "5")


class Recent(unittest.TestCase):
    def test_follows_pagination_to_the_end(self):
        """Three pages, so stopping after two fails."""
        page = {"reviews": [{"reviewId": "a"}], "tokenPagination": {"nextPageToken": "t1"}}
        pages = [
            Resp(payload=page),
            Resp(
                payload={
                    "reviews": [{"reviewId": "b"}],
                    "tokenPagination": {"nextPageToken": "t2"},
                }
            ),
            Resp(payload={"reviews": [{"reviewId": "c"}]}),
        ]
        with mock.patch.object(pr.requests, "get", side_effect=pages) as g:
            got = pr.fetch_recent("tok")
        self.assertEqual([r["reviewId"] for r in got], ["a", "b", "c"])
        self.assertEqual(g.call_count, 3)
        self.assertEqual(g.call_args_list[1].kwargs["params"]["token"], "t1")
        self.assertEqual(g.call_args_list[2].kwargs["params"]["token"], "t2")

    def test_a_repeated_token_does_not_loop_forever(self):
        """A server echoing one token must not spin until the mock runs dry."""
        same = {"reviews": [{"reviewId": "a"}], "tokenPagination": {"nextPageToken": "t"}}
        with mock.patch.object(pr.requests, "get", side_effect=[Resp(payload=same)] * 50) as g:
            pr.fetch_recent("tok")
        self.assertLessEqual(g.call_count, 3)

    def test_empty_week_is_not_an_error(self):
        with mock.patch.object(pr.requests, "get", return_value=Resp(payload={})):
            self.assertEqual(pr.fetch_recent("tok"), [])


class Bucket(unittest.TestCase):
    def test_denied_names_the_missing_grant(self):
        for status in (401, 403):
            with mock.patch.object(pr.requests, "get", return_value=Resp(status=status)):
                with self.assertRaises(SystemExit) as e:
                    pr.list_report_objects("tok", "pubsite_prod_rev_1")
            self.assertIn("View app information", str(e.exception))
            self.assertNotEqual(e.exception.code, 0)

    def test_a_readable_bucket_with_no_reports_is_not_reported_as_none(self):
        """An empty listing must not read as an empty history."""
        with mock.patch.object(pr.requests, "get", return_value=Resp(payload={})):
            with self.assertRaises(SystemExit) as e:
                pr.list_report_objects("tok", "pubsite_prod_rev_1")
        self.assertNotEqual(e.exception.code, 0)

    def test_lists_every_page_and_filters_by_month(self):
        name = "reviews/reviews_com.httrack.android_%s.csv"
        pages = [
            Resp(payload={"items": [{"name": name % "201703"}], "nextPageToken": "p"}),
            Resp(payload={"items": [{"name": name % "202608"}]}),
        ]
        with mock.patch.object(pr.requests, "get", side_effect=pages):
            self.assertEqual(len(pr.list_report_objects("tok", "b")), 2)
        with mock.patch.object(
            pr.requests, "get", side_effect=[Resp(payload=p.json()) for p in pages]
        ):
            got = pr.list_report_objects("tok", "b", since="202000")
        self.assertEqual(got, [name % "202608"])


# The header Play actually emits, verbatim. The normalise tests are circular
# without it: they would pass on names invented to match the code.
PLAY_HEADER = (
    "Package Name,App Version Code,App Version Name,Reviewer Language,Device,"
    "Review Submit Date and Time,Review Submit Millis Since Epoch,"
    "Review Last Update Date and Time,Review Last Update Millis Since Epoch,"
    "Star Rating,Review Title,Review Text,Developer Reply Date and Time,"
    "Developer Reply Millis Since Epoch,Developer Reply Text,Review Link"
)


class RealHeader(unittest.TestCase):
    """Guards against a schema the code names but Play does not emit."""

    def rows(self, body):
        raw = (PLAY_HEADER + "\n" + body).encode("utf-16")
        import csv as _csv

        return list(_csv.DictReader(io.StringIO(pr.decode_report(raw))))

    def test_every_field_the_tool_reads_is_populated(self):
        link = "https://play.google.com/console/developers/x/app/y/review-details?reviewId=abc123"
        row = self.rows(
            "com.httrack.android,99,3.50-beta-7,en,Pixel,2026-08-01T10:00:00Z,1,"
            "2026-08-02T10:00:00Z,2,4,Nice,Works well,,,," + link
        )[0]
        got = pr.normalise_csv(row)
        self.assertEqual(got["id"], "abc123")
        self.assertEqual(got["rating"], "4")
        self.assertEqual(got["text"], "Nice Works well")
        self.assertEqual(got["version"], "3.50-beta-7")
        self.assertEqual(got["device"], "Pixel")
        self.assertEqual(got["modified"], "2026-08-02T10:00:00Z")

    def test_a_rating_with_no_text_still_carries_its_id(self):
        link = "https://example/review-details?reviewId=zz9"
        row = self.rows(
            "com.httrack.android,99,3.50,en,Pixel,2026-08-01T10:00:00Z,1,"
            "2026-08-02T10:00:00Z,2,5,,,,,," + link
        )[0]
        got = pr.normalise_csv(row)
        self.assertEqual((got["rating"], got["text"], got["id"]), ("5", "", "zz9"))


class Merge(unittest.TestCase):
    """The bucket row must win, because only it carries a textless rating."""

    def test_bulk_wins_and_the_review_is_not_duplicated(self):
        rows = [
            {"id": "a1", "source": "api", "text": "from api"},
            {"id": "a1", "source": "bulk", "text": "from bulk"},
        ]
        merged = pr.merge(rows)
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0]["text"], "from bulk")

    def test_rows_without_an_id_are_all_kept(self):
        merged = pr.merge([{"id": "", "source": "bulk"}, {"id": "", "source": "bulk"}])
        self.assertEqual(len(merged), 2)

    def test_a_review_link_and_a_bare_id_meet(self):
        self.assertEqual(pr.review_id("https://x/review-details?reviewId=q7&hl=en"), "q7")
        self.assertEqual(pr.review_id("q7"), "q7")


class Normalise(unittest.TestCase):
    def test_csv_columns_match_case_insensitively(self):
        row = {"Star Rating": "1", "Review Text": "crashes", "App Version Name": "3.49"}
        got = pr.normalise_csv(row)
        self.assertEqual((got["rating"], got["text"], got["version"]), ("1", "crashes", "3.49"))

    def test_a_rating_with_no_text_survives(self):
        """The bucket's reason to exist: the API drops these entirely."""
        got = pr.normalise_csv({"Star Rating": "4", "Review Text": ""})
        self.assertEqual(got["rating"], "4")
        self.assertEqual(got["text"], "")

    def test_api_review_reads_the_latest_comment(self):
        got = pr.normalise_api(
            {
                "reviewId": "x",
                "authorName": "A",
                "comments": [
                    {"userComment": {"starRating": 2, "text": "slow\nstill", "device": "d"}}
                ],
            }
        )
        self.assertEqual((got["id"], got["rating"], got["device"]), ("x", "2", "d"))
        self.assertNotIn("\n", got["text"])

    def test_api_review_without_comments_does_not_crash(self):
        self.assertEqual(pr.normalise_api({"reviewId": "y"})["rating"], "")


if __name__ == "__main__":
    unittest.main(verbosity=2)
