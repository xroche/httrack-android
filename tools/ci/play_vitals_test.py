#!/usr/bin/env python3
"""Tests for play_vitals.py, stubbing what the Reporting API really returns."""

import datetime
import io
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import play_vitals as pv  # noqa: E402


class Flatten(unittest.TestCase):
    """The search methods take the nested body as dotted query parameters."""

    def test_nested_interval_becomes_dotted_keys(self):
        got = pv.flatten("", {"interval": {"startTime": {"year": 2026, "hours": 0}}}, {})
        self.assertEqual(got["interval.startTime.year"], ["2026"])
        self.assertEqual(got["interval.startTime.hours"], ["0"])

    def test_a_zone_survives_the_flattening(self):
        got = pv.flatten("", {"i": pv.datetime_at(datetime.date(2026, 9, 1), "UTC")}, {})
        self.assertEqual(got["i.timeZone.id"], ["UTC"])
        self.assertEqual(got["i.day"], ["1"])

    def test_a_repeated_key_keeps_every_value(self):
        got = pv.flatten("", {"metrics": ["a", "b"]}, {})
        self.assertEqual(got["metrics"], ["a", "b"])


class Freshness(unittest.TestCase):
    """A query past the freshest day comes back empty, so the day drives the window."""

    def test_the_daily_period_is_picked_out_of_several(self):
        got = pv.freshness(
            {
                "freshnessInfo": {
                    "freshnesses": [
                        {"aggregationPeriod": "HOURLY", "latestEndTime": {"day": 8}},
                        {
                            "aggregationPeriod": "DAILY",
                            "latestEndTime": {"year": 2026, "month": 9, "day": 7},
                        },
                    ]
                }
            }
        )
        self.assertEqual(pv.day_str(got), "2026-09-07")

    def test_no_freshness_is_empty_not_an_exception(self):
        self.assertEqual(pv.freshness({}), {})


class Rows(unittest.TestCase):
    def test_a_missing_metric_reads_as_none_rather_than_zero(self):
        dims, values = pv.rates_row(
            {
                "dimensions": [{"dimension": "versionCode", "int64Value": "63"}],
                "metrics": [{"metric": "distinctUsers", "decimalValue": {"value": "12"}}],
            },
            ["userPerceivedCrashRate28dUserWeighted", "distinctUsers"],
        )
        self.assertEqual(dims, {"versionCode": "63"})
        self.assertEqual(values, [None, "12"])


class Errors(unittest.TestCase):
    """A 403 has two causes and the wrong one sends you to the wrong console."""

    def test_403_names_both_the_api_enablement_and_the_console_grant(self):
        resp = mock.Mock(status_code=403, text="PERMISSION_DENIED")
        with mock.patch("play_vitals.requests.request", return_value=resp):
            with mock.patch("sys.stdout", new=io.StringIO()):
                with self.assertRaises(SystemExit) as cm:
                    pv.call("t", "https://example/x")
        self.assertIn("has not been used in project", str(cm.exception))
        self.assertIn("View app quality information", str(cm.exception))


if __name__ == "__main__":
    unittest.main(verbosity=2)
