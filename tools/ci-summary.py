#!/usr/bin/env python3
"""Collect every surefire report into ONE verdict at the end of a CI job.

The build workflow runs each module's suite as its own step so that a red
in one does not hide the others. That is the right shape for CORRECTNESS
and a bad one for READING: six matrix legs x four suites means the thing
you actually want -- which tests failed -- is buried in four collapsed
step logs per leg, each thousands of lines long.

So: parse target/surefire-reports/*.xml, print one table plus the failing
testcases, and write it to $GITHUB_STEP_SUMMARY when that exists (the
job's summary page) as well as stdout (the step log).

NEVER fails the job. The suite steps already decide red or green; this
only reports, and a reporter that can break the build is a liability.
A module with no reports at all is called out as NOT RUN rather than
silently counted as zero -- that is the vacuous-green shape, and it is
exactly what a skipped step after a failed compile looks like.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

MODULES = ["core", "nlq", "pct", "parser-equivalence"]
MAX_LISTED = 40          # per module, before we start counting instead
MSG_CHARS = 220


def first_line(text):
    for line in (text or "").strip().splitlines():
        line = line.strip()
        if line:
            return line[:MSG_CHARS]
    return ""


def collect(module):
    """-> (ran, tests, failures, errors, skipped, [(kind, who, msg)])"""
    reports = sorted(glob.glob(os.path.join(
        module, "target", "surefire-reports", "*.xml")))
    if not reports:
        return False, 0, 0, 0, 0, []
    tests = failures = errors = skipped = 0
    bad = []
    for path in reports:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            # a fork that died mid-write leaves truncated XML; that IS a
            # signal, so say it rather than skipping the file quietly
            bad.append(("error", os.path.basename(path),
                        "unparseable surefire report (crashed fork?)"))
            continue
        tests += int(root.get("tests") or 0)
        failures += int(root.get("failures") or 0)
        errors += int(root.get("errors") or 0)
        skipped += int(root.get("skipped") or 0)
        for case in root.iter("testcase"):
            for kind in ("failure", "error"):
                el = case.find(kind)
                if el is None:
                    continue
                who = "{}.{}".format(
                    (case.get("classname") or "").rsplit(".", 1)[-1],
                    case.get("name") or "?")
                msg = first_line(el.get("message")) or first_line(el.text)
                bad.append((kind, who, msg or (el.get("type") or "")))
    return True, tests, failures, errors, skipped, bad


def main():
    out = ["## Test summary", "",
           "| module | tests | failures | errors | skipped |",
           "| --- | ---: | ---: | ---: | ---: |"]
    details = []
    any_ran = False
    for module in MODULES:
        ran, tests, failures, errors, skipped, bad = collect(module)
        if not ran:
            out.append("| `{}` | _not run_ | | | |".format(module))
            continue
        any_ran = True
        out.append("| `{}` | {} | {} | {} | {} |".format(
            module, tests, failures, errors, skipped))
        if not bad:
            continue
        details.append("")
        details.append("### `{}` — {} failing".format(module, len(bad)))
        details.append("")
        for kind, who, msg in bad[:MAX_LISTED]:
            details.append("- **{}** `{}`{}".format(
                kind, who, " — " + msg if msg else ""))
        if len(bad) > MAX_LISTED:
            details.append("- _…and {} more_".format(len(bad) - MAX_LISTED))
    if not any_ran:
        out += ["", "_No surefire reports anywhere — the suites never ran._",
                "_Look at the compile + install step._"]
    text = "\n".join(out + details) + "\n"

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(text)
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(text)


if __name__ == "__main__":
    main()
