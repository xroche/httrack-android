#!/usr/bin/env python3
"""Read Android vitals: crash and ANR rates, and the error clusters behind them.

androidpublisher, which the other scripts here speak, carries no quality data at
all. The rates Play warns about live in the Play Developer Reporting API, a
separate service with its own scope and its own permission grant.

Usage:
  play_vitals.py <sa_json> probe
  play_vitals.py <sa_json> rates [--days N] [--by versionCode|apiLevel|deviceModel]
  play_vitals.py <sa_json> issues [--kind crash|anr|both] [--days N] [--limit N]
                            [--reports N] [--traces N] [--version-code VC]

`probe` only reads the two metric-set descriptors, so it answers "does this
service account have access at all" without asking for any data.
"""

import argparse
import collections
import json
import sys

import requests

from play_track_admin import PKG, TIMEOUT, access_token

REPORTING_SCOPE = "https://www.googleapis.com/auth/playdeveloperreporting"
BASE = f"https://playdeveloperreporting.googleapis.com/v1beta1/apps/{PKG}"

# Play attributes a day to the app's reporting timezone, and a DAILY query must ask in
# that timezone or the buckets do not line up with what the Console shows.
DEFAULT_TZ = "America/Los_Angeles"

MetricSet = collections.namedtuple("MetricSet", "name weighted metrics threshold")
ReportFields = collections.namedtuple("ReportFields", "version_code api_level build marketing_name")

# The rate Play compares against its threshold is the user-perceived one, weighted over 28
# days by distinct users. The plain rate rides along because a per-version split of the
# weighted one is meaningless on its own.
METRIC_SETS = (
    MetricSet(
        "crashRateMetricSet",
        "userPerceivedCrashRate28dUserWeighted",
        ["userPerceivedCrashRate28dUserWeighted", "userPerceivedCrashRate", "distinctUsers"],
        0.0109,
    ),
    MetricSet(
        "anrRateMetricSet",
        "userPerceivedAnrRate28dUserWeighted",
        ["userPerceivedAnrRate28dUserWeighted", "userPerceivedAnrRate", "distinctUsers"],
        0.0047,
    ),
)


def flatten(body):
    """Encode a nested body as the dotted query parameters the search methods take."""
    out = {}
    _flatten(body, out, "")
    return out


def _flatten(value, out, prefix):
    if isinstance(value, dict):
        for k, v in value.items():
            _flatten(v, out, f"{prefix}.{k}" if prefix else k)
    elif isinstance(value, list):
        for v in value:
            _flatten(v, out, prefix)
    else:
        out.setdefault(prefix, []).append(str(value))


def datetime_at(day, tz):
    """Returns a day boundary as the API's DateTime, which needs an explicit zone."""
    return {
        "year": day.year,
        "month": day.month,
        "day": day.day,
        "hours": 0,
        "timeZone": {"id": tz},
    }


def call(token, url, method="GET", body=None, params=None):
    r = requests.request(
        method,
        url,
        headers={"Authorization": f"Bearer {token}"},
        json=body,
        params=params,
        timeout=TIMEOUT,
    )
    if r.status_code == 403:
        sys.exit(
            "403 from the Reporting API. Two different causes, and the message says which:\n"
            '  "has not been used in project N" -> enable Play Developer Reporting API on\n'
            "     that Cloud project, the one owning the service account key.\n"
            '  "caller does not have permission" -> in Play Console, Users and permissions,\n'
            "     grant this service account 'View app quality information (read-only)'.\n\n"
            f"{r.text}"
        )
    if not r.ok:
        sys.exit(f"{r.status_code} {method} {url}\n{r.text}")
    if not r.content:
        return {}
    try:
        return r.json()
    except ValueError:
        sys.exit(f"{r.status_code} {method} {url} answered non-JSON:\n{r.text[:500]}")


def day_str(day):
    return f"{day.get('year')}-{day.get('month'):02d}-{day.get('day'):02d}"


def freshness(metric_set):
    """Returns the last day the set has data for. A query past it comes back empty."""
    for f in metric_set.get("freshnessInfo", {}).get("freshnesses", []):
        if f.get("aggregationPeriod") == "DAILY":
            return f.get("latestEndTime", {})
    return {}


def cmd_probe(token, _args):
    for metric_set in METRIC_SETS:
        latest = freshness(call(token, f"{BASE}/{metric_set.name}"))
        when = day_str(latest) if latest else "no DAILY freshness reported"
        print(f"{metric_set.name}: readable, data through {when}")


def dimension_value(d):
    """Returns a dimension's value. It has two typed fields, and "" and 0 are both real."""
    for key in ("stringValue", "int64Value"):
        if key in d:
            return d[key]
    return None


def rates_row(row, metrics):
    """Returns one row as (dimension name -> value, the metrics in the order asked for)."""
    values = {m["metric"]: m.get("decimalValue", {}).get("value") for m in row.get("metrics", [])}
    dims = {d["dimension"]: dimension_value(d) for d in row.get("dimensions", [])}
    return dims, {m: values.get(m) for m in metrics}


def print_rates(res, metric_set, by, span):
    """Print one metric set's timeline, marking the rows Play would call bad behaviour."""
    print(f"=== {metric_set.name} {span} by {by or 'nothing'} ===")
    print("  " + "\t".join(["day"] + ([by] if by else []) + metric_set.metrics))
    over = 0
    for row in res.get("rows", []):
        dims, values = rates_row(row, metric_set.metrics)
        weighted = values[metric_set.weighted]
        flag = ""
        if weighted is not None and float(weighted) > metric_set.threshold:
            flag, over = "  OVER", over + 1
        cells = [day_str(row.get("startTime", {}))]
        cells += [str(v) for v in dims.values()] + [str(v) for v in values.values()]
        print("  " + "\t".join(cells) + flag)
    print(f"  ({over} row(s) above the {metric_set.threshold:.2%} threshold)")
    if not res.get("rows"):
        print(
            "  no rows: Play withholds dimensioned data below its privacy "
            "aggregation threshold, so try again without --by"
        )
    return over


def cmd_rates(token, args):
    import datetime

    over = {}
    for metric_set in METRIC_SETS:
        latest = freshness(call(token, f"{BASE}/{metric_set.name}"))
        if not latest:
            print(f"=== {metric_set.name}: no data ===")
            continue
        end = datetime.date(latest["year"], latest["month"], latest["day"])
        start = end - datetime.timedelta(days=args.days)
        body = {
            "timelineSpec": {
                "aggregationPeriod": "DAILY",
                "startTime": datetime_at(start, args.timezone),
                "endTime": datetime_at(end, args.timezone),
            },
            "dimensions": [args.by] if args.by else [],
            "metrics": metric_set.metrics,
            "pageSize": 1000,
        }
        res = call(token, f"{BASE}/{metric_set.name}:query", method="POST", body=body)
        over[metric_set.name] = print_rates(res, metric_set, args.by, f"{start}..{end}")
    bad = [name for name, count in over.items() if count]
    print(f"\nover threshold: {', '.join(bad) if bad else 'nothing'}")


def cmd_issues(token, args):
    import datetime

    kinds = ["CRASH", "ANR"] if args.kind == "both" else [args.kind.upper()]
    latest = freshness(call(token, f"{BASE}/crashRateMetricSet"))
    if not latest:
        sys.exit("no freshness data; run probe first")
    end = datetime.date(latest["year"], latest["month"], latest["day"])
    start = end - datetime.timedelta(days=args.days)
    # errorIssues:search rejects a named zone ("Unsupported timezone"), unlike the metric
    # queries, which need one. UTC is the only id it takes.
    tz = "UTC"
    for kind in kinds:
        terms = [f"errorIssueType = {kind}"]
        if args.version_code:
            terms.append(f"versionCode = {args.version_code}")
        params = flatten(
            {
                "interval": {
                    "startTime": datetime_at(start, tz),
                    "endTime": datetime_at(end, tz),
                },
                "filter": " AND ".join(terms),
                "orderBy": "distinctUsers desc",
                "pageSize": args.limit,
                # errorIssues:search answers 400 above 1 ("'sample_error_reports' field only
                # supports the values 0 and 1"). The samples we print come from the search below.
                "sampleErrorReportLimit": 1,
            }
        )
        res = call(token, f"{BASE}/errorIssues:search", params=params)
        issues = res.get("errorIssues", [])
        print(f"=== top {kind} clusters {start}..{end} ({len(issues)}) ===")
        for issue in issues:
            print(
                f"\n--- {issue.get('distinctUsers')} users, "
                f"{issue.get('errorReportCount')} reports, "
                f"last seen {issue.get('lastErrorReportTime')}"
            )
            print(f"    {issue.get('type')} in {issue.get('location')}")
            print(f"    {issue.get('cause')}")
            print(f"    id {issue.get('name')}")
            reports = search_reports(
                token, issue.get("name", ""), start, end, tz, limit=args.reports
            )
            for line in report_lines(reports, traces=args.traces):
                print(line)


def subobject(d, key):
    """Returns a nested object, or an empty one when the field is absent or null."""
    value = d.get(key)
    return value if isinstance(value, dict) else {}


def report_fields(report):
    """Returns one report's versions and device, each None when the field is absent."""
    model = subobject(report, "deviceModel")
    device = subobject(model, "deviceId")
    # Play types both build fields as strings, so str() only guards a stray number here.
    build = "/".join(str(p) for p in (device.get("buildBrand"), device.get("buildDevice")) if p)
    return ReportFields(
        subobject(report, "appVersion").get("versionCode"),
        subobject(report, "osVersion").get("apiLevel"),
        build or None,
        model.get("marketingName"),
    )


def report_lines(reports, traces):
    """Returns one label per report, plus a stack trace for the first `traces` of them."""
    lines = []
    for rank, report in enumerate(reports):
        fields = report_fields(report)
        named = f" ({fields.marketing_name})" if fields.marketing_name else ""
        lines.append(
            f"    [vc {fields.version_code or '?'}, "
            f"api {fields.api_level or '?'}, {fields.build or '?'}{named}]"
        )
        if rank < traces:
            lines.append(indent(report.get("reportText", "")))
    return lines


def search_reports(token, issue_name, start, end, tz, limit):
    """Returns the reports behind one cluster, each with its device and versions.

    The issue carries sample report names, but only the names, and they are not a
    resource path any GET accepts. errorReports:search filtered on the issue id is the
    route that works.
    """
    issue_id = issue_name.rsplit("/", 1)[-1]
    params = flatten(
        {
            "interval": {"startTime": datetime_at(start, tz), "endTime": datetime_at(end, tz)},
            "filter": f"errorIssueId = {issue_id}",
            "pageSize": limit,
        }
    )
    res = call(token, f"{BASE}/errorReports:search", params=params)
    return res.get("errorReports", [])


def indent(text):
    return "\n".join("      " + line for line in text.splitlines())


def build_parser():
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("sa_json")
    p.add_argument("--timezone", default=DEFAULT_TZ)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("probe")
    r = sub.add_parser("rates")
    r.add_argument("--days", type=int, default=28)
    r.add_argument(
        "--by", default="versionCode", choices=["versionCode", "apiLevel", "deviceModel", ""]
    )
    i = sub.add_parser("issues")
    i.add_argument("--kind", default="both", choices=["crash", "anr", "both"])
    i.add_argument("--days", type=int, default=28)
    i.add_argument("--limit", type=int, default=15, help="clusters listed per kind")
    i.add_argument("--reports", type=int, default=1, help="reports read per cluster")
    i.add_argument("--traces", type=int, default=1, help="of those, how many print a stack trace")
    i.add_argument("--version-code", type=int)
    return p


def main():
    args = build_parser().parse_args()

    with open(args.sa_json, encoding="utf-8") as f:
        sa = json.load(f)
    token = access_token(sa, scope=REPORTING_SCOPE)
    {"probe": cmd_probe, "rates": cmd_rates, "issues": cmd_issues}[args.cmd](token, args)


if __name__ == "__main__":
    main()
