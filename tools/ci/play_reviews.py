#!/usr/bin/env python3
"""Collect Play reviews for com.httrack.android from both sources Play offers.

Neither source alone is complete. reviews.list carries only reviews created or
modified in the last week, and only those with text, so a bare star rating never
appears. The monthly CSVs in the report bucket hold the history, including
ratings without text, but lag three to seven days.

So "every review" means the bucket for the history and the API for the tail, and
this merges the two on review id.

Usage:
  play_reviews.py <sa_json> recent [--json]
  play_reviews.py <sa_json> bulk <bucket> [--since YYYYMM] [--out DIR]
  play_reviews.py <sa_json> all <bucket> [--out DIR]

<bucket> is the report bucket, pubsite_prod_rev_<developer id>, whose name the
Console shows under Download reports as "Copy Cloud Storage URI". Reading it needs
the service account to hold "View app information and download bulk reports".
"""

import argparse
import csv
import io
import json
import sys

import requests

from play_track_admin import PKG, TIMEOUT, access_token

REVIEWS = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}/reviews"
STORAGE = "https://storage.googleapis.com/storage/v1/b"
STORAGE_SCOPE = "https://www.googleapis.com/auth/devstorage.read_only"


def fetch_recent(token):
    """Every review the API will admit to, following pagination to the end."""
    out, page = [], None
    while True:
        params = {"maxResults": 100}
        if page:
            params["token"] = page
        r = requests.get(
            REVIEWS,
            headers={"Authorization": f"Bearer {token}"},
            params=params,
            timeout=TIMEOUT,
        )
        r.raise_for_status()
        body = r.json()
        out.extend(body.get("reviews", []))
        page = body.get("tokenPagination", {}).get("nextPageToken")
        if not page:
            return out


def list_report_objects(token, bucket, since=None):
    """Names of the monthly review CSVs, oldest first, optionally from YYYYMM on."""
    prefix = f"reviews/reviews_{PKG}_"
    names, page = [], None
    while True:
        params = {"prefix": prefix}
        if page:
            params["pageToken"] = page
        r = requests.get(
            f"{STORAGE}/{bucket}/o",
            headers={"Authorization": f"Bearer {token}"},
            params=params,
            timeout=TIMEOUT,
        )
        if r.status_code in (401, 403):
            raise SystemExit(
                f"cannot read gs://{bucket}/{prefix}* ({r.status_code}). Grant the service "
                "account 'View app information and download bulk reports' in the Console."
            )
        r.raise_for_status()
        body = r.json()
        names.extend(o["name"] for o in body.get("items", []))
        page = body.get("nextPageToken")
        if not page:
            break
    if since:
        names = [n for n in names if report_month(n) >= since]
    return sorted(names)


def report_month(name):
    """The YYYYMM out of reviews_<package>_YYYYMM.csv."""
    return name.rsplit("_", 1)[-1][:6]


def decode_report(raw):
    """Play writes these CSVs UTF-16, so decoding as UTF-8 yields one wide junk column."""
    for bom, enc in (
        (b"\xff\xfe", "utf-16"),
        (b"\xfe\xff", "utf-16"),
        (b"\xef\xbb\xbf", "utf-8-sig"),
    ):
        if raw.startswith(bom):
            return raw.decode(enc)
    return raw.decode("utf-8")


def fetch_report(token, bucket, name):
    r = requests.get(
        f"{STORAGE}/{bucket}/o/{requests.utils.quote(name, safe='')}",
        headers={"Authorization": f"Bearer {token}"},
        params={"alt": "media"},
        timeout=TIMEOUT,
    )
    r.raise_for_status()
    return list(csv.DictReader(io.StringIO(decode_report(r.content))))


def normalise_api(review):
    """One API review to the flat shape the CSV rows already have."""
    latest = (review.get("comments") or [{}])[0].get("userComment", {})
    return {
        "id": review.get("reviewId", ""),
        "author": review.get("authorName", ""),
        "rating": str(latest.get("starRating", "")),
        "text": (latest.get("text") or "").replace("\n", " ").strip(),
        "version": str(latest.get("appVersionName") or ""),
        "device": latest.get("device", ""),
        "modified": str(latest.get("lastModified", {}).get("seconds", "")),
        "source": "api",
    }


def normalise_csv(row):
    def col(*names):
        for n in names:
            for k, v in row.items():
                if k and k.strip().lower() == n:
                    return (v or "").strip()
        return ""

    return {
        "id": col("review link", "review id"),
        "author": col("reviewer language", "review submit date and time"),
        "rating": col("star rating"),
        "text": " ".join(x for x in (col("review title"), col("review text")) if x),
        "version": col("app version name", "app version code"),
        "device": col("device"),
        "modified": col("review last update date and time", "review submit date and time"),
        "source": "bulk",
    }


def write_rows(rows, out):
    w = csv.DictWriter(out, fieldnames=list(rows[0].keys()) if rows else ["id"])
    w.writeheader()
    w.writerows(rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sa_json")
    ap.add_argument("command", choices=("recent", "bulk", "all"))
    ap.add_argument("bucket", nargs="?")
    ap.add_argument("--since", help="earliest monthly report, YYYYMM")
    ap.add_argument("--json", action="store_true", help="raw API json instead of csv")
    args = ap.parse_args()

    with open(args.sa_json, encoding="utf-8") as f:
        sa = json.load(f)
    if args.command in ("bulk", "all") and not args.bucket:
        raise SystemExit(f"{args.command} needs the report bucket name")

    rows = []
    if args.command in ("recent", "all"):
        api = fetch_recent(access_token(sa))
        if args.json:
            json.dump(api, sys.stdout, indent=2, ensure_ascii=False)
            return
        rows += [normalise_api(r) for r in api]
        print(f"api: {len(api)} review(s) in the last week", file=sys.stderr)

    if args.command in ("bulk", "all"):
        token = access_token(sa, STORAGE_SCOPE)
        names = list_report_objects(token, args.bucket, args.since)
        print(f"bulk: {len(names)} monthly report(s)", file=sys.stderr)
        for name in names:
            got = fetch_report(token, args.bucket, name)
            rows += [normalise_csv(r) for r in got]
            print(f"  {name}: {len(got)}", file=sys.stderr)

    # The API tail overlaps the newest CSV; the bucket row wins because it carries
    # the rating even when the user left no text.
    seen, merged = set(), []
    for row in sorted(rows, key=lambda r: r["source"]):
        if row["id"] and row["id"] in seen:
            continue
        seen.add(row["id"])
        merged.append(row)
    write_rows(merged, sys.stdout)
    print(f"total: {len(merged)} review(s)", file=sys.stderr)


if __name__ == "__main__":
    main()
