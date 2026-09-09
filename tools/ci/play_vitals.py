#!/usr/bin/env python3
"""Read Android vitals: crash and ANR rates, and the error clusters behind them.

androidpublisher, which the other scripts here speak, carries no quality data at
all. The rates Play warns about live in the Play Developer Reporting API, a
separate service with its own scope and its own permission grant.

Usage:
  play_vitals.py <sa_json> probe
  play_vitals.py <sa_json> rates [--days N] [--by versionCode|apiLevel|deviceModel]
  play_vitals.py <sa_json> issues [--kind crash|anr|both] [--days N] [--limit N]
                            [--samples N] [--version-code VC]

`probe` only reads the two metric-set descriptors, so it answers "does this
service account have access at all" without asking for any data.
"""

import argparse
import json
import sys

import requests

from play_track_admin import PKG, TIMEOUT, access_token

REPORTING_SCOPE = "https://www.googleapis.com/auth/playdeveloperreporting"
BASE = f"https://playdeveloperreporting.googleapis.com/v1beta1/apps/{PKG}"

# Play attributes a day to the app's reporting timezone, and a DAILY query must ask in
# that timezone or the buckets do not line up with what the Console shows.
DEFAULT_TZ = "America/Los_Angeles"

# The rates Play compares against its thresholds: user-perceived, weighted over 28 days
# by distinct users. The plain rate is kept because a per-version split of the weighted
# one is meaningless on its own.
CRASH_METRICS = ["userPerceivedCrashRate28dUserWeighted", "userPerceivedCrashRate", "distinctUsers"]
ANR_METRICS = ["userPerceivedAnrRate28dUserWeighted", "userPerceivedAnrRate", "distinctUsers"]

# The bad-behaviour thresholds Play warns on, so the output can say which rows are over.
CRASH_THRESHOLD = 0.0109
ANR_THRESHOLD = 0.0047


def flatten(prefix, value, out):
    """Encode a nested body as the dotted query parameters the search methods take."""
    if isinstance(value, dict):
        for k, v in value.items():
            flatten(f"{prefix}.{k}" if prefix else k, v, out)
    elif isinstance(value, list):
        for v in value:
            flatten(prefix, v, out)
    else:
        out.setdefault(prefix, []).append(str(value))
    return out


def datetime_at(day, tz):
    """A day boundary as the API's DateTime, which needs an explicit zone."""
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
        sys.exit(f"{r.status_code} {r.request.method} {r.url}\n{r.text}")
    return r.json()


def day_str(day):
    return f"{day.get('year')}-{day.get('month'):02d}-{day.get('day'):02d}"


def freshness(metric_set):
    """The last day the set has data for; a query past it comes back empty."""
    for f in metric_set.get("freshnessInfo", {}).get("freshnesses", []):
        if f.get("aggregationPeriod") == "DAILY":
            return f.get("latestEndTime", {})
    return {}


def cmd_probe(token, _args):
    for name in ("crashRateMetricSet", "anrRateMetricSet"):
        latest = freshness(call(token, f"{BASE}/{name}"))
        when = day_str(latest) if latest else "no DAILY freshness reported"
        print(f"{name}: readable, data through {when}")


def rates_row(row, metrics):
    values = {m["metric"]: m.get("decimalValue", {}).get("value") for m in row.get("metrics", [])}
    dims = {
        d["dimension"]: d.get("stringValue") or d.get("int64Value")
        for d in row.get("dimensions", [])
    }
    return dims, [values.get(m) for m in metrics]


def cmd_rates(token, args):
    import datetime

    for name, metrics, threshold in (
        ("crashRateMetricSet", CRASH_METRICS, CRASH_THRESHOLD),
        ("anrRateMetricSet", ANR_METRICS, ANR_THRESHOLD),
    ):
        latest = freshness(call(token, f"{BASE}/{name}"))
        if not latest:
            print(f"=== {name}: no data ===")
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
            "metrics": metrics,
            "pageSize": 1000,
        }
        res = call(token, f"{BASE}/{name}:query", method="POST", body=body)
        print(f"=== {name} {start}..{end} by {args.by or 'nothing'} ===")
        print("  " + "\t".join(["day"] + ([args.by] if args.by else []) + metrics))
        over = 0
        for row in res.get("rows", []):
            dims, values = rates_row(row, metrics)
            day = row.get("startTime", {})
            stamp = day_str(day)
            weighted = values[0]
            flag = ""
            if weighted is not None and float(weighted) > threshold:
                flag, over = "  OVER", over + 1
            cells = [stamp] + [str(v) for v in dims.values()] + [str(v) for v in values]
            print("  " + "\t".join(cells) + flag)
        print(f"  ({over} row(s) above the {threshold:.2%} threshold)")
        if not res.get("rows"):
            print(
                "  no rows: Play withholds dimensioned data below its privacy "
                "aggregation threshold, so try again without --by"
            )


def cmd_issues(token, args):
    import datetime

    kinds = ["CRASH", "ANR"] if args.kind == "both" else [args.kind.upper()]
    latest = freshness(call(token, f"{BASE}/crashRateMetricSet"))
    if not latest:
        sys.exit("no freshness data; run probe first")
    end = datetime.date(latest["year"], latest["month"], latest["day"])
    start = end - datetime.timedelta(days=args.days)
    for kind in kinds:
        terms = [f"errorIssueType = {kind}"]
        if args.version_code:
            terms.append(f"versionCode = {args.version_code}")
        params = flatten(
            "",
            {
                "interval": {
                    "startTime": datetime_at(start, args.timezone),
                    "endTime": datetime_at(end, args.timezone),
                },
                "filter": " AND ".join(terms),
                "orderBy": "distinctUsers desc",
                "pageSize": args.limit,
                "sampleErrorReportLimit": args.samples,
            },
            {},
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
            for report in issue.get("sampleErrorReports", []) or []:
                print(indent(fetch_report(token, report)))


def fetch_report(token, name):
    """The stack trace behind one sample, which the issue itself does not carry."""
    res = call(token, f"https://playdeveloperreporting.googleapis.com/v1beta1/{name}")
    return res.get("reportText", json.dumps(res, indent=2))


def indent(text):
    return "\n".join("      " + line for line in text.splitlines())


def main():
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
    i.add_argument("--limit", type=int, default=15)
    i.add_argument("--samples", type=int, default=1)
    i.add_argument("--version-code", type=int)
    args = p.parse_args()

    with open(args.sa_json, encoding="utf-8") as f:
        sa = json.load(f)
    token = access_token(sa, scope=REPORTING_SCOPE)
    {"probe": cmd_probe, "rates": cmd_rates, "issues": cmd_issues}[args.cmd](token, args)


if __name__ == "__main__":
    main()
