#!/usr/bin/env python3
"""Collect Play reviews for com.httrack.android from both sources Play offers.

reviews.list carries only the last week, and only reviews with text. The monthly
CSVs in the report bucket hold the history and the textless ratings, and lag three
to seven days. So "every review" means both, merged on review id.

Usage:
  play_reviews.py <sa_json> recent [--json]
  play_reviews.py <sa_json> bulk <bucket> [--since YYYYMM]
  play_reviews.py <sa_json> all <bucket> [--since YYYYMM]

<bucket> is pubsite_prod_rev_<developer id>, shown in the Console under Download
reports as "Copy Cloud Storage URI". Reading it needs the service account to hold
"View app information and download bulk reports".
"""

import argparse
import csv
import io
import json
import re
import sys

import requests

from play_track_admin import PKG, TIMEOUT, access_token

REVIEWS = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}/reviews"
STORAGE = "https://storage.googleapis.com/storage/v1/b"
STORAGE_SCOPE = "https://www.googleapis.com/auth/devstorage.read_only"
FIELDS = ["id", "author", "rating", "text", "version", "device", "modified", "source"]


def fetch_recent(token):
    """Every review the API will admit to, following pagination to the end."""
    out, page, seen = [], None, set()
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
        if not page or page in seen:
            return out
        seen.add(page)


def list_report_objects(token, bucket, since=None):
    """Names of the monthly review CSVs, oldest first, optionally from YYYYMM on."""
    prefix = f"reviews/reviews_{PKG}_"
    names, page, pages = [], None, set()
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
        if r.status_code == 404:
            raise SystemExit(f"no such bucket: gs://{bucket}. Check the Cloud Storage URI.")
        r.raise_for_status()
        body = r.json()
        names.extend(o["name"] for o in body.get("items", []))
        page = body.get("nextPageToken")
        if not page or page in pages:
            break
        pages.add(page)
    if not names:
        raise SystemExit(
            f"no {prefix}* objects in gs://{bucket}. Wrong bucket, or the reports are not "
            "generated yet. Refusing to report an empty history as no history."
        )
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
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("utf-16-le")


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


def review_id(link):
    """The bare reviewId out of a Review Link, so bulk and api rows can meet."""
    m = re.search(r"[?&]reviewId=([^&]+)", link)
    return m.group(1) if m else link


def normalise_csv(row):
    def col(*names):
        for n in names:
            for k, v in row.items():
                if k and k.strip().lower() == n:
                    return (v or "").strip()
        return ""

    return {
        "id": review_id(col("review link", "review id")),
        # The export carries no reviewer name, only a locale, so this stays empty.
        "author": "",
        "rating": col("star rating"),
        "text": " ".join(x for x in (col("review title"), col("review text")) if x).replace(
            "\n", " "
        ),
        "version": col("app version name"),
        "device": col("device"),
        "modified": col("review last update date and time", "review submit date and time"),
        "source": "bulk",
    }


def merge(rows):
    """Drop reviews present in both sources, keeping the bucket row: it carries the
    rating even when the user left no text. Rows with no id are all kept."""
    seen, merged = set(), []
    for row in sorted(rows, key=lambda r: r["source"] != "bulk"):
        if row["id"] and row["id"] in seen:
            continue
        seen.add(row["id"])
        merged.append(row)
    return merged


def write_rows(rows, out):
    w = csv.DictWriter(out, fieldnames=FIELDS)
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
    if args.json and args.command != "recent":
        raise SystemExit("--json carries the api shape only, so it fits recent")

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

    merged = merge(rows)
    write_rows(merged, sys.stdout)
    print(f"total: {len(merged)} review(s)", file=sys.stderr)


if __name__ == "__main__":
    main()
