"""Publish Surefire counts and fail if an expected module has no executed tests."""

import argparse
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("modules", nargs="+")
    args = parser.parse_args()
    lines = [
        "## Test results",
        "",
        "| Module | Tests | Failures | Errors | Skipped |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    problems = []
    for module in args.modules:
        counts = {name: 0 for name in ("tests", "failures", "errors", "skipped")}
        reports = sorted((Path(module) / "target" / "surefire-reports").glob("TEST-*.xml"))
        for report in reports:
            try:
                suite = ET.parse(report).getroot()
                if suite.tag != "testsuite":
                    raise ValueError("expected a Surefire testsuite")
                for name in counts:
                    count = int(suite.get(name, "0"))
                    if count < 0:
                        raise ValueError("negative test count")
                    counts[name] += count
            except (OSError, ET.ParseError, ValueError) as error:
                problems.append(f"Cannot read {report}: {error}")
        lines.append(f"| {module} | {counts['tests']} | {counts['failures']} | {counts['errors']} | {counts['skipped']} |")
        if not reports:
            problems.append(f"{module}: no Surefire XML reports were produced.")
        elif counts["tests"] <= counts["skipped"]:
            problems.append(f"{module}: no tests executed (all missing or skipped).")
        if counts["failures"] or counts["errors"]:
            problems.append(f"{module}: test failures or errors were reported.")

    if problems:
        lines.extend(["", *[f"- {problem}" for problem in problems]])
    summary = "\n".join(lines) + "\n"
    print(summary)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as output:
            output.write(summary)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
