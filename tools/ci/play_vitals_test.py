#!/usr/bin/env python3
"""Tests for play_vitals.py, stubbing what the Reporting API really returns."""

import argparse
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
        got = pv.flatten({"interval": {"startTime": {"year": 2026, "hours": 0}}})
        self.assertEqual(got["interval.startTime.year"], ["2026"])
        self.assertEqual(got["interval.startTime.hours"], ["0"])

    def test_a_zone_survives_the_flattening(self):
        got = pv.flatten({"i": pv.datetime_at(datetime.date(2026, 9, 1), "UTC")})
        self.assertEqual(got["i.timeZone.id"], ["UTC"])
        self.assertEqual(got["i.day"], ["1"])

    def test_a_repeated_key_keeps_every_value(self):
        got = pv.flatten({"metrics": ["a", "b"]})
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
        self.assertIsNone(values["userPerceivedCrashRate28dUserWeighted"])
        self.assertEqual(values["distinctUsers"], "12")

    def test_an_empty_dimension_value_is_kept_rather_than_read_as_absent(self):
        self.assertEqual(pv.dimension_value({"stringValue": ""}), "")
        self.assertEqual(pv.dimension_value({"int64Value": "0"}), "0")
        self.assertIsNone(pv.dimension_value({}))


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


class Body(unittest.TestCase):
    """Play answers some calls 200 with no body at all, which json() cannot parse."""

    def response(self, status=200, content=b"", payload=None):
        r = mock.Mock(status_code=status, ok=status < 400, content=content, text=content.decode())
        r.json.side_effect = (lambda: payload) if payload is not None else ValueError("no json")
        return r

    def test_an_empty_200_reads_as_an_empty_result_rather_than_raising(self):
        with mock.patch("play_vitals.requests.request", return_value=self.response()):
            self.assertEqual(pv.call("t", "https://example/x"), {})

    def test_a_200_carrying_junk_exits_naming_the_url(self):
        junk = self.response(content=b"<html>nope</html>")
        with mock.patch("play_vitals.requests.request", return_value=junk):
            with self.assertRaises(SystemExit) as cm:
                pv.call("t", "https://example/x")
        self.assertIn("non-JSON", str(cm.exception))
        self.assertIn("https://example/x", str(cm.exception))

    def test_a_500_exits_naming_the_method_and_the_url(self):
        with mock.patch("play_vitals.requests.request", return_value=self.response(503)):
            with self.assertRaises(SystemExit) as cm:
                pv.call("t", "https://example/x", method="POST")
        self.assertIn("503", str(cm.exception))
        self.assertIn("POST", str(cm.exception))


class Rates(unittest.TestCase):
    """Tests that rates marks the rows over Play's threshold."""

    CRASH = pv.METRIC_SETS[0]

    def row(self, weighted, day=7):
        return {
            "startTime": {"year": 2026, "month": 9, "day": day},
            "metrics": [{"metric": self.CRASH.weighted, "decimalValue": {"value": weighted}}],
        }

    def count_over(self, *weighted):
        rows = {"rows": [self.row(w, day=i + 1) for i, w in enumerate(weighted)]}
        with mock.patch("sys.stdout", new=io.StringIO()):
            return pv.print_rates(rows, self.CRASH, "", "2026-09-01..2026-09-07")

    def test_a_rate_over_the_threshold_counts(self):
        self.assertEqual(self.count_over("0.1455"), 1)

    def test_a_rate_under_the_threshold_does_not(self):
        self.assertEqual(self.count_over("0.0001"), 0)

    def test_a_rate_exactly_on_the_threshold_is_not_over(self):
        self.assertEqual(self.count_over(str(self.CRASH.threshold)), 0)

    def test_each_row_is_judged_rather_than_only_the_first(self):
        self.assertEqual(self.count_over("0.0001", "0.5", "0.9"), 2)

    def test_the_crash_and_anr_thresholds_are_not_interchangeable(self):
        crash, anr = pv.METRIC_SETS
        self.assertGreater(crash.threshold, anr.threshold)
        self.assertIn("Crash", crash.weighted)
        self.assertIn("Anr", anr.weighted)

    def test_a_row_missing_the_weighted_metric_is_not_counted(self):
        rows = {"rows": [{"startTime": {"year": 2026, "month": 9, "day": 7}, "metrics": []}]}
        with mock.patch("sys.stdout", new=io.StringIO()):
            self.assertEqual(pv.print_rates(rows, self.CRASH, "", "span"), 0)


class Probe(unittest.TestCase):
    def test_probe_reads_every_metric_set_and_reports_its_freshest_day(self):
        payload = {
            "freshnessInfo": {
                "freshnesses": [
                    {
                        "aggregationPeriod": "DAILY",
                        "latestEndTime": {"year": 2026, "month": 9, "day": 7},
                    }
                ]
            }
        }
        out = io.StringIO()
        with mock.patch("play_vitals.call", return_value=payload):
            with mock.patch("sys.stdout", new=out):
                pv.cmd_probe("t", None)
        printed = out.getvalue()
        for metric_set in pv.METRIC_SETS:
            self.assertIn(metric_set.name, printed)
        self.assertEqual(printed.count("2026-09-07"), len(pv.METRIC_SETS))


class SearchParams(unittest.TestCase):
    """The command paths build their own query, and nothing used to exercise them.

    A refactor of flatten() left both call sites passing the old argument order. Every
    unit test still passed, because they all called flatten() directly.
    """

    def captured_params(self, fn):
        seen = {}

        def fake_call(_token, url, method="GET", body=None, params=None):
            seen["url"], seen["params"] = url, params
            return {}

        with mock.patch("play_vitals.call", side_effect=fake_call):
            fn()
        return seen

    def test_search_reports_sends_a_dotted_interval_and_filter(self):
        seen = self.captured_params(
            lambda: pv.search_reports(
                "t",
                "apps/com.httrack.android/abc123",
                datetime.date(2026, 8, 10),
                datetime.date(2026, 9, 7),
                "UTC",
                1,
            )
        )
        self.assertTrue(seen["url"].endswith("/errorReports:search"))
        self.assertEqual(seen["params"]["interval.startTime.year"], ["2026"])
        self.assertEqual(seen["params"]["interval.endTime.day"], ["7"])
        self.assertEqual(seen["params"]["filter"], ["errorIssueId = abc123"])
        self.assertEqual(seen["params"]["pageSize"], ["1"])

    def test_the_issue_id_is_taken_from_the_last_path_segment(self):
        seen = self.captured_params(
            lambda: pv.search_reports(
                "t",
                "apps/com.httrack.android/deadbeef",
                datetime.date(2026, 9, 1),
                datetime.date(2026, 9, 7),
                "UTC",
                2,
            )
        )
        self.assertEqual(seen["params"]["filter"], ["errorIssueId = deadbeef"])


FULL_REPORT = {
    "appVersion": {"versionCode": "63"},
    "osVersion": {"apiLevel": "24"},
    "deviceModel": {
        "marketingName": "Galaxy A51",
        "deviceId": {"buildBrand": "samsung", "buildDevice": "a51"},
    },
}
FULL_LABEL = "    [vc 63, api 24, samsung/a51 (Galaxy A51)]"
FRESHNESS = {
    "freshnessInfo": {
        "freshnesses": [
            {"aggregationPeriod": "DAILY", "latestEndTime": {"year": 2026, "month": 9, "day": 7}}
        ]
    }
}


class Fields(unittest.TestCase):
    """Every field of an ErrorReport is optional, so absence is the normal case."""

    def test_a_complete_report_yields_all_four_fields(self):
        self.assertEqual(pv.report_fields(FULL_REPORT), ("63", "24", "samsung/a51", "Galaxy A51"))

    def test_an_empty_report_yields_four_absent_fields(self):
        self.assertEqual(pv.report_fields({}), (None, None, None, None))

    def test_a_null_subobject_reads_as_absent_rather_than_raising(self):
        report = {"appVersion": None, "osVersion": None, "deviceModel": None}
        self.assertEqual(pv.report_fields(report), (None, None, None, None))

    def test_a_scalar_where_an_object_belongs_reads_as_absent(self):
        report = {"osVersion": "24", "deviceModel": "Galaxy A51"}
        self.assertEqual(pv.report_fields(report), (None, None, None, None))

    def test_a_null_device_id_reads_as_absent_rather_than_raising(self):
        report = {"deviceModel": {"marketingName": "Galaxy A51", "deviceId": None}}
        self.assertEqual(pv.report_fields(report).build, None)

    def test_a_device_named_by_brand_alone_keeps_that_brand(self):
        report = {"deviceModel": {"deviceId": {"buildBrand": "samsung"}}}
        self.assertEqual(pv.report_fields(report).build, "samsung")

    def test_a_non_string_build_field_does_not_raise(self):
        report = {"deviceModel": {"deviceId": {"buildBrand": 1, "buildDevice": "a51"}}}
        self.assertEqual(pv.report_fields(report).build, "1/a51")


class Lines(unittest.TestCase):
    """The device spread needs every sample labelled, not only the ones printed in full."""

    def reports(self, count):
        return [
            dict(FULL_REPORT, reportText=f"trace-{i}", osVersion={"apiLevel": str(20 + i)})
            for i in range(count)
        ]

    def test_an_absent_field_prints_a_question_mark_under_its_own_label(self):
        self.assertEqual(pv.report_lines([{}], 0), ["    [vc ?, api ?, ?]"])

    def test_a_field_present_but_null_also_prints_a_question_mark(self):
        report = {"osVersion": {"apiLevel": None}, "appVersion": {"versionCode": None}}
        self.assertEqual(pv.report_lines([report], 0), ["    [vc ?, api ?, ?]"])

    def test_a_complete_report_names_the_build_the_api_level_and_the_device(self):
        self.assertEqual(pv.report_lines([FULL_REPORT], 0), [FULL_LABEL])

    def test_every_sample_is_labelled_and_only_the_first_carries_a_trace(self):
        self.assertEqual(
            pv.report_lines(self.reports(3), 1),
            [
                "    [vc 63, api 20, samsung/a51 (Galaxy A51)]",
                "      trace-0",
                "    [vc 63, api 21, samsung/a51 (Galaxy A51)]",
                "    [vc 63, api 22, samsung/a51 (Galaxy A51)]",
            ],
        )

    def test_raising_the_trace_limit_prints_more_of_them(self):
        printed = "\n".join(pv.report_lines(self.reports(3), 3))
        self.assertIn("trace-2", printed)

    def test_a_report_with_no_text_still_prints_its_label(self):
        self.assertEqual(pv.report_lines([FULL_REPORT], 1)[0], FULL_LABEL)


class Parser(unittest.TestCase):
    """The workflow is the only caller, so a flag it passes must exist here."""

    def parse(self, *argv):
        return pv.build_parser().parse_args(["sa.json", "issues", *argv])

    def test_both_report_counts_default_to_one(self):
        args = self.parse()
        self.assertEqual((args.reports, args.traces), (1, 1))

    def test_the_workflow_argv_parses(self):
        args = self.parse("--days", "28", "--kind", "both", "--reports", "20", "--traces", "2")
        self.assertEqual((args.reports, args.traces), (20, 2))


class Issues(unittest.TestCase):
    """cmd_issues must ask the API for as many reports as it was told to read."""

    def run_issues(self, wanted, traces, available=5):
        pool = [dict(FULL_REPORT, reportText=f"trace-{i}") for i in range(available)]
        asked = {}

        def fake_call(_token, url, method="GET", body=None, params=None):
            if url.endswith("/crashRateMetricSet"):
                return FRESHNESS
            if url.endswith("/errorIssues:search"):
                asked["issues"] = params
                return {"errorIssues": [{"name": "apps/com.httrack.android/abc"}]}
            asked["reports"] = params
            return {"errorReports": pool[: int(params["pageSize"][0])]}

        args = argparse.Namespace(
            kind="crash", days=28, limit=15, reports=wanted, traces=traces, version_code=None
        )
        out = io.StringIO()
        with mock.patch("play_vitals.call", side_effect=fake_call):
            with mock.patch("sys.stdout", new=out):
                pv.cmd_issues("t", args)
        return out.getvalue(), asked

    def test_both_searches_ask_for_the_number_of_reports_requested(self):
        _, asked = self.run_issues(wanted=3, traces=1)
        self.assertEqual(asked["reports"]["pageSize"], ["3"])
        self.assertEqual(asked["issues"]["sampleErrorReportLimit"], ["3"])

    def test_every_report_the_api_returns_is_labelled_once(self):
        printed, _ = self.run_issues(wanted=3, traces=1)
        self.assertEqual(printed.count(FULL_LABEL), 3)


if __name__ == "__main__":
    unittest.main(verbosity=2)
